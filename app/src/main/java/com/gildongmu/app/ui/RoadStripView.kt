package com.gildongmu.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.gildongmu.app.R
import com.gildongmu.app.StageIcon
import com.gildongmu.app.data.Stage
import kotlin.math.abs
import kotlin.math.min

/**
 * 길 띠: 앞쪽 600m 길 위에 한마디가 어디 놓였는지 보여준다 (디자인 B안 "길 위의 방명록").
 * 주행 중에도 보이므로 움직이는 그림은 쓰지 않고, 값이 바뀔 때만 다시 그린다 (Pleos 주행 규제).
 * 세로로 길면 차가 아래·앞쪽이 위이고, 가로로 길면(화면 나누기 등) 차가 왼쪽·앞쪽이 오른쪽이다.
 */
class RoadStripView(context: Context) : View(context) {

    /** 길 위 한마디 하나. `distanceM`은 10m 단위로 반올림해서 넘긴다(필요 없이 다시 그리지 않도록). */
    data class Pin(val id: String, val distanceM: Int, val stage: Stage, val highlighted: Boolean)

    var pins: List<Pin> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            contentDescription = describe(value)
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gd_road) }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gd_lane)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(18f), dp(14f)), 0f)
    }
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gd_text) }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gd_pin_bg) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gd_text_sub)
        textSize = 22f * scaled
    }
    private val strongLabelPaint = Paint(labelPaint).apply {
        color = context.getColor(R.color.gd_text)
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val carIcon = context.getDrawable(R.drawable.ic_car)!!.mutate().apply { setTint(context.getColor(R.color.gd_bg)) }
    private val stageColors = mapOf(
        Stage.SEED to context.getColor(R.color.gd_stage_seed),
        Stage.SPROUT to context.getColor(R.color.gd_stage_sprout),
        Stage.FLOWER to context.getColor(R.color.gd_stage_flower),
        Stage.MEMORY to context.getColor(R.color.gd_stage_memory),
    )
    private val stageIcons = Stage.entries.associateWith { context.getDrawable(StageIcon.of(it))!! }
    private val highlightColor = context.getColor(R.color.gd_highlight)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val vertical = height >= width
        val w = width.toFloat()
        val h = height.toFloat()
        val car = dp(64f)
        val road = min(if (vertical) w * 0.42f else h * 0.42f, dp(112f))

        // 길: 띠 하나와 가운데 점선
        if (vertical) {
            val cx = w * 0.36f
            rect.set(cx - road / 2, 0f, cx + road / 2, h)
            canvas.drawRoundRect(rect, road / 2, road / 2, roadPaint)
            canvas.drawLine(cx, dp(16f), cx, h - car - dp(16f), lanePaint)
        } else {
            val cy = h * 0.40f
            rect.set(0f, cy - road / 2, w, cy + road / 2)
            canvas.drawRoundRect(rect, road / 2, road / 2, roadPaint)
            canvas.drawLine(car + dp(16f), cy, w - dp(16f), cy, lanePaint)
        }

        // 내 차: 0m 자리
        val (carX, carY) = if (vertical) w * 0.36f to h - car / 2 - dp(12f) else car / 2 + dp(12f) to h * 0.40f
        rect.set(carX - car / 2, carY - car / 2, carX + car / 2, carY + car / 2)
        canvas.drawRoundRect(rect, dp(16f), dp(16f), carPaint)
        val icon = (car * 0.62f).toInt() / 2
        carIcon.setBounds(carX.toInt() - icon, carY.toInt() - icon, carX.toInt() + icon, carY.toInt() + icon)
        carIcon.draw(canvas)

        // 0m(차 바로 앞)부터 600m(띠 끝)까지를 길이에 맞춰 늘인다.
        val start = if (vertical) carY - car / 2 - dp(44f) else carX + car / 2 + dp(44f)
        val end = if (vertical) dp(44f) else w - dp(44f)
        fun along(distanceM: Int) = start + (end - start) * (distanceM.coerceIn(0, LOOKAHEAD_M) / LOOKAHEAD_M.toFloat())

        // 먼 것부터 그려서 가까운(곧 닿을) 한마디가 위에 오게 한다. 글자가 겹치면 먼 쪽 글자는 뺀다.
        var lastLabelAt: Float? = null
        pins.sortedBy { it.distanceM }.let { near ->
            val labelled = near.filter { pin ->
                val at = along(pin.distanceM)
                val show = lastLabelAt?.let { abs(it - at) >= dp(40f) } ?: true
                if (show) lastLabelAt = at
                show || pin.highlighted
            }.map { it.id }.toSet()
            near.reversed().forEach { pin ->
                val at = along(pin.distanceM)
                val (x, y) = if (vertical) w * 0.36f to at else at to h * 0.40f
                drawPin(canvas, pin, x, y)
                if (pin.id in labelled) drawLabel(canvas, pin, x, y, road, vertical)
            }
        }
    }

    private fun drawPin(canvas: Canvas, pin: Pin, x: Float, y: Float) {
        val radius = dp(if (pin.highlighted) 38f else 30f)
        canvas.drawCircle(x, y, radius, pinPaint)
        ringPaint.color = if (pin.highlighted) highlightColor else stageColors.getValue(pin.stage)
        ringPaint.strokeWidth = dp(if (pin.highlighted) 7f else 5f)
        canvas.drawCircle(x, y, radius, ringPaint)
        val icon = stageIcons.getValue(pin.stage)
        val half = (radius * 0.72f).toInt()
        icon.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
        icon.draw(canvas)
    }

    private fun drawLabel(canvas: Canvas, pin: Pin, x: Float, y: Float, road: Float, vertical: Boolean) {
        val paint = if (pin.highlighted) strongLabelPaint else labelPaint
        val text = "${pin.distanceM}m"
        if (vertical) {
            canvas.drawText(text, x + road / 2 + dp(14f), y + paint.textSize * 0.35f, paint)
        } else {
            canvas.drawText(text, x - paint.measureText(text) / 2, y + road / 2 + paint.textSize + dp(10f), paint)
        }
    }

    private fun describe(pins: List<Pin>): String =
        if (pins.isEmpty()) "앞쪽 길에 한마디가 없어요"
        else "앞쪽 길 한마디 ${pins.size}개: " + pins.joinToString(", ") { "${it.distanceM}m ${it.stage.label}" }

    private fun dp(value: Float) = value * density

    companion object {
        const val LOOKAHEAD_M = 600
    }
}
