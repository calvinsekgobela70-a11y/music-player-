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

/**
 * Offline metadata used by Ceecept DJ Mode.
 *
 * It mirrors the AUTOMIX guideline phases in a compact on-device form: tempo/beat grid
 * summary, harmonic key, loudness/energy bands, structural cue approximations, and
 * safety flags for graceful fallback when a song is not reliably mixable.
 */
data class DjTrackAnalysis(
    val trackId: Long,
    val bpm: Float,
    val key: Int,
    val minor: Boolean,
    val energy: Float,
    val confidence: Float,
    val beatMs: Long = (60_000f / bpm.coerceIn(70f, 190f)).toLong(),
    val introCueMs: Long = 0L,
    val outroCueMs: Long = 0L,
    val downbeatOffsetMs: Long = 0L,
    val tempoStable: Boolean = true,
    val mixable: Boolean = true,
    val keyConfidence: Float = confidence,
    val firstOnsetMs: Long = 0L,
    val introEndMs: Long = introCueMs,
    val outroStartMs: Long = outroCueMs,
    val lastAudibleMs: Long = 0L,
    val lastVocalEndMs: Long = outroCueMs,
    val loudnessDb: Float = -18f,
    val lowEnergy: Float = energy,
    val midEnergy: Float = energy,
    val highEnergy: Float = energy
) {
    val camelot: String
        get() {
            val num = intArrayOf(8, 3, 10, 5, 12, 7, 2, 9, 4, 11, 6, 1)[key.coerceIn(0, 11)]
            return "$num${if (minor) "A" else "B"}"
        }
}

private data class TempoResult(
    val bpm: Float,
    val confidence: Float,
    val stable: Boolean,
    val beatMs: Long,
    val downbeatOffsetMs: Long
)

private data class StructureResult(
    val firstOnsetMs: Long,
    val introEndMs: Long,
    val outroStartMs: Long,
    val lastAudibleMs: Long,
    val lastVocalEndMs: Long,
    val low: Float,
    val mid: Float,
    val high: Float,
    val loudnessDb: Float,
    val energy: Float
)

/**
 * Local-file audio analyser for DJ Mode.
 *
 * Dependency-free for APK size/offline use. The phone decodes a bounded preview of the
 * file, downsamples to 8 kHz, then runs onset/autocorrelation BPM detection,
 * chroma/Krumhansl key detection, rough structure/silence/energy profiling, and
 * phrase-locked cue selection. Low-confidence or rubato tracks are marked not mixable
 * so the transition engine chooses simple/echo fallback instead of a broken beat mix.
 */
class DjAnalyzer(private val context: Context) {

    fun analyze(track: Track): DjTrackAnalysis {
        val samples = decodeMono(track, sampleRate = ANALYSIS_SR, maxSeconds = 72)
        if (samples.size < ANALYSIS_SR * 6) return fallback(track)

        val tempo = estimateTempo(samples, ANALYSIS_SR)
        val keyResult = estimateKey(samples, ANALYSIS_SR)
        val structure = analyseStructure(samples, ANALYSIS_SR, track.durationMs, tempo)
        val mixable = tempo.confidence >= 0.42f && tempo.stable && track.durationMs >= 45_000L
        val combinedConfidence = (tempo.confidence * 0.56f + keyResult.third * 0.24f +
            if (tempo.stable) 0.20f else 0.02f).coerceIn(0f, 1f)

        return DjTrackAnalysis(
            trackId = track.id,
            bpm = tempo.bpm,
            key = keyResult.first,
            minor = keyResult.second,
            energy = structure.energy,
            confidence = combinedConfidence,
            beatMs = tempo.beatMs,
            introCueMs = snapToDownbeat(structure.firstOnsetMs, tempo),
            outroCueMs = snapToDownbeat(structure.outroStartMs, tempo),
            downbeatOffsetMs = tempo.downbeatOffsetMs,
            tempoStable = tempo.stable,
            mixable = mixable,
            keyConfidence = keyResult.third,
            firstOnsetMs = structure.firstOnsetMs,
            introEndMs = snapToDownbeat(structure.introEndMs, tempo),
            outroStartMs = snapToDownbeat(structure.outroStartMs, tempo),
            lastAudibleMs = structure.lastAudibleMs,
            lastVocalEndMs = structure.lastVocalEndMs,
            loudnessDb = structure.loudnessDb,
            lowEnergy = structure.low,
            midEnergy = structure.mid,
            highEnergy = structure.high
        )
    }

    private fun fallback(track: Track): DjTrackAnalysis {
        val h = abs((track.title + track.artist + track.album).hashCode())
        val bpm = 92f + (h % 58)
        val beatMs = (60_000f / bpm).toLong()
        val phraseMs = beatMs * 32L
        val outro = (track.durationMs - phraseMs.coerceIn(7_000L, 18_000L)).coerceAtLeast(0L)
        return DjTrackAnalysis(
            trackId = track.id,
            bpm = bpm,
            key = h % 12,
            minor = (h / 12) % 2 == 0,
            energy = 0.42f,
            confidence = 0.10f,
            beatMs = beatMs,
            introCueMs = 0L,
            outroCueMs = outro,
            downbeatOffsetMs = 0L,
            tempoStable = false,
            mixable = false,
            keyConfidence = 0.10f,
            firstOnsetMs = 0L,
            introEndMs = min(track.durationMs, phraseMs),
            outroStartMs = outro,
            lastAudibleMs = track.durationMs,
            lastVocalEndMs = outro,
            loudnessDb = -18f,
            lowEnergy = 0.42f,
            midEnergy = 0.42f,
            highEnergy = 0.42f
        )
    }

    private fun decodeMono(track: Track, sampleRate: Int, maxSeconds: Int): FloatArray {
        val out = FloatArrayList(sampleRate * min(maxSeconds, 45))
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

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var outputFormat = codec.outputFormat
            var sr = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var decim = 0
            var step = max(1, sr / sampleRate)
            val maxSamples = sampleRate * maxSeconds
            var sawInputEnd = false
            var sawOutputEnd = false
            val info = MediaCodec.BufferInfo()
            while (!sawOutputEnd && out.size < maxSamples) {
                if (!sawInputEnd) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts > maxSeconds * 1_000_000L) {
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
                        step = max(1, sr / sampleRate)
                        decim = 0
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (outIndex >= 0) {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            while (buffer.remaining() >= ch * 2 && out.size < maxSamples) {
                                var sum = 0f
                                for (c in 0 until ch) sum += buffer.short / 32768f
                                decim++
                                if (decim >= step) {
                                    out.add(sum / ch)
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

    private fun estimateTempo(x: FloatArray, sr: Int): TempoResult {
        val frame = sr / 20 // 50 ms
        val n = x.size / frame
        if (n < 80) return TempoResult(120f, 0f, false, 500L, 0L)
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
        var second = 0f
        var total = 1e-6f
        for (lag in minLag..maxLag) {
            var c = 0f
            var i = lag
            while (i < n) {
                c += onset[i] * onset[i - lag]
                i++
            }
            val bpm = envRate * 60f / lag
            val prior = 1f - (abs(bpm - 122f) / 160f).coerceIn(0f, 0.35f)
            c *= prior
            total += c
            if (c > best) {
                second = best
                best = c
                bestLag = lag
            } else if (c > second) {
                second = c
            }
        }
        var bpm = envRate * 60f / bestLag
        while (bpm < 80f) bpm *= 2f
        while (bpm > 170f) bpm *= 0.5f
        val beatMs = (60_000f / bpm).toLong().coerceIn(315L, 860L)
        val peakRatio = if (second <= 1e-6f) 1f else best / second
        val avgRatio = (best / (total / (maxLag - minLag + 1))).let { it / (it + 6f) }
        val confidence = (avgRatio * 0.70f + ((peakRatio - 1f) / 2f).coerceIn(0f, 1f) * 0.30f)
            .coerceIn(0f, 1f)
        val stable = confidence >= 0.42f && peakRatio >= 1.10f
        val first = firstOnsetMs(onset, frame, sr)
        val downbeat = snapRaw(first, beatMs * 4L, 0L).coerceAtLeast(0L)
        return TempoResult(bpm, confidence, stable, beatMs, downbeat)
    }

    private fun analyseStructure(x: FloatArray, sr: Int, durationMs: Long, tempo: TempoResult): StructureResult {
        val frame = sr / 10 // 100 ms
        val frames = (x.size / frame).coerceAtLeast(1)
        val rms = FloatArray(frames)
        val centroid = FloatArray(frames)
        var global = 0f
        var lowAcc = 0f
        var midAcc = 0f
        var highAcc = 0f
        var lpLow = 0f
        var lpMid = 0f
        val lowCoef = DspCoef.onePole(180f, sr)
        val midCoef = DspCoef.onePole(1800f, sr)
        for (i in 0 until frames) {
            var e = 0f
            var hf = 0f
            val base = i * frame
            for (j in 0 until frame) {
                val s = x[base + j]
                lpLow += lowCoef * (s - lpLow)
                lpMid += midCoef * (s - lpMid)
                val low = lpLow
                val high = s - lpMid
                val mid = s - low - high
                lowAcc += low * low
                midAcc += mid * mid
                highAcc += high * high
                e += s * s
                hf += abs(high)
            }
            val r = sqrt(e / frame)
            rms[i] = r
            centroid[i] = hf / frame
            global += r
        }
        global /= frames
        val threshold = max(global * 0.20f, 0.006f)
        val firstFrame = rms.indexOfFirst { it > threshold }.let { if (it < 0) 0 else it }
        val lastFrameInPreview = rms.indexOfLast { it > threshold }.let { if (it < 0) frames - 1 else it }
        val firstMs = firstFrame * 100L
        val previewLastMs = lastFrameInPreview * 100L
        val phraseMs = (tempo.beatMs * 32L).coerceIn(7_000L, 22_000L)
        val introEnd = (firstMs + phraseMs).coerceAtMost(min(durationMs, 35_000L))
        val outro = (durationMs - phraseMs).coerceAtLeast(0L)
        val lastAudible = if (durationMs <= x.size * 1000L / sr + 1_000L) previewLastMs else durationMs
        val lastVocalEnd = (outro - tempo.beatMs * 8L).coerceAtLeast(0L)
        val totalBand = (lowAcc + midAcc + highAcc).coerceAtLeast(1e-9f)
        val energy = rms(x).coerceIn(0f, 1f)
        val loudness = (20f * kotlin.math.log10(energy.coerceAtLeast(1e-5f))).coerceIn(-60f, 0f)
        return StructureResult(
            firstOnsetMs = firstMs,
            introEndMs = introEnd,
            outroStartMs = outro,
            lastAudibleMs = lastAudible,
            lastVocalEndMs = lastVocalEnd,
            low = (lowAcc / totalBand).coerceIn(0f, 1f),
            mid = (midAcc / totalBand).coerceIn(0f, 1f),
            high = (highAcc / totalBand).coerceIn(0f, 1f),
            loudnessDb = loudness,
            energy = energy
        )
    }

    private fun firstOnsetMs(onset: FloatArray, frame: Int, sr: Int): Long {
        val maxVal = onset.maxOrNull() ?: 0f
        val threshold = maxVal * 0.25f
        val i = onset.indexOfFirst { it >= threshold && it > 0.0005f }
        return if (i < 0) 0L else (i.toLong() * frame * 1000L / sr)
    }

    private fun snapToDownbeat(ms: Long, tempo: TempoResult): Long =
        snapRaw(ms, tempo.beatMs * 4L, tempo.downbeatOffsetMs)

    private fun snapRaw(ms: Long, grid: Long, offset: Long): Long {
        if (grid <= 0) return ms
        val shifted = ms - offset
        val snapped = ((shifted + grid / 2) / grid) * grid + offset
        return snapped.coerceAtLeast(0L)
    }

    private fun estimateKey(x: FloatArray, sr: Int): Triple<Int, Boolean, Float> {
        val chroma = FloatArray(12)
        val maxSamples = min(x.size, sr * 40)
        val noteFreqs = floatArrayOf(
            65.41f, 69.30f, 73.42f, 77.78f, 82.41f, 87.31f,
            92.50f, 98.00f, 103.83f, 110.00f, 116.54f, 123.47f
        )
        for (pc in 0 until 12) {
            var f = noteFreqs[pc]
            while (f < min(3200f, sr / 2.2f)) {
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
            i += 2
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
        private var data = FloatArray(initial.coerceAtLeast(1024))
        var size = 0
            private set
        fun add(v: Float) {
            if (size >= data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    private object DspCoef {
        fun onePole(freq: Float, sr: Int): Float {
            val x = 2f * PI.toFloat() * freq / sr
            return (x / (1f + x)).coerceIn(0f, 1f)
        }
    }

    private companion object {
        const val ANALYSIS_SR = 8000
    }
}
