package com.example.auto_2048

/**
 * Thin delegate over [GameConfig] so that [Auto2048Service] (which dispatches
 * gestures) reads exactly the same bounds that [VisionProcessor] uses for grid
 * recognition and that [OverlayService] draws on screen.
 *
 * Previously this object had its own hardcoded fractions (0.23/0.64/0.13/0.92)
 * that were never updated from user calibration, causing swipes to land in a
 * different area than the visible frame.
 *
 * All values are computed from [GameConfig.detectedXStart] etc., which are
 * set either by the user's manual calibration or by [VisionProcessor.detectBoard].
 */
object GridCoordinates {

    val gridXStart: Int get() = GameConfig.detectedXStart
    val gridYStart: Int get() = GameConfig.detectedYStart
    val gridXEnd: Int get() = GameConfig.detectedXEnd
    val gridYEnd: Int get() = GameConfig.detectedYEnd

    /** True once GameConfig has non-zero bounds (from calibration or auto-detect). */
    val isCalibrated: Boolean get() =
        gridYStart >= 0 && gridYEnd > gridYStart && gridXStart >= 0 && gridXEnd > gridXStart

    fun cellWidth(): Float = (gridXEnd - gridXStart) / 4f
    fun cellHeight(): Float = (gridYEnd - gridYStart) / 4f
    fun midX(): Float = (gridXStart + gridXEnd) / 2f
    fun midY(): Float = (gridYStart + gridYEnd) / 2f
}
