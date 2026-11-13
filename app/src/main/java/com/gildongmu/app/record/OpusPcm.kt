package com.gildongmu.app.record

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * 녹음한 한마디(Ogg Opus)를 Pleos 음성 인식(SpeechToText SDK의 sendAudio)에 넣을 16kHz·16비트·모노 PCM으로 푼다.
 * 녹음은 MediaRecorder가 Opus로 하지만, 디코더는 보통 48kHz로 내보내므로 16kHz로 줄인다.
 * 시간이 걸리므로 메인 스레드가 아닌 곳에서 부른다. 풀지 못하면 빈 배열이다.
 */
object OpusPcm {
    const val SAMPLE_RATE = 16_000
    private const val TIMEOUT_US = 10_000L
    private const val MAX_LOOPS = 20_000

    fun decode(file: File): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return ByteArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            val chunks = mutableListOf<ShortArray>()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var loops = 0
                while (loops++ < MAX_LOOPS) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (i >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            chunks += ShortArray(shorts.remaining()).also { shorts.get(it) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val all = ShortArray(chunks.sumOf { it.size })
            var at = 0
            chunks.forEach { it.copyInto(all, at); at += it.size }
            return toBytes(toMono16k(all, rate, channels))
        } finally {
            extractor.release()
        }
    }

    /**
     * 섞인(interleaved) 샘플을 모노로 합치고 16kHz로 바꾼다.
     * 16kHz의 정수배(48kHz 등)면 그만큼씩 평균을 내고(간단한 저역 통과), 아니면 선형 보간한다.
     */
    fun toMono16k(samples: ShortArray, rate: Int, channels: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        val frames = samples.size / ch
        val mono = IntArray(frames) { f -> (0 until ch).sumOf { samples[f * ch + it].toInt() } / ch }
        if (rate == SAMPLE_RATE) return ShortArray(frames) { mono[it].toShort() }
        if (rate > SAMPLE_RATE && rate % SAMPLE_RATE == 0) {
            val step = rate / SAMPLE_RATE
            return ShortArray(frames / step) { i -> ((i * step until i * step + step).sumOf { mono[it] } / step).toShort() }
        }
        val outFrames = (frames.toLong() * SAMPLE_RATE / rate).toInt()
        return ShortArray(outFrames) { i ->
            val pos = i.toDouble() * rate / SAMPLE_RATE
            val a = pos.toInt().coerceAtMost(frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            val t = pos - a
            (mono[a] + (mono[b] - mono[a]) * t).toInt().toShort()
        }
    }

    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[2 * i] = (s.toInt() and 0xFF).toByte()
            out[2 * i + 1] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        return out
    }
}
