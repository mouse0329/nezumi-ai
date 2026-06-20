package com.nezumi_ai.presentation.ui.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val baseMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val lastTouch = PointF()
    private var minScale = 1f
    private var maxScale = 5f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = target / current
                drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                constrain()
                imageMatrix = drawMatrix
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (currentScale() > minScale * 1.2f) minScale else minScale * 2.5f
                resetToScale(target, e.x, e.y)
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetBaseMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetBaseMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastTouch.set(event.x, event.y)
                isDragging = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress) {
                    drawMatrix.postTranslate(event.x - lastTouch.x, event.y - lastTouch.y)
                    constrain()
                    imageMatrix = drawMatrix
                    lastTouch.set(event.x, event.y)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = if (event.actionIndex == 0) 1 else 0
                if (index < event.pointerCount) {
                    lastTouch.set(event.getX(index), event.getY(index))
                }
            }
        }
        return true
    }

    private fun resetBaseMatrix() {
        val drawable = drawable ?: return
        val viewWidth = width
        val viewHeight = height
        if (viewWidth <= 0 || viewHeight <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return
        }

        val scale = min(
            viewWidth.toFloat() / drawable.intrinsicWidth.toFloat(),
            viewHeight.toFloat() / drawable.intrinsicHeight.toFloat()
        )
        val dx = (viewWidth - drawable.intrinsicWidth * scale) * 0.5f
        val dy = (viewHeight - drawable.intrinsicHeight * scale) * 0.5f

        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        minScale = scale
        maxScale = max(scale * 5f, 5f)
        drawMatrix.set(baseMatrix)
        imageMatrix = drawMatrix
    }

    private fun resetToScale(targetScale: Float, focusX: Float, focusY: Float) {
        val factor = targetScale / currentScale()
        drawMatrix.postScale(factor, factor, focusX, focusY)
        constrain()
        imageMatrix = drawMatrix
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X].takeIf { it > 0f } ?: minScale
    }

    private fun constrain() {
        val drawable = drawable ?: return
        val rect = android.graphics.RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        drawMatrix.mapRect(rect)

        var dx = 0f
        var dy = 0f
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        dx = if (rect.width() <= viewWidth) {
            viewWidth * 0.5f - rect.centerX()
        } else {
            when {
                rect.left > 0f -> -rect.left
                rect.right < viewWidth -> viewWidth - rect.right
                else -> 0f
            }
        }

        dy = if (rect.height() <= viewHeight) {
            viewHeight * 0.5f - rect.centerY()
        } else {
            when {
                rect.top > 0f -> -rect.top
                rect.bottom < viewHeight -> viewHeight - rect.bottom
                else -> 0f
            }
        }

        drawMatrix.postTranslate(dx, dy)
    }
}
