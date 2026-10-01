package com.caamano.ccwearos.presentation

import com.caamano.ccwearos.data.AnswerGate
import com.caamano.ccwearos.data.CommandText
import com.caamano.ccwearos.data.WrapperStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeWatchRepository()
        vm = CcwearosViewModel(repo, AnswerGate())
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

    // (c) completion fires once per response across a transient IDLE gap
    @Test
    fun `task completion fires once across the transient idle gap`() = runTest(dispatcher) {
        var fired = 0
        val job = launch { vm.taskCompleted.collect { fired++ } }

        repo.status.value = WrapperStatus.RUNNING
        repo.response.value = null
        repo.status.value = WrapperStatus.IDLE // transient gap: IDLE, no response yet
        assertEquals(0, fired)
        repo.response.value = "listo"
        assertEquals(1, fired)

        // Re-emissions of the same state must not re-fire.
        repo.status.value = WrapperStatus.IDLE
        repo.response.value = "listo"
        assertEquals(1, fired)

        // Next run: wrapper clears stale state, streams while RUNNING, then IDLE.
        repo.status.value = WrapperStatus.RUNNING
        repo.response.value = null
        repo.response.value = "otra respuesta"
        assertEquals(1, fired)
        repo.status.value = WrapperStatus.IDLE
        assertEquals(2, fired)
        job.cancel()
    }
}

class CompletionDetectorTest {
    @Test
    fun `fires once per response and survives the idle gap`() {
        val d = CompletionDetector()
        assertFalse(d.onUpdate(WrapperStatus.IDLE, "vieja")) // cold start, never working
        assertFalse(d.onUpdate(WrapperStatus.RUNNING, "vieja"))
        assertFalse(d.onUpdate(WrapperStatus.IDLE, null)) // transient gap
        assertFalse(d.onUpdate(WrapperStatus.IDLE, ""))
        assertTrue(d.onUpdate(WrapperStatus.IDLE, "nueva"))
        assertFalse(d.onUpdate(WrapperStatus.IDLE, "nueva"))
    }

    @Test
    fun `permission wait counts as working`() {
        val d = CompletionDetector()
        d.onUpdate(WrapperStatus.AWAITING_PERMISSION, null)
        assertTrue(d.onUpdate(WrapperStatus.IDLE, "hecho"))
    }

    @Test
    fun `same response text after a new run does not re-fire`() {
        val d = CompletionDetector()
        d.onUpdate(WrapperStatus.RUNNING, null)
        assertTrue(d.onUpdate(WrapperStatus.IDLE, "ok"))
        d.onUpdate(WrapperStatus.RUNNING, "ok")
        assertFalse(d.onUpdate(WrapperStatus.IDLE, "ok"))
    }
}
