package com.gildongmu.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gildongmu.app.data.DeviceId
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Settings
import com.gildongmu.app.drive.DriveTracker
import com.gildongmu.app.drive.DrivingMode
import com.gildongmu.app.drive.VehicleSignals
import com.gildongmu.app.guide.GuideController
import com.gildongmu.app.guide.SteeringKeys
import com.gildongmu.app.record.MyMessages
import com.gildongmu.app.record.RecordController

/**
 * 길동무 본 화면 (MVP 1단계 듣기 + 2단계 남기기 + 3단계 주행/정차 화면).
 * - 주행 중: 안내 한 줄, 단계 아이콘, 큰 버튼만. 글자 입력·애니메이션·설정은 없다 (CarUxRestrictions).
 * - 정차 중: 위에 더해 내 꽃밭(꽃 소식, 내 한마디), 설정, 상태와 기록을 보여준다.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var settings: Settings
    private lateinit var guide: GuideController
    private lateinit var tracker: DriveTracker
    private lateinit var drivingMode: DrivingMode
    private lateinit var vehicle: VehicleSignals
    private lateinit var record: RecordController
    private lateinit var myMessages: MyMessages

    private lateinit var modeText: TextView
    private lateinit var stageIcon: ImageView
    private lateinit var guideText: TextView
    private lateinit var listenButton: Button
    private lateinit var skipButton: Button
    private lateinit var thanksButton: Button
    private lateinit var recordText: TextView
    private lateinit var recordButton: Button
    private lateinit var aheadText: TextView
    private lateinit var parkedOnly: LinearLayout
    private lateinit var bloomText: TextView
    private lateinit var gardenRows: LinearLayout
    private lateinit var filterButton: Button
    private lateinit var driveText: TextView
    private lateinit var serverText: TextView
    private lateinit var logText: TextView

    private var filterDialog: AlertDialog? = null
    /** 내 꽃밭 줄을 다시 그릴지 정하는 값. 0.5초마다 render()가 돌아서 바뀔 때만 다시 만든다. */
    private var gardenKey = ""

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
        modeText.text = if (driving) "주행 중 · 소리로 안내해요" else "정차 중 · 내 꽃밭과 설정을 볼 수 있어요"

        val stage = guide.shownStage()
        stageIcon.visibility = if (stage == null) View.INVISIBLE else View.VISIBLE
        stage?.let { stageIcon.setImageResource(StageIcon.of(it)) }
        guideText.text = guide.statusText()

        val phase = guide.phase
        listenButton.isEnabled = phase == GuideController.Phase.PROMPTING
        skipButton.isEnabled = phase != GuideController.Phase.IDLE
        thanksButton.isEnabled = guide.lastHeard != null || phase == GuideController.Phase.PLAYING
        thanksButton.text = guide.lastThanks?.let { "고마워요 ${it.thanksCount}" } ?: "고마워요"

        // 듣는 중에는 녹음하지 않는다. 녹음 중에는 끝내기 버튼으로 쓴다.
        val recording = record.phase == RecordController.Phase.RECORDING
        recordText.text = record.statusText()
        recordButton.text = if (recording) "녹음 끝내기" else "한마디 남기기"
        recordButton.isEnabled = recording ||
            (record.phase == RecordController.Phase.IDLE && phase == GuideController.Phase.IDLE)

        aheadText.text = guide.aheadSummary(brief = driving)

        parkedOnly.visibility = if (driving) View.GONE else View.VISIBLE
        if (driving) return
        bloomText.text = listOfNotNull(myMessages.news, MyMessages.bloomSummary(myMessages.items))
            .joinToString("\n")
            .ifEmpty { "아직 남긴 한마디가 없어요. 운전하다 떠오른 한마디를 남겨 보세요." }
        renderGarden()
        filterButton.text = if (settings.filterOn) "비속어 필터 켜짐 · 끄기" else "비속어 필터 꺼짐 · 켜기"
        driveText.text = tracker.latest?.let(DriveTracker::describe) ?: "GPS 위치를 기다리는 중"
        serverText.text = guide.serverStatus
        logText.text = EventLog.recent()
    }

    private fun renderGarden() {
        val items = myMessages.items.take(GARDEN_ROWS)
        val key = items.joinToString { "${it.id}:${it.stage}:${it.review}:${it.listenCount}:${it.thanksCount}" }
        if (key == gardenKey) return
        gardenKey = key
        gardenRows.removeAllViews()
        items.forEach { m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(ImageView(this).apply { setImageResource(StageIcon.of(m.stage)) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            row.addView(text(myMessages.lineOf(m), 17f).apply { setPadding(dp(16), 0, 0, 0) })
            gardenRows.addView(row)
        }
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(48))
        }
        column.addView(text("길동무", 34f, bold = true))
        modeText = text("", 18f).also { column.addView(it) }

        // 안내: 단계 아이콘 + 안내 문장
        column.addView(heading("안내"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        stageIcon = ImageView(this).also { card.addView(it, LinearLayout.LayoutParams(dp(96), dp(96))) }
        guideText = text("-", 24f).apply { setPadding(dp(16), 0, 0, 0) }.also { card.addView(it) }
        column.addView(card)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenButton = button(row, "듣기") { guide.accept() }
        skipButton = button(row, "넘기기") { guide.skip() }
        thanksButton = button(row, "고마워요") { guide.thanks() }
        column.addView(row)

        column.addView(heading("남기기"))
        recordText = text("-", 20f).also { column.addView(it) }
        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordButton = button(recordRow, "한마디 남기기") { onRecordPressed() }.apply { minimumWidth = dp(360) }
        column.addView(recordRow)

        column.addView(heading("앞쪽 길"))
        aheadText = text("-", 20f).also { column.addView(it) }

        // 정차 중에만 보이는 부분
        parkedOnly = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parkedOnly.addView(heading("내 꽃밭"))
        bloomText = text("-", 19f).also { parkedOnly.addView(it) }
        gardenRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { parkedOnly.addView(it) }
        parkedOnly.addView(heading("설정"))
        val settingsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filterButton = button(settingsRow, "비속어 필터") { onFilterPressed() }.apply { minimumWidth = dp(360) }
        parkedOnly.addView(settingsRow)
        parkedOnly.addView(heading("지금"))
        driveText = text("-", 18f).also { parkedOnly.addView(it) }
        parkedOnly.addView(heading("서버"))
        serverText = text("-", 16f).also { parkedOnly.addView(it) }
        parkedOnly.addView(heading("최근 기록"))
        logText = text("", 14f).also { parkedOnly.addView(it) }
        column.addView(parkedOnly)

        return ScrollView(this).apply { addView(column) }
    }

    private fun heading(value: String) = text(value, 22f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        isAllCaps = false
        minimumWidth = dp(180)
        setOnClickListener { action() }
        parent.addView(this)
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 1
        private const val REQUEST_RECORD = 2
        private const val GARDEN_ROWS = 8
    }
}
