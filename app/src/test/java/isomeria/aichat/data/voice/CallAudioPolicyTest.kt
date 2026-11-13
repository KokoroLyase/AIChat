package isomeria.aichat.data.voice

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通话声音的规则（0.8.0）：出声的设备、来电时挂断 */
class CallAudioPolicyTest {
    private val phone = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    @Test
    fun withoutHeadset_earpieceOrSpeaker() {
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, CallAudioPolicy.device(speakerphone = false, phone))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, CallAudioPolicy.device(speakerphone = true, phone))
    }

    @Test
    fun headsetConnected_notSpeakerphone_usesTheHeadsetNotTheEarpiece() {
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, CallAudioPolicy.device(false, phone + AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, CallAudioPolicy.device(false, phone + AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, CallAudioPolicy.device(false, phone + AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, CallAudioPolicy.device(false, phone + AudioDeviceInfo.TYPE_BLE_HEADSET))
        // 免提是用户自己选的：连着耳机也用扬声器
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, CallAudioPolicy.device(true, phone + AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    @Test
    fun nothingSuitable_leftToTheSystem() {
        assertNull(CallAudioPolicy.device(false, emptyList()))
        assertNull(CallAudioPolicy.device(true, listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
    }

    @Test
    fun phoneCallModes_hangUp_othersIgnored() {
        assertEquals(CallFocus.LOST, CallAudioPolicy.onModeChanged(AudioManager.MODE_RINGTONE))
        assertEquals(CallFocus.LOST, CallAudioPolicy.onModeChanged(AudioManager.MODE_IN_CALL))
        assertEquals(CallFocus.LOST, CallAudioPolicy.onModeChanged(AudioManager.MODE_CALL_SCREENING))
        assertEquals(CallFocus.LOST, CallAudioPolicy.onModeChanged(AudioManager.MODE_CALL_REDIRECT))
        // 本 App 自己切到通话模式、放完语音消息切回普通模式：不管
        assertNull(CallAudioPolicy.onModeChanged(AudioManager.MODE_IN_COMMUNICATION))
        assertNull(CallAudioPolicy.onModeChanged(AudioManager.MODE_NORMAL))
    }
}
