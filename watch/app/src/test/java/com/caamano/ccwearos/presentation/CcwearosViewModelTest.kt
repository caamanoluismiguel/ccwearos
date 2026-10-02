package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.CommandText
import com.caamano.ccwearos.data.SharedSessionMeta
import com.caamano.ccwearos.data.WrapperStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class CcwearosViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: FakeWatchRepository
    private lateinit var vm: CcwearosViewModel
    private var now = 10_000_000_000L
    private val ticks = MutableSharedFlow<Unit>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeWatchRepository()
        vm = CcwearosViewModel(repo, AnswerGate(), clock = { now }, staleTicks = ticks)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun showPrompt(id: String) {
        repo.status.value = WrapperStatus.AWAITING_PERMISSION
        repo.permissionPrompt.value = "Bash: rm -rf build"
        repo.permissionPromptId.value = id
    }

    // (a) one command per promptId, even on double tap
    @Test
    fun `double tap allow sends exactly one command`() {
        showPrompt("p1")
        vm.allow()
        vm.allow()
        vm.deny()
        assertEquals(listOf(CommandText.ALLOW to "p1"), repo.commands)
        assertTrue(vm.answered.value)
    }

    @Test
    fun `a new prompt id can be answered again`() {
        showPrompt("p1")
        vm.allow()
        showPrompt("p2")
        assertFalse(vm.answered.value)
        vm.deny()
        assertEquals(listOf(CommandText.ALLOW to "p1", CommandText.DENY to "p2"), repo.commands)
    }

    @Test
    fun `failed write releases the claim so the user can retry`() {
        showPrompt("p1")
        repo.failNextCommand = true
        vm.allow()
        assertEquals(ErrorCopy.ANSWER_FAILED, vm.lastError.value)
        assertFalse(vm.answered.value)
        vm.allow()
        assertEquals(listOf(CommandText.ALLOW to "p1"), repo.commands)
        vm.clearError()
        assertNull(vm.lastError.value)
    }

    // (b) refused when disconnected or promptId null
    @Test
    fun `allow refused while disconnected`() {
        showPrompt("p1")
        repo.connected.value = false
        vm.allow()
        assertTrue(repo.commands.isEmpty())
        assertEquals(ErrorCopy.OFFLINE, vm.lastError.value)
        assertFalse(vm.answered.value)

        repo.connected.value = true
        vm.allow()
        assertEquals(listOf(CommandText.ALLOW to "p1"), repo.commands)
    }

    @Test
    fun `allow refused when prompt id is null`() {
        showPrompt("p1")
        repo.permissionPromptId.value = null
        vm.allow()
        vm.deny()
        assertTrue(repo.commands.isEmpty())
        assertEquals(ErrorCopy.PROMPT_GONE, vm.lastError.value)
    }

    @Test
    fun `stop is sent without a prompt id`() {
        repo.status.value = WrapperStatus.RUNNING
        vm.stop()
        assertEquals(listOf(CommandText.STOP to null), repo.commands)
    }

    // Stale hook lock: hidden so the dashboard shows the ask button again.
    @Test
    fun `fresh hook session is exposed`() {
        val meta = SharedSessionMeta(kind = "hook", startedAt = now - 60_000, heartbeatAt = now - 5_000)
        repo.sharedSession.value = meta
        assertEquals(meta, vm.sharedSession.value)
    }

    @Test
    fun `hook session with an old heartbeat is hidden`() {
        repo.sharedSession.value =
            SharedSessionMeta(kind = "hook", startedAt = now - 3_600_000, heartbeatAt = now - 31 * 60_000)
        assertNull(vm.sharedSession.value)
    }

    @Test
    fun `hook session goes stale on a tick without an RTDB change`() = runTest(dispatcher) {
        val meta = SharedSessionMeta(kind = "hook", startedAt = now - 60_000, heartbeatAt = now - 60_000)
        repo.sharedSession.value = meta
        assertEquals(meta, vm.sharedSession.value)
        now += 30 * 60_000L
        ticks.emit(Unit)
        assertNull(vm.sharedSession.value)
        // A new heartbeat brings it back.
        val beat = meta.copy(heartbeatAt = now)
        repo.sharedSession.value = beat
        assertEquals(beat, vm.sharedSession.value)
    }

    @Test
    fun `wrapper-pty session is never hidden by age`() {
        val meta = SharedSessionMeta(kind = "wrapper-pty", startedAt = now - 24 * 3_600_000L)
        repo.sharedSession.value = meta
        assertEquals(meta, vm.sharedSession.value)
    }
}
