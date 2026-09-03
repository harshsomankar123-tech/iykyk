package com.example.iykyk.domain.util

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Shared axis-aligned bounding-box geometry.
 *
 * Defined once and reused by both face detection (Non-Maximum Suppression) and
 * identity clustering so the Intersection-over-Union formula never drifts between call sites.
 */
object BoxGeometry {

    /**
     * Intersection-over-Union of two rectangles. Returns 0 when they do not overlap
     * or when the union area is degenerate.
     */
    fun iou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interWidth = max(0, interRight - interLeft)
        val interHeight = max(0, interBottom - interTop)
        val interArea = interWidth * interHeight

        val areaA = max(0, a.right - a.left) * max(0, a.bottom - a.top)
        val areaB = max(0, b.right - b.left) * max(0, b.bottom - b.top)
        val unionArea = areaA + areaB - interArea

        if (unionArea <= 0) return 0f
        return interArea.toFloat() / unionArea.toFloat()
    }
}
