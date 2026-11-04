package com.gildongmu.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gildongmu.app.data.DeviceId
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.drive.DriveTracker
import com.gildongmu.app.guide.GuideController
import com.gildongmu.app.guide.SteeringKeys

/**
 * 길동무 본 화면 (MVP 1단계: 듣기).
 * 주행 중에도 띄울 수 있도록(DO) 글자 입력과 애니메이션 없이 큰 글씨와 버튼만 쓴다.
 * 녹음·꽃 아이콘·주행/정차 화면 전환은 다음 단계에서 붙인다.
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var guide: GuideController
    private lateinit var tracker: DriveTracker

    private lateinit var driveText: TextView
    private lateinit var aheadText: TextView
    private lateinit var guideText: TextView
    private lateinit var serverText: TextView
    private lateinit var logText: TextView
    private lateinit var listenButton: Button
    private lateinit var skipButton: Button
    private lateinit var thanksButton: Button

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = ServerApi(BuildConfig.SERVER_URL, DeviceId.get(this), BuildConfig.VERSION_NAME)
        guide = GuideController(this, api)
        tracker = DriveTracker(this) { guide.onDrive(it) }

        setContentView(buildLayout())
        EventLog.add("앱", "길동무 시작 · 서버 ${BuildConfig.SERVER_URL.ifEmpty { "(미설정)" }}")
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
        if (requestCode == REQUEST_LOCATION && !tracker.start()) {
            EventLog.add("권한", "위치 권한이 있어야 앞쪽 한마디를 찾을 수 있어요")
        }
    }

    private fun render() {
        driveText.text = tracker.latest?.let(DriveTracker::describe) ?: "GPS 위치를 기다리는 중"
        aheadText.text = guide.aheadSummary()
        guideText.text = guide.statusText()
        serverText.text = guide.serverStatus
        logText.text = EventLog.recent()

        val phase = guide.phase
        listenButton.isEnabled = phase == GuideController.Phase.PROMPTING
        skipButton.isEnabled = phase != GuideController.Phase.IDLE
        thanksButton.isEnabled = guide.lastHeard != null || phase == GuideController.Phase.PLAYING
        thanksButton.text = guide.lastThanks?.let { "고마워요 ${it.thanksCount} · ${it.stage.label}" } ?: "고마워요"
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(48))
        }
        column.addView(text("길동무", 34f, bold = true))
        column.addView(text("길 위의 방명록 · 같은 길을 가는 사람이 남긴 한마디를 들려줘요", 17f))

        guideText = section(column, "안내", 24f)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenButton = button(row, "듣기") { guide.accept() }
        skipButton = button(row, "넘기기") { guide.skip() }
        thanksButton = button(row, "고마워요") { guide.thanks() }
        column.addView(row)

        aheadText = section(column, "앞쪽 길", 20f)
        driveText = section(column, "지금", 18f)
        serverText = section(column, "서버", 16f)
        column.addView(text("최근 기록", 20f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) })
        logText = text("", 14f)
        column.addView(logText)

        return ScrollView(this).apply { addView(column) }
    }

    private fun section(parent: LinearLayout, heading: String, size: Float): TextView {
        parent.addView(text(heading, 22f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) })
        return text("-", size).also { parent.addView(it) }
    }

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
    }
}
