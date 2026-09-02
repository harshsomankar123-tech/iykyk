package com.example.iykyk.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.iykyk.domain.model.CollageConfig
import com.example.iykyk.domain.model.UniquePerson
import kotlin.math.max

object CollageBitmapRenderer {

    /**
     * Renders a clean, high-contrast Black and White 1080x1920 Instagram Story (9:16) collage.
     */
    fun renderStoryCollage(
        persons: List<UniquePerson>,
        config: CollageConfig = CollageConfig(),
        width: Int = 1080,
        height: Int = 1920
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Background (Pure Dark)
        val bgPaint = Paint().apply {
            color = Color.BLACK
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Header Area
        val headerTop = 90f
        var currentY = headerTop

        // Minimal App Tag (Pure White Border)
        val tagPaint = Paint().apply {
            color = Color.rgb(24, 24, 24)
            isAntiAlias = true
        }
        val tagBorderPaint = Paint().apply {
            color = Color.rgb(60, 60, 60)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val tagRect = RectF(60f, currentY, 260f, currentY + 40f)
        canvas.drawRoundRect(tagRect, 8f, 8f, tagPaint)
        canvas.drawRoundRect(tagRect, 8f, 8f, tagBorderPaint)

        val tagTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.1f
        }
        canvas.drawText("IYKYK COLLAGE", tagRect.centerX(), tagRect.centerY() + 6f, tagTextPaint)

        currentY += 70f

        // Title
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 52f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            letterSpacing = -0.02f
        }
        canvas.drawText(config.title.replace("✨", "").trim(), 60f, currentY, titlePaint)

        currentY += 42f

        // Subtitle
        val subtitlePaint = Paint().apply {
            color = Color.rgb(160, 160, 160)
            textSize = 26f
            isAntiAlias = true
        }
        val countText = "${persons.size} Unique ${if (persons.size == 1) "Individual" else "Individuals"} Detected"
        canvas.drawText(countText, 60f, currentY, subtitlePaint)

        currentY += 45f

        // 3. Grid Area
        val gridTop = currentY
        val gridBottom = height - 100f
        val gridLeft = 60f
        val gridRight = width - 60f

        val gridBounds = RectF(gridLeft, gridTop, gridRight, gridBottom)
        renderPersonGrid(canvas, persons, gridBounds, config)

        // 4. Footer Watermark
        val footerPaint = Paint().apply {
            color = Color.rgb(110, 110, 110)
            textSize = 20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(
            "Processed On-Device • ML Kit + TFLite",
            width / 2f,
            height - 45f,
            footerPaint
        )

        return bitmap
    }

    private fun renderPersonGrid(
        canvas: Canvas,
        persons: List<UniquePerson>,
        bounds: RectF,
        config: CollageConfig
    ) {
        if (persons.isEmpty()) return

        val spacing = 16f
        val count = persons.size

        when {
            count == 1 -> {
                drawPersonCard(canvas, persons[0], bounds, config)
            }
            count == 2 -> {
                val cardH = (bounds.height() - spacing) / 2f
                val card1 = RectF(bounds.left, bounds.top, bounds.right, bounds.top + cardH)
                val card2 = RectF(bounds.left, bounds.top + cardH + spacing, bounds.right, bounds.bottom)
                drawPersonCard(canvas, persons[0], card1, config)
                drawPersonCard(canvas, persons[1], card2, config)
            }
            count == 3 -> {
                val heroH = bounds.height() * 0.52f
                val heroRect = RectF(bounds.left, bounds.top, bounds.right, bounds.top + heroH)
                drawPersonCard(canvas, persons[0], heroRect, config)

                val bottomTop = bounds.top + heroH + spacing
                val bottomH = bounds.bottom - bottomTop
                val bottomW = (bounds.width() - spacing) / 2f
                val card2 = RectF(bounds.left, bottomTop, bounds.left + bottomW, bottomTop + bottomH)
                val card3 = RectF(bounds.left + bottomW + spacing, bottomTop, bounds.right, bottomTop + bottomH)
                drawPersonCard(canvas, persons[1], card2, config)
                drawPersonCard(canvas, persons[2], card3, config)
            }
            count == 4 -> {
                val cardW = (bounds.width() - spacing) / 2f
                val cardH = (bounds.height() - spacing) / 2f
                for (i in 0 until 4) {
                    val row = i / 2
                    val col = i % 2
                    val left = bounds.left + col * (cardW + spacing)
                    val top = bounds.top + row * (cardH + spacing)
                    val cardRect = RectF(left, top, left + cardW, top + cardH)
                    drawPersonCard(canvas, persons[i], cardRect, config)
                }
            }
            count == 5 -> {
                val topH = (bounds.height() - spacing) * 0.52f
                val topW = (bounds.width() - spacing) / 2f
                val card1 = RectF(bounds.left, bounds.top, bounds.left + topW, bounds.top + topH)
                val card2 = RectF(bounds.left + topW + spacing, bounds.top, bounds.right, bounds.top + topH)
                drawPersonCard(canvas, persons[0], card1, config)
                drawPersonCard(canvas, persons[1], card2, config)

                val botTop = bounds.top + topH + spacing
                val botH = bounds.bottom - botTop
                val botW = (bounds.width() - (2 * spacing)) / 3f
                for (j in 0 until 3) {
                    val left = bounds.left + j * (botW + spacing)
                    val cardRect = RectF(left, botTop, left + botW, botTop + botH)
                    drawPersonCard(canvas, persons[j + 2], cardRect, config)
                }
            }
            else -> {
                val cols = 2
                val rows = (count + 1) / 2
                val cardW = (bounds.width() - spacing) / cols
                val cardH = (bounds.height() - ((rows - 1) * spacing)) / rows
                for (i in persons.indices) {
                    val r = i / cols
                    val c = i % cols
                    val left = bounds.left + c * (cardW + spacing)
                    val top = bounds.top + r * (cardH + spacing)
                    val cardRect = RectF(left, top, left + cardW, top + cardH)
                    drawPersonCard(canvas, persons[i], cardRect, config)
                }
            }
        }
    }

    private fun drawPersonCard(
        canvas: Canvas,
        person: UniquePerson,
        rect: RectF,
        config: CollageConfig
    ) {
        val radius = 18f

        // Card background
        val cardPaint = Paint().apply {
            color = Color.rgb(20, 20, 20)
            isAntiAlias = true
        }
        canvas.drawRoundRect(rect, radius, radius, cardPaint)

        // Portrait Image
        val portrait = person.bestShotCrop ?: person.bestShot.frameBitmap
        if (portrait != null) {
            val roundedBmp = createRoundedBitmap(portrait, rect.width().toInt(), rect.height().toInt(), radius)
            canvas.drawBitmap(roundedBmp, rect.left, rect.top, null)
            roundedBmp.recycle()
        }

        // Gradient legibility overlay at bottom
        val overlayPaint = Paint().apply {
            shader = LinearGradient(
                rect.left, rect.bottom - 130f,
                rect.left, rect.bottom,
                Color.TRANSPARENT,
                Color.argb(230, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            RectF(rect.left, rect.bottom - 130f, rect.right, rect.bottom),
            radius, radius, overlayPaint
        )

        // Clean crisp border
        val strokePaint = Paint().apply {
            color = Color.rgb(45, 45, 45)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // Person Name Label (Bottom Left)
        val namePaint = Paint().apply {
            color = Color.WHITE
            textSize = if (rect.width() < 300f) 22f else 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText(person.displayName, rect.left + 18f, rect.bottom - 20f, namePaint)

        // Appearance Badge (Top Right)
        if (config.showAppearanceBadges) {
            val badgeText = "${person.totalAppearances} ${if (person.totalAppearances == 1) "appearance" else "appearances"}"
            val badgeTextPaint = Paint().apply {
                color = Color.WHITE
                textSize = if (rect.width() < 300f) 16f else 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val textW = badgeTextPaint.measureText(badgeText)
            val badgePadH = 12f
            val badgeH = 30f
            val badgeW = textW + (badgePadH * 2f)

            val badgeRect = RectF(
                rect.right - badgeW - 14f,
                rect.top + 14f,
                rect.right - 14f,
                rect.top + 14f + badgeH
            )

            val badgeBgPaint = Paint().apply {
                color = Color.argb(220, 0, 0, 0)
                isAntiAlias = true
            }
            canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBgPaint)

            val badgeBorder = Paint().apply {
                color = Color.rgb(70, 70, 70)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            canvas.drawRoundRect(badgeRect, 6f, 6f, badgeBorder)

            canvas.drawText(
                badgeText,
                badgeRect.left + badgePadH,
                badgeRect.top + badgeH - 8f,
                badgeTextPaint
            )
        }
    }

    private fun createRoundedBitmap(src: Bitmap, targetW: Int, targetH: Int, cornerRadius: Float): Bitmap {
        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, targetW.toFloat(), targetH.toFloat()),
                cornerRadius, cornerRadius,
                Path.Direction.CW
            )
        }
        canvas.clipPath(path)

        val srcW = src.width
        val srcH = src.height
        val scale = max(targetW.toFloat() / srcW.toFloat(), targetH.toFloat() / srcH.toFloat())
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        val left = (targetW - scaledW) / 2
        val top = (targetH - scaledH) / 2

        val dstRect = Rect(left, top, left + scaledW, top + scaledH)
        val srcRect = Rect(0, 0, srcW, srcH)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(src, srcRect, dstRect, paint)

        return output
    }
}
