package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.BlockerKind
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.home.BLOCKER_MAX_AGE_MS
import com.caamano.ccwearos.presentation.home.ErrorBuzzDedupe
import com.caamano.ccwearos.presentation.home.HomeEvent
import com.caamano.ccwearos.presentation.home.HomeMode
import com.caamano.ccwearos.presentation.home.InMemoryBlockerDismissalStore
import com.caamano.ccwearos.presentation.home.LastRun
import com.caamano.ccwearos.presentation.home.MAX_DISMISSED_BLOCKERS
import com.caamano.ccwearos.presentation.home.NO_DICTATION_KEY
import com.caamano.ccwearos.presentation.home.Overlay
import com.caamano.ccwearos.presentation.home.addDismissal
import com.caamano.ccwearos.presentation.home.blockerVisible
import com.caamano.ccwearos.presentation.home.parseDismissed
import com.caamano.ccwearos.presentation.home.underlayHidden
import com.caamano.ccwearos.presentation.home.underlayProgress
import com.caamano.ccwearos.presentation.ui.MascotState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockerFeedbackTest {

    // ─── blocker visibility (pure) ───────────────────────────────────────────

    @Test
    fun `a dismissed blocker ts never shows again`() {
        val b = Blocker(BlockerKind.TRUST, ts = 1_000)
        assertTrue(blockerVisible(b, emptySet(), nowMs = 2_000))
        assertFalse(blockerVisible(b, setOf(1_000L), nowMs = 2_000))
        assertTrue(blockerVisible(b.copy(ts = 1_001), setOf(1_000L), nowMs = 2_000))
    }

    @Test
    fun `a blocker older than 6h is never shown, a future ts counts as fresh`() {
        val now = 100 * BLOCKER_MAX_AGE_MS
        assertTrue(blockerVisible(Blocker(BlockerKind.CRASH, ts = now - BLOCKER_MAX_AGE_MS), emptySet(), now))
        assertFalse(blockerVisible(Blocker(BlockerKind.CRASH, ts = now - BLOCKER_MAX_AGE_MS - 1), emptySet(), now))
        assertTrue(blockerVisible(Blocker(BlockerKind.CRASH, ts = now + 60_000), emptySet(), now))
    }

    @Test
    fun `dismissal list keeps the newest ten without duplicates`() {
        var list = emptyList<Long>()
        for (ts in 1L..15L) list = addDismissal(list, ts)
        assertEquals(MAX_DISMISSED_BLOCKERS, list.size)
        assertEquals((6L..15L).toList(), list)
        assertEquals((7L..15L).toList() + 6L, addDismissal(list, 6L))
        assertEquals(listOf(1L, 22L), parseDismissed("1, x,22,"))
        assertEquals(emptyList<Long>(), parseDismissed(null))
    }

    // ─── error haptic dedupe (pure) ──────────────────────────────────────────

    @Test
    fun `blocker then failed outcome buzzes once`() {
        val d = ErrorBuzzDedupe()
        assertTrue(d.onBlocker(ts = 10, nowMs = 0))
        assertFalse(d.onBlocker(ts = 10, nowMs = 100))
        assertFalse(d.onFailedOutcome(ts = 11, blockerPresent = true, nowMs = 200))
    }

    @Test
    fun `failed outcome then late blocker buzzes once`() {
        val d = ErrorBuzzDedupe()
        assertTrue(d.onFailedOutcome(ts = 11, blockerPresent = false, nowMs = 0))
        assertFalse(d.onBlocker(ts = 10, nowMs = 1_000))
    }

    @Test
    fun `each new failure buzzes, a repeat of the same outcome does not`() {
        val d = ErrorBuzzDedupe()
        assertTrue(d.onFailedOutcome(ts = 1, blockerPresent = false, nowMs = 0))
        assertFalse(d.onFailedOutcome(ts = 1, blockerPresent = false, nowMs = 60_000))
        assertTrue(d.onFailedOutcome(ts = 2, blockerPresent = false, nowMs = 60_000))
        assertTrue(d.onBlocker(ts = 3, nowMs = 120_000))
    }

    // ─── underlay below an overlay (pure) ────────────────────────────────────

    @Test
    fun `underlay blur stops once the overlay is fully in`() {
        assertEquals(0f, underlayProgress(0f), 0f)
        assertEquals(0.3f, underlayProgress(0.5f), 1e-6f)
        assertFalse(underlayHidden(0.99f))
        assertTrue(underlayHidden(1f))
        assertEquals("no RenderEffect at full cover", 0f, underlayProgress(1f), 0f)
    }

    // ─── stopped runs look calm ──────────────────────────────────────────────

    @Test
    fun `a stopped run settles the mascot on Idle, a real failure on Error`() {
        fun idle(f: LastRun) = HomeMode.Idle(offline = null, conversationActive = false, failure = f)
        assertEquals(MascotState.Idle, mascotFor(idle(LastRun(false, 130, stoppedByUser = true)), WrapperStatus.IDLE, null))
        assertEquals(MascotState.Error, mascotFor(idle(LastRun(false, 1, stoppedByUser = false)), WrapperStatus.IDLE, null))
    }

    // ─── through the VM ──────────────────────────────────────────────────────

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: FakeWatchRepository
    private lateinit var store: InMemoryBlockerDismissalStore
    private var now = 1_000_000L

    private fun newVm() = CcwearosViewModel(
        repo,
        AnswerGate(),
        clock = { now },
        staleTicks = MutableSharedFlow(),
        dismissalStore = store,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeWatchRepository()
        store = InMemoryBlockerDismissalStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.recordEvents(vm: CcwearosViewModel): MutableList<HomeEvent> {
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        return events
    }

    @Test
    fun `dismissed blocker stays hidden after the app is reopened`() {
        val b = Blocker(BlockerKind.CRASH, ts = 900_000)
        repo.blocker.value = b
        val first = newVm()
        val o = first.overlay.value as Overlay.Blocked
        first.dismissOverlay(o.dismissKey)
        assertEquals(listOf(900_000L), store.load())

        val reopened = newVm()
        assertEquals(Overlay.None, reopened.overlay.value)
    }

    @Test
    fun `a blocked run buzzes once, from the blocker`() = runTest(dispatcher) {
        val vm = newVm()
        val events = recordEvents(vm)
        repo.status.value = WrapperStatus.RUNNING
        repo.blocker.value = Blocker(BlockerKind.TRUST, ts = 999_000)
        repo.outcome.value = RunOutcome(ok = false, exitCode = 1, ts = 999_100)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(listOf(HomeEvent.Blocked, HomeEvent.Finished(ok = false, buzzError = false)), events)
    }

    @Test
    fun `wrapper stopped flag reads as stopped, never as an error`() = runTest(dispatcher) {
        val vm = newVm()
        val events = recordEvents(vm)
        repo.status.value = WrapperStatus.RUNNING
        repo.outcome.value = RunOutcome(ok = false, exitCode = 130, ts = 5, stopped = true)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(listOf<HomeEvent>(HomeEvent.Finished(ok = false, stopped = true, buzzError = false)), events)
        assertTrue(vm.lastRun.value!!.stoppedByUser)
    }

    @Test
    fun `no dictation shows its screen until dismissed, and can show again`() {
        val vm = newVm()
        vm.reportNoDictation()
        val o = vm.overlay.value as Overlay.Blocked
        assertEquals(BlockedVariant.NO_DICTATION, o.variant)
        vm.dismissOverlay(NO_DICTATION_KEY)
        assertEquals(Overlay.None, vm.overlay.value)
        vm.reportNoDictation()
        assertTrue(vm.overlay.value is Overlay.Blocked)
    }

    @Test
    fun `stop and reset are refused offline instead of queued`() {
        val vm = newVm()
        repo.connected.value = false
        repo.status.value = WrapperStatus.RUNNING
        vm.stop()
        vm.forceReset()
        assertTrue(repo.commands.isEmpty())
        assertEquals(0, repo.resets)
        assertEquals(ErrorCopy.OFFLINE, vm.lastError.value)
    }
}
