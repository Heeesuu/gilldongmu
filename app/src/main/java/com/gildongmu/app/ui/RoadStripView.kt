package com.gildongmu.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.gildongmu.app.R
import com.gildongmu.app.data.Stage
import kotlin.math.abs
import kotlin.math.min

/**
 * 길 띠: 앞쪽 600m 길 위에 한마디를 남긴 길동무가 어디 서 있는지 보여준다 (디자인 "길동무").
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
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gd_text_sub)
        textSize = 22f * scaled
    }
    private val strongLabelPaint = Paint(labelPaint).apply {
        color = context.getColor(R.color.gd_text)
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val carIcon = context.getDrawable(R.drawable.ic_car)!!.mutate().apply { setTint(context.getColor(R.color.gd_bg)) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gd_text) }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gd_bg)
        textSize = 18f * scaled
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
    private val highlightColor = context.getColor(R.color.gd_highlight)
    private val pinBg = context.getColor(R.color.gd_pin_bg)
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

        // 가까이 모인 길동무는 한 자리에 셋까지 겹쳐 세우고 사람 수를 붙인다.
        // 먼 자리부터 그려서 가까운(곧 닿을) 자리가 위에 온다.
        clusters(pins.sortedBy { it.distanceM }) { along(it.distanceM) }.reversed().forEach { group ->
            val pin = group.first()
            val at = along(pin.distanceM)
            val (x, y) = if (vertical) w * 0.36f to at else at to h * 0.40f
            val highlighted = group.any { it.highlighted }
            // 여럿이 선 자리는 겹쳐 그리므로 한 명일 때보다 크게 잡는다.
            val box = dp((if (group.size > 1) 88f else 68f) + if (highlighted) 16f else 0f)
            drawMates(canvas, group, highlighted, x, y, box)
            // 세로 띠에서는 글자를 길동무 몸 높이에, 가로 띠에서는 발밑 아래에 둔다.
            drawLabel(canvas, if (group.size > 1) "${pin.distanceM}m · ${group.size}명" else "${pin.distanceM}m", highlighted, x, if (vertical) y - box * 0.4f else y, road, vertical)
        }
    }

    /**
     * 가까운 순으로 정렬된 핀을 묶는다. 앞 묶음의 첫 핀에서 50m 안이면(한 번에 묻는 기준, GuidePolicy와 같음)
     * 또는 화면에서 겹칠 만큼(`CLUSTER_DP`) 가까우면 같은 묶음이다.
     */
    private fun clusters(sorted: List<Pin>, position: (Pin) -> Float): List<List<Pin>> {
        val groups = mutableListOf<MutableList<Pin>>()
        sorted.forEach { pin ->
            val first = groups.lastOrNull()?.first()
            val together = first != null &&
                (pin.distanceM - first.distanceM <= GROUP_M || abs(position(first) - position(pin)) < dp(CLUSTER_DP))
            if (together) groups.last() += pin else groups += mutableListOf(pin)
        }
        return groups
    }

    /** 길동무가 선 자리: 발밑 그림자(지금 묻거나 듣는 자리는 초록) 위에 길동무를 세운다. 발이 (x, y)에 닿는다. */
    private fun drawMates(canvas: Canvas, group: List<Pin>, highlighted: Boolean, x: Float, y: Float, box: Float) {
        groundPaint.color = if (highlighted) highlightColor else pinBg
        rect.set(x - box * 0.44f, y - box * 0.1f, x + box * 0.44f, y + box * 0.14f)
        canvas.drawOval(rect, groundPaint)
        val figure = MateDrawable(group.map { MateDrawable.Mate(it.stage, MateDrawable.colorFor(it.id)) })
        figure.setBounds((x - box / 2).toInt(), (y - box * 0.94f).toInt(), (x + box / 2).toInt(), (y + box * 0.06f).toInt())
        figure.draw(canvas)
        if (group.size > 1) {
            // 오른쪽 위에 사람 수
            val bx = x + box * 0.44f
            val by = y - box * 0.86f
            canvas.drawCircle(bx, by, dp(16f), badgePaint)
            canvas.drawText("${group.size}", bx - badgeTextPaint.measureText("${group.size}") / 2, by + badgeTextPaint.textSize * 0.36f, badgeTextPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, highlighted: Boolean, x: Float, y: Float, road: Float, vertical: Boolean) {
        val paint = if (highlighted) strongLabelPaint else labelPaint
        if (vertical) {
            canvas.drawText(text, x + road / 2 + dp(14f), y + paint.textSize * 0.35f, paint)
        } else {
            canvas.drawText(text, x - paint.measureText(text) / 2, y + road / 2 + paint.textSize + dp(10f), paint)
        }
    }

    private fun describe(pins: List<Pin>): String =
        if (pins.isEmpty()) "앞쪽 길은 아직 조용해요"
        else "앞쪽 길에 한마디를 남긴 길동무 ${pins.size}명: " + pins.joinToString(", ") { "${it.distanceM}m ${it.stage.label}" }

    private fun dp(value: Float) = value * density

    companion object {
        const val LOOKAHEAD_M = 600
        private const val CLUSTER_DP = 76f
        private const val GROUP_M = 50
    }
}
