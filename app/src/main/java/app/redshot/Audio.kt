package app.redshot

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Audio {
    /** 16-bit mono PCM -> WAV -> base64 (no line breaks), ready to send to the brain. */
    fun wavBase64(pcm: ShortArray, sampleRate: Int = 16000): String {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }
}
