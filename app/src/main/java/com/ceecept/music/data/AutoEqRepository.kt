package com.ceecept.music.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ceecept.music.audio.EqualizerProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/** Imports the uploaded app's AutoEQ headphone database into Ceecept's EQ. */
data class AutoEqPreset(
    val id: Long,
    val name: String,
    val meta: String,
    val parametric: Boolean,
    val gains: FloatArray
)

class AutoEqRepository(private val context: Context) {
    private val dbFile = File(context.filesDir, "uploaded_auto_eq.db")

    suspend fun search(query: String, limit: Int = 12): List<AutoEqPreset> = withContext(Dispatchers.IO) {
        ensureDb()
        if (query.trim().length < 2) return@withContext emptyList()
        val out = ArrayList<AutoEqPreset>()
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT _id,name,meta,parametric,data_blob FROM eq_presets WHERE name LIKE ? AND parametric=0 LIMIT ?",
                arrayOf("%${query.trim()}%", limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    val gains = parseGraphicBlob(c.getBlob(4)) ?: continue
                    out += AutoEqPreset(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "AutoEQ",
                        meta = c.getString(2) ?: "",
                        parametric = c.getInt(3) != 0,
                        gains = gains
                    )
                }
            }
        }
        out
    }

    private fun ensureDb() {
        if (dbFile.isFile && dbFile.length() > 1_000_000L) return
        context.assets.open("auto_eq.db").use { input ->
            dbFile.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun parseGraphicBlob(blob: ByteArray?): FloatArray? {
        if (blob == null || blob.size < 64) return null
        if (blob[0] != 'P'.code.toByte() || blob[1] != 'a'.code.toByte() || blob[2] != 'P'.code.toByte()) return null
        val bands = ArrayList<Pair<Float, Float>>()
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        // The first 12-byte record after the PaP header is Poweramp metadata/preamp;
        // fixed graphic bands start immediately after it.
        var off = 32
        while (off + 12 <= blob.size) {
            val freq = bb.getInt(off).toFloat()
            val type = bb.getInt(off + 4)
            val gain = bb.getFloat(off + 8)
            // Poweramp graphic EQ records use 0x5002. Keep only plausible audio-band points.
            if (type == 0x5002 && freq in 20f..22_000f && gain.isFinite()) {
                bands += freq to gain.coerceIn(-12f, 12f)
            }
            off += 12
        }
        if (bands.size < 6) return null
        val sorted = bands.distinctBy { it.first.toInt() }.sortedBy { it.first }
        val out = FloatArray(EqualizerProcessor.BANDS)
        for (i in out.indices) out[i] = interpolateLog(sorted, EqualizerProcessor.FREQUENCIES[i]).coerceIn(-12f, 12f)
        // Imported AutoEQ curves are corrective; soften to avoid overcorrecting phone speakers.
        for (i in out.indices) out[i] *= 0.72f
        return out
    }

    private fun interpolateLog(points: List<Pair<Float, Float>>, freq: Float): Float {
        if (freq <= points.first().first) return points.first().second
        if (freq >= points.last().first) return points.last().second
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            if (freq >= a.first && freq <= b.first) {
                val t = (ln(freq / a.first) / ln(b.first / a.first)).coerceIn(0f, 1f)
                return a.second + (b.second - a.second) * t
            }
        }
        return points.minBy { abs(it.first - freq) }.second
    }
}
