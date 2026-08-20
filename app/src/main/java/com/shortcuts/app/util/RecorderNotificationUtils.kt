package com.shortcuts.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat

object RecorderNotificationPresenter {
    const val MAX_COUNT_DISPLAY = "99+"

    fun formatBadgeText(count: Int): String {
        return when {
            count <= 0 -> ""
            count > 99 -> MAX_COUNT_DISPLAY
            else -> count.toString()
        }
    }

    fun formatContentText(count: Int): String {
        return if (count == 1) {
            "Recording — 1 step captured"
        } else {
            "Recording — $count steps captured"
        }
    }

    fun formatChipText(count: Int): String {
        val countStr = when {
            count <= 0 -> "0"
            count > 99 -> MAX_COUNT_DISPLAY
            else -> count.toString()
        }
        return "REC $countStr"
    }

    enum class Presentation {
        PROMOTED_CHIP,
        BITMAP_BADGE
    }

    fun determinePresentation(apiLevel: Int, canPromote: Boolean): Presentation {
        return if (apiLevel >= 36 && canPromote) {
            Presentation.PROMOTED_CHIP
        } else {
            Presentation.BITMAP_BADGE
        }
    }

    fun createSmallIcon(context: Context, count: Int): IconCompat {
        val displayMetrics = context.resources.displayMetrics
        val sizePx = (24 * displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val text = formatBadgeText(count)
        
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * displayMetrics.density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        val dotRadius = 2.5f * displayMetrics.density
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        if (text.isEmpty()) {
            canvas.drawCircle(sizePx / 2f, sizePx / 2f, dotRadius * 1.5f, dotPaint)
        } else {
            val textBounds = Rect()
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            
            if (text == MAX_COUNT_DISPLAY) {
                textPaint.textSize = 10f * displayMetrics.density
                textPaint.getTextBounds(text, 0, text.length, textBounds)
            }

            val textHeight = textBounds.height()
            val spacing = 2f * displayMetrics.density
            val textWidth = textPaint.measureText(text)
            
            val totalWidth = (dotRadius * 2) + spacing + textWidth
            val startX = (sizePx - totalWidth) / 2f
            
            val dotX = startX + dotRadius
            val dotY = sizePx / 2f
            canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
            
            val textX = startX + (dotRadius * 2) + spacing + (textWidth / 2f)
            val textY = (sizePx / 2f) + (textHeight / 2f) - textBounds.bottom
            canvas.drawText(text, textX, textY, textPaint)
        }

        return IconCompat.createWithBitmap(bitmap)
    }
}

class NotificationThrottler(
    private val windowMillis: Long = 500L,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val scheduleTask: (delay: Long, task: () -> Unit) -> Any,
    private val cancelTask: (Any) -> Unit,
    private val onUpdate: (Int) -> Unit
) {
    private val lock = Any()
    private var lastUpdateMillis = 0L
    private var pendingTask: Any? = null
    private var pendingCount: Int? = null

    fun onCountChanged(count: Int) = synchronized(lock) {
        val now = nowMillis()
        if (now - lastUpdateMillis >= windowMillis) {
            dispatchNow(count, now)
        } else {
            pendingCount = count
            if (pendingTask == null) {
                val delay = windowMillis - (now - lastUpdateMillis)
                pendingTask = scheduleTask(delay) {
                    synchronized(lock) {
                        pendingTask = null
                        pendingCount?.let {
                            pendingCount = null
                            dispatchNow(it, nowMillis())
                        }
                    }
                }
            }
        }
    }

    fun forceUpdate(count: Int) = synchronized(lock) {
        pendingTask?.let { cancelTask(it) }
        pendingTask = null
        pendingCount = null
        dispatchNow(count, nowMillis())
    }

    private fun dispatchNow(count: Int, now: Long) {
        lastUpdateMillis = now
        onUpdate(count)
    }
}
