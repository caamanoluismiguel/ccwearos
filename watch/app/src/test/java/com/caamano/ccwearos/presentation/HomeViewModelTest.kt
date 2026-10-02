package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.Blocker
import com.caamano.ccwearos.data.BlockerKind
import com.caamano.ccwearos.data.PromptMode
import com.caamano.ccwearos.data.RunOutcome
import com.caamano.ccwearos.data.WrapperStatus
import com.caamano.ccwearos.presentation.home.HomeEvent
import com.caamano.ccwearos.presentation.home.HomeMode
import com.caamano.ccwearos.presentation.home.LastRun
import com.caamano.ccwearos.presentation.home.Overlay
import com.caamano.ccwearos.presentation.home.SendState
import com.caamano.ccwearos.presentation.home.homeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: FakeWatchRepository
    private lateinit var vm: CcwearosViewModel
    private var now = 1_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeWatchRepository()
        vm = CcwearosViewModel(repo, AnswerGate(), clock = { now }, staleTicks = MutableSharedFlow())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.recordEvents(): MutableList<HomeEvent> {
        val events = mutableListOf<HomeEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        return events
    }

    // ─── sending → running → done ────────────────────────────────────────────

    @Test
    fun `send then pickup then done via outcome`() = runTest(dispatcher) {
        val events = recordEvents()
        vm.sendPrompt("  arregla el parser ")
        assertEquals(listOf("arregla el parser"), repo.prompts)
        val sending = vm.sendState.value as SendState.Sending
        assertEquals("arregla el parser", sending.text)
        assertEquals(listOf<HomeEvent>(HomeEvent.Sent), events)

        now += 2_000
        repo.status.value = WrapperStatus.RUNNING
        assertEquals(SendState.None, vm.sendState.value)
        assertEquals(now, vm.runStartedAt.value)
        assertEquals(HomeEvent.PickedUp, events.last())

        repo.outcome.value = RunOutcome(ok = true, exitCode = 0, ts = 42)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(HomeEvent.Finished(ok = true), events.last())
        assertEquals(LastRun(ok = true, exitCode = 0, stoppedByUser = false), vm.lastRun.value)
        assertNull(vm.runStartedAt.value)

        // The pickup timeout was cancelled: no failure 20s later.
        advanceTimeBy(20_000)
        assertEquals(SendState.None, vm.sendState.value)
        assertEquals(3, events.size)
    }

    @Test
    fun `run that starts and ends between emissions still counts as pickup and done`() = runTest(dispatcher) {
        val events = recordEvents()
        repo.outcome.value = RunOutcome(ok = true, ts = 1) // old run
        vm.sendPrompt("hola")
        repo.outcome.value = RunOutcome(ok = true, ts = 2) // new run, status never seen RUNNING
        assertEquals(SendState.None, vm.sendState.value)
        assertEquals(listOf(HomeEvent.Sent, HomeEvent.PickedUp, HomeEvent.Finished(true)), events)
    }

    // ─── 15s timeout ─────────────────────────────────────────────────────────

    @Test
    fun `not picked up in 15s fails, retry re-sends the same text and mode`() = runTest(dispatcher) {
        val events = recordEvents()
        vm.continueConversation("empieza de cero")
        advanceTimeBy(CcwearosViewModel.PICKUP_TIMEOUT_MS - 1)
        assertTrue(vm.sendState.value is SendState.Sending)
        advanceTimeBy(2)
        val failed = vm.sendState.value as SendState.Failed
        assertEquals("empieza de cero", failed.text)
        assertEquals(HomeEvent.SendFailed, events.last())

        vm.retrySend()
        assertTrue(vm.sendState.value is SendState.Sending)
        assertEquals(listOf("empieza de cero", "empieza de cero"), repo.prompts)
        assertEquals(listOf(PromptMode.CONTINUE, PromptMode.CONTINUE), repo.promptModes)

        vm.cancelSend()
        assertEquals(SendState.None, vm.sendState.value)
        advanceTimeBy(CcwearosViewModel.PICKUP_TIMEOUT_MS * 2)
        assertEquals(SendState.None, vm.sendState.value)
    }

    @Test
    fun `failed write goes straight to the retry card`() = runTest(dispatcher) {
        repo.failNextPrompt = true
        vm.sendPrompt("hola")
        assertEquals(SendState.Failed("hola", PromptMode.NEW), vm.sendState.value)
    }

    @Test
    fun `double send while sending writes once`() {
        vm.sendPrompt("a")
        vm.sendPrompt("b")
        assertEquals(listOf("a"), repo.prompts)
    }

    // ─── offline gating ──────────────────────────────────────────────────────

    @Test
    fun `send refused while offline, never queued`() {
        repo.connected.value = false
        vm.sendPrompt("hola")
        assertTrue(repo.prompts.isEmpty())
        assertEquals(SendState.None, vm.sendState.value)
        assertEquals(ErrorCopy.OFFLINE, vm.lastError.value)
    }

    @Test
    fun `watch offline banner waits 5s and stays dismissed for the episode`() = runTest(dispatcher) {
        repo.connected.value = false
        advanceTimeBy(CcwearosViewModel.WATCH_OFFLINE_DEBOUNCE_MS - 1)
        assertFalse(vm.watchOfflineBanner.value)
        advanceTimeBy(2)
        assertTrue(vm.watchOfflineBanner.value)
        vm.dismissOverlay(com.caamano.ccwearos.presentation.home.WATCH_OFFLINE_KEY)
        assertFalse(vm.watchOfflineBanner.value)

        // Next episode shows again.
        repo.connected.value = true
        repo.connected.value = false
        advanceTimeBy(CcwearosViewModel.WATCH_OFFLINE_DEBOUNCE_MS + 1)
        assertTrue(vm.watchOfflineBanner.value)
    }

    // ─── prompt mode routing ─────────────────────────────────────────────────

    @Test
    fun `Preguntar always starts a new conversation, even inside a thread`() {
        repo.conversationActive.value = true
        vm.sendPrompt("  qué hora es ")
        assertEquals(listOf("qué hora es"), repo.prompts)
        assertEquals(listOf(PromptMode.NEW), repo.promptModes)
        assertEquals(PromptMode.NEW, (vm.sendState.value as SendState.Sending).mode)
    }

    @Test
    fun `Seguir esta conversacion and chips continue the thread, text sent as is`() {
        vm.continueConversation("Escribe el test")
        assertEquals(listOf("Escribe el test"), repo.prompts)
        assertEquals(listOf(PromptMode.CONTINUE), repo.promptModes)
    }

    @Test
    fun `sending state carries the mode for the meta line`() {
        vm.continueConversation("sigue")
        val mode = homeMode(
            status = vm.status.value,
            connected = true,
            send = vm.sendState.value,
            sharedSession = null,
            conversationActive = true,
            lastRun = null,
        )
        assertEquals(HomeMode.Sending("sigue", PromptMode.CONTINUE), mode)
    }

    // ─── completion via outcome ──────────────────────────────────────────────

    @Test
    fun `cold open with an old outcome does not fire`() = runTest(dispatcher) {
        val events = recordEvents()
        repo.outcome.value = RunOutcome(ok = true, ts = 7)
        repo.status.value = WrapperStatus.IDLE
        repo.response.value = "vieja"
        assertTrue(events.isEmpty())
        assertNull(vm.lastRun.value)
    }

    @Test
    fun `outcome arriving after IDLE fires once`() = runTest(dispatcher) {
        val events = recordEvents()
        repo.outcome.value = RunOutcome(ok = true, ts = 1)
        repo.status.value = WrapperStatus.RUNNING
        repo.status.value = WrapperStatus.IDLE // outcome still the old one
        assertTrue(events.isEmpty())
        repo.outcome.value = RunOutcome(ok = false, exitCode = 2, ts = 2)
        assertEquals(listOf<HomeEvent>(HomeEvent.Finished(ok = false)), events)
        assertEquals(LastRun(ok = false, exitCode = 2, stoppedByUser = false), vm.lastRun.value)
        repo.outcome.value = RunOutcome(ok = false, exitCode = 2, ts = 2)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(1, events.size)
    }

    @Test
    fun `stop marks the failure as stopped by the user`() = runTest(dispatcher) {
        repo.status.value = WrapperStatus.RUNNING
        vm.stop()
        repo.outcome.value = RunOutcome(ok = false, exitCode = 130, ts = 9)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(LastRun(ok = false, exitCode = 130, stoppedByUser = true), vm.lastRun.value)
        // A new send clears the failure line.
        vm.sendPrompt("otra")
        assertNull(vm.lastRun.value)
    }

    // ─── routing through the VM ──────────────────────────────────────────────

    @Test
    fun `blocker routes to BlockedScreen and dismisses locally per ts`() {
        val crash = Blocker(kind = BlockerKind.CRASH, hint = "claude salió", ts = 100)
        repo.blocker.value = crash
        val o = vm.overlay.value as Overlay.Blocked
        assertEquals(BlockedVariant.CLAUDE_CRASHED, o.variant)
        vm.dismissOverlay(o.dismissKey)
        assertEquals(Overlay.None, vm.overlay.value)
        assertTrue("dismissal never writes to RTDB", repo.commands.isEmpty() && repo.resets == 0)

        repo.blocker.value = Blocker(kind = BlockerKind.TRUST, ts = 200)
        assertEquals(BlockedVariant.NEEDS_MAC, (vm.overlay.value as Overlay.Blocked).variant)
    }

    @Test
    fun `mac offline is debounced so the cold-start default never flashes`() = runTest(dispatcher) {
        repo.status.value = WrapperStatus.OFFLINE
        assertEquals(Overlay.None, vm.overlay.value)
        advanceTimeBy(CcwearosViewModel.MAC_OFFLINE_DEBOUNCE_MS + 1)
        assertEquals(BlockedVariant.MAC_OFFLINE, (vm.overlay.value as Overlay.Blocked).variant)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(Overlay.None, vm.overlay.value)
    }

    @Test
    fun `permission prompt shows immediately, waiting without text only after a beat`() = runTest(dispatcher) {
        repo.status.value = WrapperStatus.AWAITING_PERMISSION
        assertEquals(Overlay.None, vm.overlay.value)
        advanceTimeBy(CcwearosViewModel.AWAITING_DEBOUNCE_MS + 1)
        assertEquals(BlockedVariant.NEEDS_MAC, (vm.overlay.value as Overlay.Blocked).variant)
        repo.permissionPrompt.value = "Bash: npm test"
        assertEquals(Overlay.Permission, vm.overlay.value)
    }

    @Test
    fun `junk content flagged by ResultPage routes to NEEDS_MAC for that response only`() {
        repo.response.value = "╭──── trust this folder? ────╮"
        vm.reportBlockedContent()
        val o = vm.overlay.value as Overlay.Blocked
        assertEquals(BlockedVariant.NEEDS_MAC, o.variant)
        repo.response.value = "una respuesta normal"
        assertEquals(Overlay.None, vm.overlay.value)
    }

    @Test
    fun `force reset clears a pending send`() {
        vm.sendPrompt("hola")
        vm.forceReset()
        assertEquals(SendState.None, vm.sendState.value)
        assertEquals(1, repo.resets)
    }
}
