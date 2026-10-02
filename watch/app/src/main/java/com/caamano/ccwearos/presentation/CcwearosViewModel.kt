package com.caamano.ccwearos.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.CcwearosRepository
import com.caamano.ccwearos.data.ClaimResult
import com.caamano.ccwearos.data.ClaudeStatus
import com.caamano.ccwearos.data.CommandText
import com.caamano.ccwearos.data.Metrics
import com.caamano.ccwearos.data.PromptMode
import com.caamano.ccwearos.data.RecentSession
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.SharedSessionStaleness
import com.caamano.ccwearos.data.TaskKind
import com.caamano.ccwearos.data.ToolEvent
import com.caamano.ccwearos.data.WatchRepository
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.home.AWAITING_KEY
import com.caamano.ccwearos.presentation.home.BlockerDismissalStore
import com.caamano.ccwearos.presentation.home.BlockerDismissals
import com.caamano.ccwearos.presentation.home.ErrorBuzzDedupe
import com.caamano.ccwearos.presentation.home.HomeEvent
import com.caamano.ccwearos.presentation.home.LastRun
import com.caamano.ccwearos.presentation.home.MAC_OFFLINE_KEY
import com.caamano.ccwearos.presentation.home.NO_DICTATION_KEY
import com.caamano.ccwearos.presentation.home.OutcomeCompletionDetector
import com.caamano.ccwearos.presentation.home.Overlay
import com.caamano.ccwearos.presentation.home.RoutingInput
import com.caamano.ccwearos.presentation.home.SendState
import com.caamano.ccwearos.presentation.home.WATCH_OFFLINE_KEY
import com.caamano.ccwearos.presentation.home.addDismissal
import com.caamano.ccwearos.presentation.home.blockerDismissKey
import com.caamano.ccwearos.presentation.home.blockedContentKey
import com.caamano.ccwearos.presentation.home.routeOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

// User-visible error copy (Spanish, tuteo).
internal object ErrorCopy {
    const val OFFLINE = "Sin conexión. Espera a que el reloj se reconecte."
    const val PROMPT_GONE = "Esa solicitud ya no está activa."
    const val ANSWER_FAILED = "No se pudo enviar tu respuesta. Intenta de nuevo."
    const val STOP_FAILED = "No se pudo detener la tarea. Intenta de nuevo."
    const val RESET_FAILED = "No se pudo reiniciar la pantalla. Intenta de nuevo."
    const val CLAIM_FAILED = "No se pudo abrir la sesión. Intenta de nuevo."
}

/** Emits true only after [this] has been true for [ms]; false immediately. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.holdTrue(ms: Long): Flow<Boolean> =
    distinctUntilChanged().transformLatest { v ->
        if (v) {
            delay(ms)
            emit(true)
        } else {
            emit(false)
        }
    }

/** Emits once a minute, forever. Drives time-based re-evaluation in the VM. */
internal fun minuteTicks(): Flow<Unit> = flow {
    while (true) {
        delay(60_000)
        emit(Unit)
    }
}

class CcwearosViewModel(
    private val repo: WatchRepository = CcwearosRepository(),
    private val answers: AnswerGate = AnswerGate.shared,
    // Wall clock + re-evaluation ticks for the stale-lock check below.
    // Injected so tests don't depend on real time or an endless ticker.
    private val clock: () -> Long = System::currentTimeMillis,
    staleTicks: Flow<Unit> = minuteTicks(),
    // Dismissed blocker ts values survive app restarts (SharedPreferences).
    private val dismissalStore: BlockerDismissalStore = BlockerDismissals.store,
) : ViewModel() {

    private val ticks: Flow<Unit> = staleTicks.onStart { emit(Unit) }

    // ROUTING-CRITICAL flows use SharingStarted.Eagerly: the listener stays
    // alive even when no UI is collecting (i.e., screen off / ambient). Cost
    // is one Firebase value listener kept warm; benefit is no "wrapper not
    // reachable" flicker on wake — observed twice in 24h on real watch with
    // WhileSubscribed(5_000). These drive screen routing in WearApp and the
    // allow/deny guards below, so a fresh value on wake matters more than the
    // tiny battery cost of an idle listener.
    val status: StateFlow<WrapperStatus> = repo.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, WrapperStatus.OFFLINE)

    val permissionPrompt: StateFlow<String?> = repo.permissionPrompt
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val permissionPromptId: StateFlow<String?> = repo.permissionPromptId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live Firebase socket state. Allow/deny are refused while false. */
    val connected: StateFlow<Boolean> = repo.connected
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // A stale hook lock (no heartbeat for 30 min) is shown as no session, so
    // the ask button comes back even if the daemon never cleared it. The tick
    // re-checks age while the RTDB value itself doesn't change.
    val sharedSession: StateFlow<SharedSessionMeta?> =
        combine(repo.sharedSession, ticks) { meta, _ ->
            SharedSessionStaleness.visible(meta, clock())
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True once the CURRENT prompt id was answered (here or from the notification). */
    val answered: StateFlow<Boolean> = combine(permissionPromptId, answers.answeredId) { id, done ->
        id != null && id == done
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Last action failure, in Spanish, for a dismissible banner. Null = none.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    // NON-ROUTING flows: stay on WhileSubscribed(5_000) — the listener
    // pauses when no UI is observing, and stale display on wake is fine
    // (these only show after the user has navigated to a page that uses
    // them, by which point Firebase has reconnected anyway).
    val metrics: StateFlow<Metrics> = repo.metrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Metrics())

    val activity: StateFlow<String?> = repo.activity
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val task: StateFlow<String?> = repo.task
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Eagerly: overlay routing checks it for TUI junk flagged by ResultPage.
    val response: StateFlow<String?> = repo.response
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val claudeStatus: StateFlow<ClaudeStatus?> = repo.claudeStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val taskKind: StateFlow<TaskKind?> = repo.taskKind
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val headline: StateFlow<String?> = repo.headline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val toolEvents: StateFlow<List<ToolEvent>> = repo.toolEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val followups: StateFlow<List<String>> = repo.followups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentSessions: StateFlow<List<RecentSession>> = repo.recentSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Sprint 4n — tap-to-claim. Daemon writes /claimResult after each
    // claim attempt; UI drives the success / error banner from this.
    val claimResult: StateFlow<ClaimResult?> = repo.claimResult
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Pending confirmation dialog state. Pair<sessionId, cwd> when set;
    // null when no dialog is showing. Driven entirely from the watch side
    // (UI tap → set; user confirms or cancels → cleared).
    private val _confirmingClaim = MutableStateFlow<Pair<String, String>?>(null)
    val confirmingClaim: StateFlow<Pair<String, String>?> =
        _confirmingClaim.asStateFlow()

    // ─── Home shell (Inicio + overlay routing) ───────────────────────────────

    /** Mac-only problem (trust/login/crash/timeout). Eager: drives routing. */
    val blocker: StateFlow<Blocker?> = repo.blocker
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Real exit status of the last run. Eager: drives completion. */
    val outcome: StateFlow<RunOutcome?> = repo.outcome
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * RTDB /conversationActive: "Seguir" vs "Preguntar". Replaces the old
     * in-memory sentInSession guess, which reset on process death while the
     * daemon kept continuing the thread.
     */
    val conversationActive: StateFlow<Boolean> = repo.conversationActive
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _send = MutableStateFlow<SendState>(SendState.None)

    /** Spoken text between "sent" and "the Mac picked it up" (or failed to). */
    val sendState: StateFlow<SendState> = _send.asStateFlow()
    private var sendTimeoutJob: Job? = null

    private val _lastRun = MutableStateFlow<LastRun?>(null)

    /** How the last run ended; a failure keeps a one-line reason on Inicio. */
    val lastRun: StateFlow<LastRun?> = _lastRun.asStateFlow()

    private val _runStartedAt = MutableStateFlow<Long?>(null)

    /** Wall time the current run was first seen working; null when idle. */
    val runStartedAt: StateFlow<Long?> = _runStartedAt.asStateFlow()
    private var stopRequested = false

    // One-shot moments (haptics, auto-slide). Lives in the VM so it survives
    // any recomposition; the pager shell collects it.
    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    // Local-only dismissals (never written to RTDB), keyed on blocker ts or
    // on an episode key that is dropped when the episode ends.
    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    private val _dismissedBlockers = MutableStateFlow(dismissalStore.load())
    private val _blockedContent = MutableStateFlow<String?>(null)
    private val _noDictation = MutableStateFlow(false)

    // Debounced conditions, so a cold-start default or a socket blip never
    // flashes a full-screen state.
    private val macOfflineStable: Flow<Boolean> =
        status.map { it == WrapperStatus.OFFLINE }.holdTrue(MAC_OFFLINE_DEBOUNCE_MS)

    private val awaitingWithoutPromptStable: Flow<Boolean> =
        combine(status, permissionPrompt) { s, p ->
            s == WrapperStatus.AWAITING_PERMISSION && p.isNullOrBlank()
        }.holdTrue(AWAITING_DEBOUNCE_MS)

    private val watchOfflineStable: Flow<Boolean> =
        connected.map { !it }.holdTrue(WATCH_OFFLINE_DEBOUNCE_MS)

    /** What sits above the pager. See [routeOverlay] for the priority. */
    val overlay: StateFlow<Overlay> = combine(
        combine(status, permissionPrompt, blocker) { s, p, b -> Triple(s, p, b) },
        combine(macOfflineStable, awaitingWithoutPromptStable) { m, a -> m to a },
        combine(_blockedContent, response, _noDictation) { key, r, noDict ->
            key?.takeIf { r != null && it == blockedContentKey(r) } to noDict
        },
        combine(_dismissed, _dismissedBlockers, ticks) { d, db, _ -> Triple(d, db.toSet(), clock()) },
    ) { (s, p, b), (macOff, awaiting), (junk, noDictation), (dismissed, dismissedBlockers, now) ->
        routeOverlay(
            RoutingInput(
                status = s,
                permissionPrompt = p,
                blocker = b,
                macOfflineStable = macOff,
                awaitingWithoutPromptStable = awaiting,
                blockedContentKey = junk,
                dismissed = dismissed,
                dismissedBlockers = dismissedBlockers,
                nowMs = now,
                noDictation = noDictation,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Overlay.None)

    // One error haptic per blocked run; see ErrorBuzzDedupe.
    private val errorBuzz = ErrorBuzzDedupe()

    /** Non-blocking "reloj sin conexión" banner (after 5s offline, until dismissed). */
    val watchOfflineBanner: StateFlow<Boolean> =
        combine(watchOfflineStable, _dismissed) { off, d -> off && WATCH_OFFLINE_KEY !in d }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Pickup, run clock and completion share one collector so they see
        // every (status, outcome) pair in the same order.
        viewModelScope.launch {
            val detector = OutcomeCompletionDetector()
            combine(status, outcome) { s, o -> s to o }.collect { (s, o) ->
                val working = s == WrapperStatus.RUNNING || s == WrapperStatus.AWAITING_PERMISSION
                val pending = _send.value
                if (pending is SendState.Sending && (working || (o != null && o != pending.outcomeAtSend))) {
                    detector.arm(pending.outcomeAtSend)
                    sendTimeoutJob?.cancel()
                    _send.value = SendState.None
                    _events.tryEmit(HomeEvent.PickedUp)
                }
                if (working) {
                    if (_runStartedAt.value == null) {
                        _runStartedAt.value = clock()
                        stopRequested = false
                    }
                } else {
                    _runStartedAt.value = null
                }
                detector.onUpdate(s, o)?.let { done ->
                    val stopped = !done.ok && (stopRequested || done.stopped)
                    _lastRun.value = LastRun(done.ok, done.exitCode, stoppedByUser = stopped)
                    stopRequested = false
                    // buzzError only matters for a real failure (default true on success).
                    val buzz = done.ok || (
                        !stopped &&
                            errorBuzz.onFailedOutcome(done.ts, blockerPresent = blocker.value != null, nowMs = clock())
                        )
                    _events.tryEmit(HomeEvent.Finished(done.ok, stopped = stopped, buzzError = buzz))
                }
            }
        }
        // A new Mac-side blocker on screen: its single error haptic.
        viewModelScope.launch {
            overlay.collect { o ->
                val ts = (o as? Overlay.Blocked)?.blockerTs ?: return@collect
                if (errorBuzz.onBlocker(ts, clock())) _events.tryEmit(HomeEvent.Blocked)
            }
        }
        // Episode ends → forget its dismissal, so the next episode shows again.
        viewModelScope.launch {
            combine(status, connected) { s, c -> s to c }.collect { (s, c) ->
                val ended = buildSet {
                    if (s != WrapperStatus.OFFLINE) add(MAC_OFFLINE_KEY)
                    if (s != WrapperStatus.AWAITING_PERMISSION) add(AWAITING_KEY)
                    if (c) add(WATCH_OFFLINE_KEY)
                }
                if (_dismissed.value.any { it in ended }) _dismissed.value = _dismissed.value - ended
            }
        }
    }

    // Claude Code TUI permission prompts use numbered selection ("1. Yes",
    // "2. Yes, ...", "3. No"). Typing the digit + Enter is the most reliable
    // way to confirm — independent of which option happens to be highlighted.
    fun allow() = answer(CommandText.ALLOW)

    // ESC drops out of the prompt to the "No, and tell Claude what to do
    // differently" branch.
    fun deny() = answer(CommandText.DENY)

    // Answers the CURRENT prompt id at most once. Refused while offline (an
    // offline write is queued and replayed later, possibly onto a different
    // prompt) and when there is no id (the wrapper would drop it anyway).
    // The claim happens synchronously on the main thread, so a double tap
    // can't produce two writes.
    private fun answer(text: String) {
        val id = permissionPromptId.value
        if (id == null) {
            _lastError.value = ErrorCopy.PROMPT_GONE
            return
        }
        if (!connected.value) {
            _lastError.value = ErrorCopy.OFFLINE
            return
        }
        if (!answers.tryClaim(id)) return
        viewModelScope.launch {
            try {
                repo.sendCommand(text, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "answer failed: ${e.message}")
                answers.release(id)
                _lastError.value = ErrorCopy.ANSWER_FAILED
            }
        }
    }

    // Cancel/stop a running task. Wrapper's watchCommands handler treats
    // ETX (^C / SIGINT) specially: it calls runner.kill() instead of
    // forwarding to the pty so Claude exits cleanly. Audit log captures it.
    // No promptId: stop isn't an answer to a prompt.
    fun stop() {
        // No silent queueing: an offline ^C would replay onto a later run.
        if (!connected.value) {
            _lastError.value = ErrorCopy.OFFLINE
            return
        }
        stopRequested = true
        launchAction(ErrorCopy.STOP_FAILED) { repo.sendCommand(CommandText.STOP, null) }
    }

    // Long-press of the stop button, or "Reiniciar estado" after 3 min with
    // no progress: force-reset stale UI state directly from the watch when
    // the wrapper appears dead. SIGINT via /command goes nowhere if no
    // wrapper is listening, leaving status=RUNNING forever. This writes IDLE
    // + null directly to RTDB so the watch gets out of phantom state.
    fun forceReset() {
        cancelSend()
        if (!connected.value) {
            _lastError.value = ErrorCopy.OFFLINE
            return
        }
        launchAction(ErrorCopy.RESET_FAILED) { repo.forceResetUi() }
    }

    // Voice input from the watch; the daemon runs `claude -p <text>`.
    // Inicio's "Preguntar" (and the tile) always start a NEW conversation:
    // most of the time you want a fresh question, not the last thread.
    fun sendPrompt(text: String) = send(text.trim(), PromptMode.NEW)

    // Resultado's "Seguir esta conversación" and the follow-up chips continue
    // the current thread (/prompt.mode = "continue" → `--continue`).
    fun continueConversation(text: String) = send(text.trim(), PromptMode.CONTINUE)

    /** Re-sends the exact text, in the same mode, that was not picked up. */
    fun retrySend() {
        val failed = _send.value as? SendState.Failed ?: return
        _send.value = SendState.None
        send(failed.text, failed.mode)
    }

    /**
     * Local only: stops waiting. If the Mac picks the prompt up later anyway,
     * Inicio shows the run with Detener, which is the honest state.
     */
    fun cancelSend() {
        sendTimeoutJob?.cancel()
        _send.value = SendState.None
    }

    /** "Ver detalle" acknowledges the failure line. */
    fun clearLastRun() {
        _lastRun.value = null
    }

    /**
     * Local dismissal of a BlockedScreen / banner. Never written to RTDB. A
     * Mac-side blocker's ts is also persisted, so it never shows again.
     */
    fun dismissOverlay(key: String) {
        if (key == NO_DICTATION_KEY) {
            _noDictation.value = false
            return
        }
        _dismissed.value = _dismissed.value + key
        val b = blocker.value
        if (b != null && key == blockerDismissKey(b)) {
            val next = addDismissal(_dismissedBlockers.value, b.ts)
            _dismissedBlockers.value = next
            dismissalStore.save(next)
        }
    }

    /** Preguntar found no speech recognizer: show BlockedScreen(NO_DICTATION). */
    fun reportNoDictation() {
        _noDictation.value = true
    }

    /** ResultPage saw TUI junk in the current response. Idempotent. */
    fun reportBlockedContent() {
        val r = response.value ?: return
        _blockedContent.value = blockedContentKey(r)
    }

    private fun send(display: String, mode: PromptMode) {
        if (display.isEmpty()) return
        // No silent queueing: an offline write would replay minutes later.
        if (!connected.value) {
            _lastError.value = ErrorCopy.OFFLINE
            return
        }
        if (_send.value is SendState.Sending) return
        _lastRun.value = null
        val pending = SendState.Sending(display, mode, outcome.value)
        _send.value = pending
        _events.tryEmit(HomeEvent.Sent)
        sendTimeoutJob?.cancel()
        sendTimeoutJob = viewModelScope.launch {
            delay(PICKUP_TIMEOUT_MS)
            failSend(pending)
        }
        viewModelScope.launch {
            try {
                repo.sendPrompt(display, mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prompt write failed: ${e.message}")
                failSend(pending)
            }
        }
    }

    private fun failSend(pending: SendState.Sending) {
        if (_send.value !== pending) return
        sendTimeoutJob?.cancel()
        _send.value = SendState.Failed(pending.text, pending.mode)
        _events.tryEmit(HomeEvent.SendFailed)
    }

    // Sprint 4n — tap-to-claim actions.
    //
    // requestClaimConfirmation() is called when SessionRow is tapped on
    // Page 5; it raises the confirmation dialog without yet writing to
    // RTDB. confirmClaim() / cancelClaim() resolve that dialog.
    fun requestClaimConfirmation(sessionId: String, cwd: String) {
        _confirmingClaim.value = sessionId to cwd
    }

    fun cancelClaim() {
        _confirmingClaim.value = null
    }

    fun confirmClaim() {
        val pending = _confirmingClaim.value ?: return
        _confirmingClaim.value = null
        launchAction(ErrorCopy.CLAIM_FAILED) { repo.claimSession(pending.first, pending.second) }
    }

    // Called by ClaimResultBanner after the auto-dismiss timer fires (or
    // user taps the close X). Nulls /claimResult so a stale entry doesn't
    // re-show on next listener reconnect. Failure is silent: worst case the
    // stale banner is filtered by its 10s freshness check anyway.
    fun dismissClaimResult() = launchAction(null) { repo.clearClaimResult() }

    private fun launchAction(errorCopy: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "action failed: ${e.message}")
                if (errorCopy != null) _lastError.value = errorCopy
            }
        }
    }

    internal companion object {
        private const val TAG = "ccwearos-vm"

        /** No pickup after this → "Tu Mac no tomó la pregunta". */
        const val PICKUP_TIMEOUT_MS = 15_000L
        const val WATCH_OFFLINE_DEBOUNCE_MS = 5_000L
        const val MAC_OFFLINE_DEBOUNCE_MS = 1_500L
        const val AWAITING_DEBOUNCE_MS = 2_000L
    }
}
