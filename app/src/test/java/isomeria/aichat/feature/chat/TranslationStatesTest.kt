package isomeria.aichat.feature.chat

import isomeria.aichat.data.repo.FailReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.9.0 翻译状态：同一条正在翻译时不重复请求；成功后状态去掉（译文在消息上）；失败记下原因，再点就是重试 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationStatesTest {
    private val scope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun sameMessageNotRequestedTwiceWhileLoading_successClears() {
        val calls = mutableListOf<Long>()
        val gate = CompletableDeferred<FailReason?>()
        val states = TranslationStates(scope) { id -> calls += id; gate.await() }
        states.start(1)
        states.start(1)
        assertEquals(listOf(1L), calls)
        assertEquals(TranslationUi.Loading, states.state.value[1])
        gate.complete(null)
        scope.runCurrent()
        assertEquals(emptyMap<Long, TranslationUi>(), states.state.value)
    }

    @Test
    fun failure_keepsReason_retryRequestsAgain() {
        var result: FailReason? = FailReason.NETWORK
        var count = 0
        val states = TranslationStates(scope) { count++; result }
        states.start(7)
        assertEquals(TranslationUi.Failed(FailReason.NETWORK), states.state.value[7])
        result = null
        states.start(7)
        assertEquals(2, count)
        assertEquals(null, states.state.value[7])
    }

    @Test
    fun unexpectedError_showsUnknown_clearRemovesIt() {
        val states = TranslationStates(scope) { error("boom") }
        states.start(3)
        assertEquals(TranslationUi.Failed(FailReason.UNKNOWN), states.state.value[3])
        states.clear(3)
        assertEquals(null, states.state.value[3])
    }
}
