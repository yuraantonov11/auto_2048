package com.example.auto_2048

import android.util.Log

/**
 * Tiny structured-logging façade over [android.util.Log].
 *
 * The codebase used to call `android.util.Log.{v,d,i,w,e}(...)` directly at
 * ~64 call sites. Direct calls have three downsides:
 *
 *   1. Log level is encoded at every site (`Log.d`, `Log.w`, ...) — adding a
 *      new level means a sweeping refactor.
 *   2. Tags are magic strings (`"Auto2048"`, `"Auto2048Service"`,
 *      `"GameSolver"`, ...) that drift across files.
 *   3. There is no place to plug a release-build switch (e.g. silent in
 *      release) or a remote sink later.
 *
 * [Logger] centralises the format, the tag convention (`Auto2048/<source>`),
 * and the level-to-priority mapping. To migrate a file, replace
 * `Log.d(TAG, "msg")` with `Logger.d(TAG_LOCAL, "msg")` — `TAG_LOCAL` is a
 * private constant in the host file (e.g. `"GameSolver"`).
 */
object Logger {

    /** Severity ordered from most to least verbose. */
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    /** Root tag every log line is prefixed with. */
    const val ROOT_TAG = "Auto2048"

    /**
     * Emit a log line. The final Android tag is
     * `"$ROOT_TAG/$source"`.
     */
    fun log(
        level: Level,
        source: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val tag = "$ROOT_TAG/$source"
        val composed = if (throwable == null) {
            message
        } else {
            "$message | ${throwable::class.java.simpleName}: ${throwable.message}"
        }
        when (level) {
            Level.VERBOSE -> Log.v(tag, composed, throwable)
            Level.DEBUG -> Log.d(tag, composed, throwable)
            Level.INFO -> Log.i(tag, composed, throwable)
            Level.WARN -> Log.w(tag, composed, throwable)
            Level.ERROR -> Log.e(tag, composed, throwable)
        }
    }

    fun v(source: String, message: String, throwable: Throwable? = null) =
        log(Level.VERBOSE, source, message, throwable)

    fun d(source: String, message: String, throwable: Throwable? = null) =
        log(Level.DEBUG, source, message, throwable)

    fun i(source: String, message: String, throwable: Throwable? = null) =
        log(Level.INFO, source, message, throwable)

    fun w(source: String, message: String, throwable: Throwable? = null) =
        log(Level.WARN, source, message, throwable)

    fun e(source: String, message: String, throwable: Throwable? = null) =
        log(Level.ERROR, source, message, throwable)
}