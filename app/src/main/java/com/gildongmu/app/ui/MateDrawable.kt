package com.gildongmu.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.gildongmu.app.data.LookItem
import com.gildongmu.app.data.Stage
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 길동무 캐릭터 (디자인 B안 "길동무"): 한마디를 남긴 사람이 그 자리에 서 있는 모습이다.
 * 단계는 모습으로 나타낸다. 씨앗(확인 중) = 점선 윤곽, 새싹(공개) = 서 있음, 꽃 = 꽃을 듦, 추억 = 벤치에 앉음.
 * 주행 중에도 보이므로 움직이지 않는 그림이다. 몸 색은 한마디마다 정해져 길 위의 여러 길동무를 구분한다.
 * 여러 명이면 셋까지 겹쳐 그린다(묶음). 앞사람이 가장 앞에 온다.
 */
class MateDrawable(private val mates: List<Mate>) : Drawable() {

    /** 길동무 한 명: 단계, 몸 색, 소품(내 길동무만 고른다. 다른 길동무는 없음). */
    data class Mate(val stage: Stage, val bodyColor: Int, val item: LookItem = LookItem.NONE)

    constructor(stage: Stage, bodyColor: Int, item: LookItem = LookItem.NONE) : this(listOf(Mate(stage, bodyColor, item)))

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val shown = mates.take(MAX_SHOWN)
        if (shown.isEmpty()) return
        val box = min(bounds.width(), bounds.height()).toFloat()
        // 한 명이면 상자를 다 쓰고, 여럿이면 조금 작게 해서 옆으로 비켜 세운다.
        val size = if (shown.size == 1) box else box * 0.74f
        val step = if (shown.size == 1) 0f else (box - size) / (shown.size - 1)
        val left = bounds.left + (bounds.width() - box) / 2
        val top = bounds.top + (bounds.height() - size)
        shown.reversed().forEachIndexed { i, mate ->
            val x = left + step * (shown.size - 1 - i)
            canvas.save()
            canvas.translate(x, top)
            drawMate(canvas, mate, size)
            canvas.restore()
        }
    }

    private fun drawMate(canvas: Canvas, mate: Mate, s: Float) {
        when (mate.stage) {
            Stage.MEMORY -> drawSitting(canvas, mate.bodyColor, s)
            else -> drawStanding(canvas, mate, s)
        }
    }

    private fun drawStanding(canvas: Canvas, mate: Mate, s: Float) {
        // 발밑 그림자
        fill.color = SHADOW
        rect.set(0.28f * s, 0.9f * s, 0.72f * s, 0.98f * s)
        canvas.drawOval(rect, fill)

        if (mate.stage == Stage.SEED) {
            // 아직 확인 중: 점선 윤곽만
            line.color = mate.bodyColor
            line.strokeWidth = 0.045f * s
            line.pathEffect = DashPathEffect(floatArrayOf(0.07f * s, 0.05f * s), 0f)
            rect.set(0.33f * s, 0.43f * s, 0.67f * s, 0.92f * s)
            canvas.drawRoundRect(rect, 0.17f * s, 0.17f * s, line)
            canvas.drawCircle(0.5f * s, 0.27f * s, 0.14f * s, line)
            line.pathEffect = null
            return
        }

        if (mate.item == LookItem.BACKPACK) {
            // 배낭은 몸 뒤(왼쪽)에 먼저 그린다.
            fill.color = BACKPACK
            rect.set(0.22f * s, 0.48f * s, 0.42f * s, 0.8f * s)
            canvas.drawRoundRect(rect, 0.06f * s, 0.06f * s, fill)
        }
        fill.color = mate.bodyColor
        rect.set(0.33f * s, 0.43f * s, 0.67f * s, 0.92f * s)
        canvas.drawRoundRect(rect, 0.17f * s, 0.17f * s, fill)
        drawHead(canvas, 0.5f * s, 0.27f * s, 0.15f * s)
        drawItem(canvas, mate.item, 0.5f * s, 0.27f * s, 0.15f * s, s)

        if (mate.stage == Stage.FLOWER) {
            // 꽃을 든 길동무: 고마워요를 많이 받은 한마디
            fill.color = SKIN
            canvas.drawCircle(0.7f * s, 0.62f * s, 0.05f * s, fill)
            line.color = STEM
            line.strokeWidth = 0.035f * s
            canvas.drawLine(0.73f * s, 0.6f * s, 0.8f * s, 0.36f * s, line)
            fill.color = PETAL
            for (k in 0 until 5) {
                val a = Math.toRadians(k * 72.0 - 90.0)
                canvas.drawCircle(0.8f * s + (0.06f * s * cos(a)).toFloat(), 0.3f * s + (0.06f * s * sin(a)).toFloat(), 0.05f * s, fill)
            }
            fill.color = POLLEN
            canvas.drawCircle(0.8f * s, 0.3f * s, 0.04f * s, fill)
        }
    }

    /** 추억: 오래된 한마디. 벤치에 앉아 쉬는 길동무. */
    private fun drawSitting(canvas: Canvas, bodyColor: Int, s: Float) {
        fill.color = BENCH
        rect.set(0.1f * s, 0.5f * s, 0.9f * s, 0.56f * s)
        canvas.drawRoundRect(rect, 0.02f * s, 0.02f * s, fill)
        rect.set(0.1f * s, 0.72f * s, 0.9f * s, 0.78f * s)
        canvas.drawRoundRect(rect, 0.02f * s, 0.02f * s, fill)
        rect.set(0.16f * s, 0.78f * s, 0.21f * s, 0.95f * s)
        canvas.drawRect(rect, fill)
        rect.set(0.79f * s, 0.78f * s, 0.84f * s, 0.95f * s)
        canvas.drawRect(rect, fill)

        fill.color = bodyColor
        fill.alpha = 200
        rect.set(0.36f * s, 0.47f * s, 0.64f * s, 0.75f * s)
        canvas.drawRoundRect(rect, 0.14f * s, 0.14f * s, fill)
        fill.alpha = 255
        drawHead(canvas, 0.5f * s, 0.34f * s, 0.13f * s)
    }

    /** 모자·목도리. 머리 가운데(cx, cy)와 반지름 r에 맞춰 그린다. */
    private fun drawItem(canvas: Canvas, item: LookItem, cx: Float, cy: Float, r: Float, s: Float) {
        when (item) {
            LookItem.HAT -> {
                fill.color = HAT
                rect.set(cx - r * 1.35f, cy - r * 0.95f, cx + r * 1.35f, cy - r * 0.62f)
                canvas.drawRoundRect(rect, r * 0.16f, r * 0.16f, fill)
                rect.set(cx - r * 0.8f, cy - r * 1.6f, cx + r * 0.8f, cy - r * 0.7f)
                canvas.drawRoundRect(rect, r * 0.35f, r * 0.35f, fill)
            }
            LookItem.SCARF -> {
                fill.color = SCARF
                rect.set(cx - r * 1.12f, cy + r * 1.0f, cx + r * 1.12f, cy + r * 1.45f)
                canvas.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
                rect.set(cx + r * 0.35f, cy + r * 1.2f, cx + r * 0.8f, cy + r * 2.2f)
                canvas.drawRoundRect(rect, r * 0.15f, r * 0.15f, fill)
            }
            else -> Unit
        }
    }

    private fun drawHead(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = SKIN
        canvas.drawCircle(cx, cy, r, fill)
        fill.color = CHEEK
        canvas.drawCircle(cx - r * 0.58f, cy + r * 0.32f, r * 0.17f, fill)
        canvas.drawCircle(cx + r * 0.58f, cy + r * 0.32f, r * 0.17f, fill)
        fill.color = EYE
        canvas.drawCircle(cx - r * 0.35f, cy, r * 0.12f, fill)
        canvas.drawCircle(cx + r * 0.35f, cy, r * 0.12f, fill)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        line.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        line.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val MAX_SHOWN = 3
        // 사람 그림의 색은 낮·밤 모두 같은 색을 쓴다(피부·꽃처럼 실제 색).
        private const val SKIN = 0xFFF5C4B3.toInt()
        private const val EYE = 0xFF2C2C2A.toInt()
        private const val CHEEK = 0x99ED93B1.toInt()
        private const val SHADOW = 0x33000000
        private const val STEM = 0xFF639922.toInt()
        private const val PETAL = 0xFFED93B1.toInt()
        private const val POLLEN = 0xFFFAC775.toInt()
        private const val BENCH = 0xFF8D6E63.toInt()
        private const val HAT = 0xFFFAC775.toInt()
        private const val SCARF = 0xFF97C459.toInt()
        private const val BACKPACK = 0xFF8D6E63.toInt()

        /** 몸 색. 낮·밤 바탕 모두에서 보이는 가운데 밝기의 색들이다. */
        private val BODY_COLORS = intArrayOf(
            0xFF1D9E75.toInt(), 0xFFD85A30.toInt(), 0xFF7F77DD.toInt(),
            0xFF378ADD.toInt(), 0xFFBA7517.toInt(), 0xFFD4537E.toInt(),
        )

        /** 한마디 id로 몸 색을 정한다. 같은 한마디는 늘 같은 색이고, 누가 남겼는지는 드러나지 않는다. */
        fun colorFor(messageId: String): Int = BODY_COLORS[Math.floorMod(messageId.hashCode(), BODY_COLORS.size)]

        /** 내 길동무가 고른 몸 색(0~5). */
        fun bodyColor(index: Int): Int = BODY_COLORS[Math.floorMod(index, BODY_COLORS.size)]

        const val COLOR_COUNT = 6
    }
}
