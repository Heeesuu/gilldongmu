package com.gildongmu.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gildongmu.app.data.DeviceId
import com.gildongmu.app.data.MyMessage
import com.gildongmu.app.data.Review
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Settings
import com.gildongmu.app.drive.DriveTracker
import com.gildongmu.app.drive.DrivingMode
import com.gildongmu.app.drive.VehicleSignals
import com.gildongmu.app.guide.GuideController
import com.gildongmu.app.guide.SteeringKeys
import com.gildongmu.app.record.MyMessages
import com.gildongmu.app.record.RecordController
import com.gildongmu.app.ui.ActionButton
import com.gildongmu.app.ui.ActionButton.Look
import com.gildongmu.app.ui.RoadStripView
import kotlin.math.roundToInt

/**
 * 길동무 본 화면. 디자인은 B안 "길 띠": 왼쪽 띠에 앞쪽 길 위 한마디를 놓고, 오른쪽에 안내 카드와 남기기 버튼을 둔다.
 * - 주행 중: 길 띠, 안내 한 줄, 그때 필요한 큰 버튼만. 글자 입력·애니메이션·설정은 없다 (CarUxRestrictions).
 * - 정차 중: 위에 더해 탭으로 내 꽃밭과 설정을 볼 수 있다.
 * - 낮·밤 색은 차 설정을 따르고(values-night), 창이 세로로 길면(화면 나누기) 띠를 위로 눕힌다.
 */
class MainActivity : Activity() {

    private enum class Tab { ROAD, GARDEN, SETTINGS }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var settings: Settings
    private lateinit var guide: GuideController
    private lateinit var tracker: DriveTracker
    private lateinit var drivingMode: DrivingMode
    private lateinit var vehicle: VehicleSignals
    private lateinit var record: RecordController
    private lateinit var myMessages: MyMessages

    private lateinit var modeText: TextView
    private lateinit var tabBar: LinearLayout
    private lateinit var tabButtons: Map<Tab, ActionButton>
    private lateinit var pages: Map<Tab, View>

    // 길 탭
    private lateinit var strip: RoadStripView
    private lateinit var stageIcon: ImageView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var actions: LinearLayout
    private lateinit var listenButton: ActionButton
    private lateinit var skipButton: ActionButton
    private lateinit var thanksButton: ActionButton
    private lateinit var recordText: TextView
    private lateinit var recordButton: ActionButton

    // 내 꽃밭 탭
    private lateinit var newsText: TextView
    private lateinit var bloomText: TextView
    private lateinit var gardenGrid: LinearLayout

    // 설정 탭
    private lateinit var filterButton: ActionButton
    private var driveText: TextView? = null
    private var serverText: TextView? = null
    private var logText: TextView? = null

    private var tab = Tab.ROAD
    private var filterDialog: AlertDialog? = null
    /** 내 꽃밭을 다시 그릴지 정하는 값. 0.5초마다 render()가 돌아서 바뀔 때만 다시 만든다. */
    private var gardenKey = ""
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val api = ServerApi(BuildConfig.SERVER_URL, DeviceId.get(this), BuildConfig.VERSION_NAME)
        guide = GuideController(
            this, api,
            // 녹음하는 동안에는 "들을까요?"를 묻지 않는다.
            canPrompt = { record.phase == RecordController.Phase.IDLE },
            filterOn = { settings.filterOn },
        )
        tracker = DriveTracker(this) {
            guide.onDrive(it)
            record.onDrive(it)
        }
        drivingMode = DrivingMode(this) { onDrivingChanged(it) }
        vehicle = VehicleSignals(this)
        myMessages = MyMessages(api)
        record = RecordController(this, api, vehicle, currentDrive = { tracker.latest }) { id -> myMessages.track(id) }

        setContentView(buildLayout())
        EventLog.add("앱", "길동무 시작 · 서버 ${BuildConfig.SERVER_URL.ifEmpty { "(미설정)" }}")
        drivingMode.connect()
        vehicle.start()
        myMessages.start()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            tracker.start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
        }
    }

    /** 화면 나누기·낮밤 전환: 듣기·녹음은 그대로 두고 색과 배치만 새 설정으로 다시 만든다. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(buildLayout())
        render()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        filterDialog?.dismiss()
        record.release()
        myMessages.stop()
        vehicle.stop()
        drivingMode.disconnect()
        tracker.stop()
        guide.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = SteeringKeys.keyOf(keyCode) ?: return super.onKeyDown(keyCode, event)
        guide.onKey(key)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Pleos 가이드: 최상위 화면의 뒤로 가기는 finish() 대신 moveTaskToBack(true)
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_LOCATION -> if (!tracker.start()) EventLog.add("권한", "위치 권한이 있어야 앞쪽 한마디를 찾을 수 있어요")
            // 남기기 버튼을 눌러서 권한을 물었으므로, 허용되면 바로 녹음을 시작한다.
            REQUEST_RECORD -> if (granted) record.toggle() else EventLog.add("권한", "마이크 권한이 있어야 한마디를 남길 수 있어요")
        }
    }

    private fun onRecordPressed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            record.toggle()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD)
        }
    }

    private fun onDrivingChanged(driving: Boolean) {
        // 주행 중에는 설정을 열 수 없다. 정차 중에 띄운 확인 창도 닫는다.
        if (driving) filterDialog?.dismiss()
        render()
    }

    /** 켜기는 바로, 끄기는 정차 중에 한 번 더 묻고 한다. */
    private fun onFilterPressed() {
        if (!settings.filterOn) {
            setFilter(true)
            return
        }
        if (drivingMode.driving) return
        filterDialog = AlertDialog.Builder(this)
            .setTitle("비속어 필터를 끌까요?")
            .setMessage("끄면 거친 말이 섞인 한마디도 들려요. 아이와 함께 탔다면 켜 두세요.")
            .setPositiveButton("끄기") { _, _ -> setFilter(false) }
            .setNegativeButton("그대로 두기", null)
            .show()
    }

    private fun setFilter(on: Boolean) {
        settings.filterOn = on
        guide.refreshSoon()
        EventLog.add("설정", "비속어 필터 ${if (on) "켜짐" else "꺼짐"}")
        render()
    }

    private fun render() {
        val driving = drivingMode.driving
        // 주행 중에는 길 탭만 보여준다.
        if (driving) tab = Tab.ROAD
        modeText.update(if (driving) "주행 중" else "정차 중")
        tabBar.visibility = if (driving) View.GONE else View.VISIBLE
        tabButtons.forEach { (t, button) -> button.look = if (t == tab) Look.SELECTED else Look.OUTLINE }
        pages.forEach { (t, page) -> page.visibility = if (t == tab) View.VISIBLE else View.GONE }
        when (tab) {
            Tab.ROAD -> renderRoad()
            Tab.GARDEN -> renderGarden()
            Tab.SETTINGS -> renderSettings()
        }
    }

    private fun renderRoad() {
        val status = guide.status()
        titleText.update(status.title)
        detailText.update(status.detail)
        detailText.visibility = if (status.detail.isEmpty()) View.GONE else View.VISIBLE
        val stage = guide.shownStage()
        stageIcon.visibility = if (stage == null) View.GONE else View.VISIBLE
        stage?.let { stageIcon.setImageResource(StageIcon.of(it)) }

        strip.pins = guide.shownAhead().map {
            RoadStripView.Pin(it.message.id, (it.distanceM / 10).roundToInt() * 10, it.message.stage, it.message.id == guide.currentId)
        }

        // 그때 할 수 있는 것만 버튼으로 보여준다.
        val phase = guide.phase
        listenButton.visibility = if (phase == GuideController.Phase.PROMPTING) View.VISIBLE else View.GONE
        skipButton.visibility = if (phase == GuideController.Phase.IDLE) View.GONE else View.VISIBLE
        skipButton.setLabel(if (phase == GuideController.Phase.PROMPTING) "넘기기" else "그만 듣기")
        val canThank = guide.lastHeard != null || phase == GuideController.Phase.PLAYING
        thanksButton.visibility = if (canThank) View.VISIBLE else View.GONE
        thanksButton.setLabel(guide.lastThanks?.let { "고마워요 ${it.thanksCount}" } ?: "고마워요")
        actions.visibility = if (listOf(listenButton, skipButton, thanksButton).any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

        // 듣는 중에는 녹음하지 않는다. 녹음 중에는 끝내기 버튼으로 쓴다.
        val recording = record.phase == RecordController.Phase.RECORDING
        recordText.update(record.statusText())
        recordButton.look = if (recording) Look.REC else Look.SEED
        recordButton.setLabel(if (recording) "녹음 끝내기" else "한마디 남기기")
        recordButton.setIcon(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
        recordButton.isEnabled = recording ||
            (record.phase == RecordController.Phase.IDLE && phase == GuideController.Phase.IDLE)
    }

    private fun renderGarden() {
        val news = myMessages.news
        newsText.update(news.orEmpty())
        newsText.visibility = if (news == null) View.GONE else View.VISIBLE
        bloomText.update(MyMessages.bloomSummary(myMessages.items) ?: "아직 남긴 한마디가 없어요. 운전하다 떠오른 한마디를 남겨 보세요.")

        val items = myMessages.items.take(GARDEN_TILES)
        val key = items.joinToString { "${it.id}:${it.stage}:${it.review}:${it.listenCount}:${it.thanksCount}" }
        if (key == gardenKey) return
        gardenKey = key
        gardenGrid.removeAllViews()
        val perRow = if (isWide()) 4 else 2
        items.chunked(perRow).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(gardenTile(it)) }
            // 마지막 줄이 덜 차도 칸 너비를 맞춘다.
            repeat(perRow - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
            gardenGrid.addView(row)
        }
    }

    private fun renderSettings() {
        filterButton.setLabel(if (settings.filterOn) "켜짐 · 끄기" else "꺼짐 · 켜기")
        driveText?.update(tracker.latest?.let(DriveTracker::describe) ?: "GPS 위치를 기다리는 중")
        serverText?.update(guide.serverStatus)
        logText?.update(EventLog.recent())
    }

    private fun buildLayout(): View {
        gardenKey = ""
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.gd_bg)))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.gd_bg))
            setPadding(dp(32), dp(20), dp(32), dp(24))
        }

        // 머리줄: 앱 이름과 주행·정차
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("길동무", 30f, medium = true))
        header.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        modeText = text("", 22f, R.color.gd_text_sub).also { header.addView(it) }
        root.addView(header)

        // 탭: 정차 중에만
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabButtons = linkedMapOf(
            Tab.ROAD to ActionButton(this, "길", R.drawable.ic_route, Look.SELECTED, textSp = 24f),
            Tab.GARDEN to ActionButton(this, "내 꽃밭", R.drawable.ic_florist, Look.OUTLINE, textSp = 24f),
            Tab.SETTINGS to ActionButton(this, "설정", R.drawable.ic_settings, Look.OUTLINE, textSp = 24f),
        )
        tabButtons.forEach { (t, button) ->
            button.onClick { tab = t; render() }
            tabBar.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(72)).apply { marginEnd = dp(12) })
        }
        root.addView(tabBar, margins(top = 16))

        val content = FrameLayout(this)
        pages = linkedMapOf(
            Tab.ROAD to buildRoadPage(),
            Tab.GARDEN to buildGardenPage(),
            Tab.SETTINGS to buildSettingsPage(),
        )
        pages.values.forEach { content.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(20) })
        return root
    }

    private fun buildRoadPage(): View {
        val wide = isWide()
        strip = RoadStripView(this)

        // 안내 카드: 단계 아이콘 + 큰 글자 한 줄 + 작은 글자 한 줄, 아래에 그때 필요한 버튼
        // 내용은 카드 가운데에 모은다. 버튼이 없는 때(조용한 때)에도 카드가 비어 보이지 않게 하려는 것이다.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(R.color.gd_surface, 28)
            setPadding(dp(36), dp(32), dp(36), dp(32))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stageIcon = ImageView(this).also { top.addView(it, LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(24) }) }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleText = text("", 36f, medium = true, maxLines = 2).also { words.addView(it) }
        detailText = text("", 22f, R.color.gd_text_sub, maxLines = 2).also { words.addView(it, margins(top = 8)) }
        top.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenButton = ActionButton(this, "듣기", R.drawable.ic_play, Look.GO).apply { onClick { guide.accept() } }
        skipButton = ActionButton(this, "넘기기", R.drawable.ic_skip, Look.OUTLINE).apply { onClick { guide.skip() } }
        thanksButton = ActionButton(this, "고마워요", R.drawable.ic_favorite, Look.OUTLINE).apply { onClick { guide.thanks() } }
        listOf(listenButton, skipButton, thanksButton).forEachIndexed { i, button ->
            actions.addView(button, LinearLayout.LayoutParams(0, dp(104), 1f).apply { if (i > 0) marginStart = dp(16) })
        }
        card.addView(actions, margins(top = 40))

        // 남기기: 상태 한 줄 + 가장 큰 버튼
        val recordArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recordText = text("", 22f, R.color.gd_text_sub, maxLines = 2).also { recordArea.addView(it) }
        recordButton = ActionButton(this, "한마디 남기기", R.drawable.ic_mic, Look.SEED, textSp = 30f).apply { onClick { onRecordPressed() } }
        recordArea.addView(recordButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(112)).apply { topMargin = dp(12) })

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(recordArea, margins(top = 20))

        // 가로로 넓으면 띠를 왼쪽에 세우고, 세로로 길면(화면 나누기) 위에 눕힌다.
        return LinearLayout(this).apply {
            if (wide) {
                orientation = LinearLayout.HORIZONTAL
                addView(strip, LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT))
                addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(24) })
            } else {
                orientation = LinearLayout.VERTICAL
                addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)))
                addView(side, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
            }
        }
    }

    private fun buildGardenPage(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        newsText = text("", 28f, medium = true).also { column.addView(it, margins(bottom = 8)) }
        bloomText = text("", 22f, R.color.gd_text_sub).also { column.addView(it) }
        gardenGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { column.addView(it, margins(top = 16)) }
        return ScrollView(this).apply { addView(column) }
    }

    private fun gardenTile(m: MyMessage) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(R.color.gd_surface, 24)
        setPadding(dp(20), dp(24), dp(20), dp(24))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        addView(ImageView(this@MainActivity).apply { setImageResource(StageIcon.of(m.stage)) }, LinearLayout.LayoutParams(dp(80), dp(80)))
        addView(text("${m.kind.label} · ${m.stage.label}", 24f, medium = true, maxLines = 1).apply { gravity = Gravity.CENTER }, margins(top = 12))
        addView(text("들은 ${m.listenCount}명 · 고마워요 ${m.thanksCount}", 20f, R.color.gd_text_sub, maxLines = 1).apply { gravity = Gravity.CENTER }, margins(top = 4))
        if (m.review != Review.CLEAN) {
            addView(text(m.review.label, 20f, R.color.gd_text_sub, maxLines = 1).apply { gravity = Gravity.CENTER })
        }
    }

    private fun buildSettingsPage(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        filterButton = ActionButton(this, "켜짐 · 끄기", R.drawable.ic_settings, Look.OUTLINE, textSp = 24f).apply { onClick { onFilterPressed() } }
        column.addView(settingCard("비속어 필터", "켜 두면 거친 말이 섞인 한마디는 들려주지 않아요.", filterButton))
        column.addView(settingCard("오픈소스 라이선스", "아이콘: Material Symbols (Apache License 2.0)\n글꼴: 차에 들어 있는 Asta Sans", null), margins(top = 16))

        // 개발용 상태는 디버그 빌드에만 보여준다. 심사·시연용 릴리스 화면에는 없다.
        if (BuildConfig.DEBUG) {
            val debug = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(R.color.gd_surface, 24)
                setPadding(dp(28), dp(24), dp(28), dp(24))
            }
            debug.addView(text("개발용 상태 (디버그 빌드에만 보여요)", 24f, medium = true))
            driveText = text("", 20f, R.color.gd_text_sub).also { debug.addView(it, margins(top = 8)) }
            serverText = text("", 20f, R.color.gd_text_sub).also { debug.addView(it, margins(top = 4)) }
            logText = text("", 16f, R.color.gd_text_sub, maxLines = 40).also { debug.addView(it, margins(top = 12)) }
            column.addView(debug, margins(top = 16))
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun settingCard(title: String, description: String, action: View?) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(R.color.gd_surface, 24)
        setPadding(dp(28), dp(24), dp(28), dp(24))
        val words = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(title, 26f, medium = true))
        words.addView(text(description, 20f, R.color.gd_text_sub), margins(top = 6))
        addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(88)).apply { marginStart = dp(24) }) }
    }

    /** 창이 가로로 넓은가. 화면 나누기로 세로로 길어지면 false. */
    private fun isWide() = resources.configuration.let { it.screenWidthDp >= it.screenHeightDp }

    private fun text(value: String, size: Float, color: Int = R.color.gd_text, medium: Boolean = false, maxLines: Int = 3) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(color))
            if (medium) typeface = this@MainActivity.medium
            this.maxLines = maxLines
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    /** 0.5초마다 같은 글자를 다시 넣어 화면을 다시 재지 않도록, 바뀔 때만 넣는다. */
    private fun TextView.update(value: String) {
        if (text.toString() != value) text = value
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(getColor(color))
    }

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 1
        private const val REQUEST_RECORD = 2
        private const val GARDEN_TILES = 12
    }
}
