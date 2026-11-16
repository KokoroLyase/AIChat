package isomeria.aichat.data.voice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 测试包用预置音频代替通话麦克风（debug 的 TEST_TOOLS 为 true） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AndroidCallMicrophoneTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val input = File(context.filesDir, AndroidCallMicrophone.TEST_INPUT)
    private val microphone = AndroidCallMicrophone(context)

    @After fun tearDown() {
        microphone.stop()
        input.delete()
    }

    /** 1.0.0 真机：上一通在静音时挂断，下一通整通都是静音（通话详情“这次通话里没有人说话”） */
    @Test fun callAfterHangingUpWhileMuted_hearsTheInputAgain() {
        val pcm = ByteArray(16_000) { if (it % 2 == 1) 0x10 else 0 } // 0.5 秒、不是静音
        input.writeBytes(WavFormat.header(pcm.size.toLong()) + pcm)

        microphone.start(onFrame = { _, _ -> })
        microphone.setListening(false) // 静音
        microphone.stop() // 静音时挂断

        val heard = CountDownLatch(1)
        microphone.start(onFrame = { frame, count -> if ((0 until count).any { frame[it] != 0.toShort() }) heard.countDown() })
        assertTrue("新的一通能听到预置音频", heard.await(3, TimeUnit.SECONDS))
    }
}
