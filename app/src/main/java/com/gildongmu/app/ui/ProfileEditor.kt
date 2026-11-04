package com.gildongmu.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gildongmu.app.R
import com.gildongmu.app.data.Look
import com.gildongmu.app.data.LookItem
import com.gildongmu.app.data.NicknameIdeas
import com.gildongmu.app.data.Profile
import com.gildongmu.app.data.Stage
import java.time.Instant
import java.time.ZoneId

/**
 * 내 길동무 정하기 화면: 몸 색·소품을 고르고 닉네임을 정한다 (처음 실행할 때, 설정에서 바꿀 때).
 * 글자를 입력하므로 정차 중에만 띄운다. 닉네임은 내 기기에서만 보이고, 30일에 한 번 바꿀 수 있다(서버가 확인).
 * `onCancel`이 null이면 처음 실행이라 취소 없이 정해야 한다.
 */
class ProfileEditor(
    private val context: Context,
    initial: Profile?,
    private val onSave: (nickname: String, look: Look) -> Unit,
    private val onCancel: (() -> Unit)?,
) {
    private val density = context.resources.displayMetrics.density
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private var bodyIndex = initial?.look?.color ?: 0
    private var item = initial?.look?.item ?: LookItem.HAT
    private val preview = ImageView(context)
    private val swatches = mutableListOf<View>()
    private val itemButtons = mutableMapOf<LookItem, ActionButton>()
    private val nicknameField = EditText(context)
    private val errorText = text("", 20f, R.color.gd_rec)
    private val saveButton: ActionButton

    /** 닉네임을 아직 바꿀 수 없는 날짜("12월 4일"). 바꿀 수 있으면 null. */
    private val lockedUntil: String? = initial?.nextChangeAt?.let { next ->
        runCatching { Instant.parse(next) }.getOrNull()?.takeIf { it.isAfter(Instant.now()) }
            ?.atZone(ZoneId.of("Asia/Seoul"))?.let { "${it.monthValue}월 ${it.dayOfMonth}일" }
    }

    val view: View

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // 왼쪽: 내 길동무 미리 보기와 고르기
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(R.color.gd_surface, 28)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        left.addView(preview, LinearLayout.LayoutParams(dp(220), dp(220)))
        val colors = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(MateDrawable.COLOR_COUNT) { i ->
            val swatch = View(context).apply {
                contentDescription = "몸 색 ${i + 1}"
                isClickable = true
                setOnClickListener { bodyIndex = i; refresh() }
            }
            swatches += swatch
            colors.addView(swatch, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) })
        }
        left.addView(colors, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        val items = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        LookItem.entries.forEach { look ->
            val button = ActionButton(context, look.label, ActionButton.NO_ICON, ActionButton.Look.OUTLINE, textSp = 22f).apply {
                onClick { item = look; refresh() }
            }
            itemButtons[look] = button
            items.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72)).apply { marginEnd = dp(10) })
        }
        left.addView(items, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        root.addView(left)

        // 오른쪽: 닉네임
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), 0, 0, 0)
        }
        right.addView(text(if (initial == null) "길 위에서 불릴 이름을 정해 주세요" else "내 길동무 바꾸기", 34f, medium = true))
        right.addView(
            text("닉네임은 내 차에서만 보여요. 한 달에 한 번 바꿀 수 있어요.", 22f, R.color.gd_text_sub),
            margins(top = 8),
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nicknameField.apply {
            setText(initial?.nickname ?: NicknameIdeas.pick())
            textSize = 30f
            setTextColor(context.getColor(R.color.gd_text))
            setHintTextColor(context.getColor(R.color.gd_text_sub))
            hint = "닉네임"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(MAX_RUNES))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(context.getColor(R.color.gd_surface))
                setStroke(dp(2), context.getColor(R.color.gd_outline))
            }
            setPadding(dp(24), 0, dp(24), 0)
            isEnabled = lockedUntil == null
        }
        row.addView(nicknameField, LinearLayout.LayoutParams(0, dp(88), 1f))
        val shuffle = ActionButton(context, "다시 뽑기", ActionButton.NO_ICON, ActionButton.Look.OUTLINE, textSp = 24f).apply {
            onClick { nicknameField.setText(NicknameIdeas.pick()) }
            isEnabled = lockedUntil == null
        }
        row.addView(shuffle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(88)).apply { marginStart = dp(16) })
        right.addView(row, margins(top = 24))
        right.addView(
            text(
                lockedUntil?.let { "닉네임은 ${it}부터 바꿀 수 있어요. 모습은 지금 바꿔도 돼요." }
                    ?: "2~10자 · 한글·영문·숫자 · 거친 말이나 \"운영자\" 같은 이름은 쓸 수 없어요",
                20f, R.color.gd_text_sub,
            ),
            margins(top = 12),
        )
        right.addView(errorText, margins(top = 8))
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        saveButton = ActionButton(context, if (initial == null) "이 이름으로 시작할게요" else "저장할게요", R.drawable.ic_play, ActionButton.Look.GO).apply {
            onClick { save() }
        }
        buttons.addView(saveButton, LinearLayout.LayoutParams(0, dp(104), 1f))
        onCancel?.let { cancel ->
            val back = ActionButton(context, "그대로 둘게요", R.drawable.ic_stop, ActionButton.Look.OUTLINE).apply { onClick { cancel() } }
            buttons.addView(back, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginStart = dp(16) })
        }
        right.addView(buttons, margins(top = 24))
        root.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        view = root
        refresh()
    }

    fun showError(message: String) {
        errorText.text = message
        saveButton.isEnabled = true
    }

    private fun save() {
        val nickname = nicknameField.text.toString().trim().replace(Regex("\\s+"), " ")
        // 서버와 같은 형식 규칙을 먼저 본다. 비속어·30일 규칙은 서버가 본다.
        val problem = when {
            nickname.codePointCount(0, nickname.length) !in MIN_RUNES..MAX_RUNES -> "닉네임은 2~10자로 정해 주세요."
            !PATTERN.matches(nickname) -> "닉네임은 한글·영문·숫자로만 정할 수 있어요."
            else -> null
        }
        if (problem != null) {
            errorText.text = problem
            return
        }
        errorText.text = ""
        saveButton.isEnabled = false
        onSave(nickname, Look(bodyIndex, item))
    }

    private fun refresh() {
        preview.setImageDrawable(MateDrawable(Stage.SPROUT, MateDrawable.bodyColor(bodyIndex), item))
        swatches.forEachIndexed { i, swatch ->
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MateDrawable.bodyColor(i))
                if (i == bodyIndex) setStroke(dp(5), context.getColor(R.color.gd_text))
            }
        }
        itemButtons.forEach { (look, button) -> button.look = if (look == item) ActionButton.Look.SELECTED else ActionButton.Look.OUTLINE }
    }

    private fun text(value: String, size: Float, color: Int = R.color.gd_text, medium: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(context.getColor(color))
        if (medium) typeface = this@ProfileEditor.medium
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(context.getColor(color))
    }

    private fun margins(top: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        private const val MIN_RUNES = 2
        private const val MAX_RUNES = 10
        private val PATTERN = Regex("^[가-힣a-zA-Z0-9]+( [가-힣a-zA-Z0-9]+)*$")
    }
}
