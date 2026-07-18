package com.example.auto_2048

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the 16 static sampling points so the user can drag them on the
 * overlay and the bot will keep using the new positions across app
 * restarts, without having to edit code.
 *
 * Stored as a flat string "x0,y0;x1,y1;…;x15,y15" in SharedPreferences
 * (key `sample_points_v1`). When empty / malformed, callers should fall
 * back to [MainActivity.STATIC_SAMPLE_POINTS].
 */
object SamplePointsStore {
    private const val PREFS = "auto_2048_overlay"
    private const val KEY = "sample_points_v1"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns saved points or null if nothing valid is stored. */
    fun load(ctx: Context): Array<Pair<Int, Int>>? {
        val raw = prefs(ctx).getString(KEY, null) ?: return null
        return try {
            val pairs = raw.split(";").map { token ->
                val parts = token.split(",")
                require(parts.size == 2)
                Pair(parts[0].trim().toInt(), parts[1].trim().toInt())
            }
            if (pairs.size == 16) pairs.toTypedArray() else null
        } catch (e: Throwable) {
            null
        }
    }

    /** Persist the 16 points. They replace the previous value entirely. */
    fun save(ctx: Context, points: Array<Pair<Int, Int>>) {
        require(points.size == 16)
        val raw = points.joinToString(";") { "${it.first},${it.second}" }
        prefs(ctx).edit().putString(KEY, raw).apply()
    }

    /** Wipe the override so the bot falls back to the built-in defaults. */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
