package com.ceecept.music.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Analysis used by DJ Mode for Apple/Spotify-style automix decisions. */
data class DjTrackAnalysis(
    val trackId: Long,
    val bpm: Float,
    val key: Int,
    val minor: Boolean,
    val energy: Float,
    val confidence: Float,
    val beatMs: Long = (60_000f / bpm.coerceIn(70f, 190f)).toLong(),
    val introCueMs: Long = 0L,
    val outroCueMs: Long = 0L
) {
    val camelot: String
        get() {
            val num = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)[key.coerceIn(0, 11)]
            return "$num${if (minor) "A" else "B"}"
        }
}

/**
 * Local-file audio analyser for DJ Mode.
 *
 * It is intentionally dependency-free and CPU bounded for a Huawei P40 Lite:
 * decode ~40 seconds, downsample to 4 kHz, compute an energy/onset envelope, run
 * autocorrelation for 70–190 BPM, and run a lightweight chroma/Krumhansl key estimate.
 */
class DjAnalyzer(private val context: Context) {

    fun analyze(track: Track): DjTrackAnalysis {
        val samples = decodeMono(track)
        if (samples.size < 4000) return fallback(track)
        val bpmResult = estimateBpm(samples, 4000)
        val keyResult = estimateKey(samples, 4000)
        val energy = rms(samples).coerceIn(0f, 1f)
        val beatMs = (60_000f / bpmResult.first.coerceIn(70f, 190f)).toLong()
        val transitionMs = (beatMs * 32L).coerceIn(7_000L, 18_000L)
        return DjTrackAnalysis(
            trackId = track.id,
            bpm = bpmResult.first,
            key = keyResult.first,
            minor = keyResult.second,
            energy = energy,
            confidence = ((bpmResult.second + keyResult.third) * 0.5f).coerceIn(0f, 1f),
            beatMs = beatMs,
            introCueMs = 0L,
            outroCueMs = (track.durationMs - transitionMs).coerceAtLeast(0L)
        )
    }

    private fun fallback(track: Track): DjTrackAnalysis {
        val h = abs((track.title + track.artist).hashCode())
        val bpm = 96f + (h % 50)
        val beatMs = (60_000f / bpm).toLong()
        val transitionMs = (beatMs * 32L).coerceIn(7_000L, 18_000L)
        return DjTrackAnalysis(
            trackId = track.id,
            bpm = bpm,
            key = h % 12,
            minor = (h / 12) % 2 == 0,
            energy = 0.45f,
            confidence = 0.05f,
            beatMs = beatMs,
            introCueMs = 0L,
            outroCueMs = (track.durationMs - transitionMs).coerceAtLeast(0L)
        )
    }

    private fun decodeMono(track: Track): FloatArray {
        val out = FloatArrayList(180_000)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, track.uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return FloatArray(0)
            extractor.selectTrack(audioTrack)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else track.durationMs * 1000L
            val startUs = (durationUs * 0.18).toLong().coerceAtMost(max(0L, durationUs - 45_000_000L))
            val endUs = min(durationUs, startUs + 42_000_000L)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var outputFormat = codec.outputFormat
            var sr = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var decim = 0
            var step = max(1, sr / 4000)
            var sawInputEnd = false
            var sawOutputEnd = false
            val info = MediaCodec.BufferInfo()
            while (!sawOutputEnd && out.size < 180_000) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts > endUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        sr = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        step = max(1, sr / 4000)
                        decim = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            while (buffer.remaining() >= ch * 2) {
                                var sum = 0f
                                for (c in 0 until ch) sum += buffer.short / 32768f
                                val mono = sum / ch
                                decim++
                                if (decim >= step) {
                                    out.add(mono)
                                    decim = 0
                                }
                            }
                        }
                        sawOutputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } catch (e: Exception) {
            return FloatArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
        return out.toArray()
    }

    private fun estimateBpm(x: FloatArray, sr: Int): Pair<Float, Float> {
        val frame = 200 // 50 ms at 4 kHz
        val n = x.size / frame
        if (n < 80) return 120f to 0f
        val env = FloatArray(n)
        for (i in 0 until n) {
            var e = 0f
            val base = i * frame
            for (j in 0 until frame) {
                val v = x[base + j]
                e += v * v
            }
            env[i] = sqrt(e / frame)
        }
        val onset = FloatArray(n)
        var mean = 0f
        for (i in 1 until n) {
            val v = (env[i] - env[i - 1]).coerceAtLeast(0f)
            onset[i] = v
            mean += v
        }
        mean /= n
        for (i in onset.indices) onset[i] = (onset[i] - mean).coerceAtLeast(0f)
        val envRate = sr.toFloat() / frame
        val minLag = (envRate * 60f / 190f).toInt().coerceAtLeast(2)
        val maxLag = (envRate * 60f / 70f).toInt().coerceAtMost(n - 2)
        var bestLag = minLag
        var best = 0f
        var total = 1e-6f
        for (lag in minLag..maxLag) {
            var c = 0f
            var i = lag
            while (i < n) {
                c += onset[i] * onset[i - lag]
                i++
            }
            // Prefer the dance/pop range very slightly to avoid half-time locks.
            val bpm = envRate * 60f / lag
            val prior = 1f - (abs(bpm - 122f) / 160f).coerceIn(0f, 0.35f)
            c *= prior
            total += c
            if (c > best) { best = c; bestLag = lag }
        }
        var bpm = envRate * 60f / bestLag
        while (bpm < 80f) bpm *= 2f
        while (bpm > 170f) bpm *= 0.5f
        val confidence = (best / (total / (maxLag - minLag + 1))).let { it / (it + 6f) }.coerceIn(0f, 1f)
        return bpm to confidence
    }

    private fun estimateKey(x: FloatArray, sr: Int): Triple<Int, Boolean, Float> {
        val chroma = FloatArray(12)
        val maxSamples = min(x.size, 65536)
        val noteFreqs = floatArrayOf(
            65.41f, 69.30f, 73.42f, 77.78f, 82.41f, 87.31f,
            92.50f, 98.00f, 103.83f, 110.00f, 116.54f, 123.47f
        )
        for (pc in 0 until 12) {
            var f = noteFreqs[pc]
            while (f < 1800f) {
                chroma[pc] += goertzel(x, maxSamples, sr, f)
                f *= 2f
            }
        }
        val major = floatArrayOf(6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f)
        val minor = floatArrayOf(6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f)
        var bestKey = 0
        var bestMinor = false
        var best = -1e9f
        var second = -1e9f
        for (k in 0 until 12) {
            val maj = corr(chroma, major, k)
            val minr = corr(chroma, minor, k)
            if (maj > best) { second = best; best = maj; bestKey = k; bestMinor = false } else if (maj > second) second = maj
            if (minr > best) { second = best; best = minr; bestKey = k; bestMinor = true } else if (minr > second) second = minr
        }
        return Triple(bestKey, bestMinor, ((best - second) * 0.7f + 0.35f).coerceIn(0f, 1f))
    }

    private fun goertzel(x: FloatArray, n: Int, sr: Int, freq: Float): Float {
        val w = 2.0 * PI * freq / sr
        val coeff = (2.0 * cos(w)).toFloat()
        var s0 = 0f; var s1 = 0f; var s2 = 0f
        var i = 0
        while (i < n) {
            s0 = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s0
            i += 2 // skip every other sample: faster and enough for global key
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    private fun corr(chroma: FloatArray, template: FloatArray, key: Int): Float {
        var cm = 0f; var tm = 0f
        for (i in 0 until 12) { cm += chroma[(i + key) % 12]; tm += template[i] }
        cm /= 12f; tm /= 12f
        var num = 0f; var cd = 0f; var td = 0f
        for (i in 0 until 12) {
            val c = chroma[(i + key) % 12] - cm
            val t = template[i] - tm
            num += c * t
            cd += c * c
            td += t * t
        }
        return num / sqrt(cd * td + 1e-9f)
    }

    private fun rms(x: FloatArray): Float {
        var e = 0f
        for (v in x) e += v * v
        return sqrt(e / x.size.coerceAtLeast(1))
    }

    private class FloatArrayList(initial: Int) {
        private var data = FloatArray(initial)
        var size = 0
            private set
        fun add(v: Float) {
            if (size >= data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
