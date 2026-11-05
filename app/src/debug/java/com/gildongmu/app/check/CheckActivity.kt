package com.gildongmu.app.check

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gildongmu.app.EventLog

/**
 * 기획서에 '에뮬레이터 검증 예정'으로 적은 항목을 한 화면에서 점검하는 테스트 화면.
 * 결과는 화면과 Logcat(태그: Gildongmu)에 함께 남는다.
 */
class CheckActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var pleosCheck: PleosCheck
    private lateinit var locationCheck: LocationCheck
    private lateinit var carCheck: CarCheck
    private lateinit var mediaKeyCheck: MediaKeyCheck
    private lateinit var audioCheck: AudioCheck
    private lateinit var speechCheck: SpeechCheck

    private lateinit var pleosText: TextView
    private lateinit var locationText: TextView
    private lateinit var carText: TextView
    private lateinit var backgroundText: TextView
    private lateinit var audioText: TextView
    private lateinit var keyText: TextView
    private lateinit var speechText: TextView
    private lateinit var logText: TextView

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pleosCheck = PleosCheck(this)
        locationCheck = LocationCheck(this)
        carCheck = CarCheck(this)
        mediaKeyCheck = MediaKeyCheck(this)
        audioCheck = AudioCheck(this) { playing -> mediaKeyCheck.setPlaying(playing) }
        speechCheck = SpeechCheck(this)

        setContentView(buildLayout())
        EventLog.add("앱", "확인 화면 시작")
        pleosCheck.start()
        carCheck.connect()
        mediaKeyCheck.start()
        requestNeededPermissions()
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
        audioCheck.stopAll()
        speechCheck.release()
        locationCheck.stop()
        carCheck.disconnect()
        mediaKeyCheck.release()
        pleosCheck.stop()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        mediaKeyCheck.record("화면 키 입력", keyCode)
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Pleos 가이드: 최상위 화면의 뒤로 가기는 finish() 대신 moveTaskToBack(true)
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, permission ->
            val granted = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            EventLog.add("권한", "${permission.substringAfterLast('.')} → ${if (granted) "허용" else "거부"}")
        }
        onPermissionsReady()
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(PERMISSION_CAR_SPEED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (wanted.isEmpty()) {
            onPermissionsReady()
        } else {
            requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun onPermissionsReady() {
        locationCheck.start()
        carCheck.subscribe()
    }

    private fun render() {
        pleosText.text = pleosCheck.summary()
        locationText.text = locationCheck.summary
        carText.text = carCheck.summary()
        backgroundText.text = BackgroundCheckService.summary()
        audioText.text = audioCheck.summary()
        keyText.text = mediaKeyCheck.summary
        speechText.text = speechCheck.summary()
        logText.text = EventLog.recent()
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(48))
        }
        column.addView(text("길동무 에뮬레이터 확인", 30f, bold = true))
        column.addView(text("결과는 Logcat 태그 'Gildongmu'에도 남습니다.", 16f))

        pleosText = section(
            column, "① Pleos SDK · NaviHelper(위치·진행 방향·다음 회전) / Vehicle State(와이퍼·전조등·배터리)",
            "Virtual Console에서 와이퍼·전조등·배터리를 바꾸면 값이 따라 바뀌어야 합니다.",
            "Vehicle 다시 읽기" to { pleosCheck.readVehicle() },
        )
        locationText = section(
            column, "② 안드로이드 표준 위치 (LocationManager)",
            "명령어로 GPS를 넣거나 경로 재생을 켜면 값이 바뀝니다.",
            "위치 받기 시작" to { locationCheck.start() },
        )
        carText = section(
            column, "③ 차량 속도·기어·주행 제한 (AAOS Car API)",
            "Virtual Console에서 기어를 D로 바꾸면 기어와 주행 제한이 바뀌어야 합니다.",
        )
        backgroundText = section(
            column, "④ 창을 닫은 뒤 백그라운드 동작",
            "시작 → 홈 버튼으로 앱을 닫고 1분 뒤 돌아와 하트비트 횟수가 늘었는지 봅니다.",
            "백그라운드 시작" to { BackgroundCheckService.start(this) },
            "중지" to { BackgroundCheckService.stop(this) },
        )
        audioText = section(
            column, "⑤ 음악·내비 음성과 함께 재생 (오디오 포커스)",
            "가짜 음악을 켠 상태에서 한마디를 재생하고, 한마디 재생 중에 가짜 내비 안내를 눌러 봅니다.",
            "가짜 음악" to { audioCheck.music.play() },
            "한마디(MEDIA)" to { audioCheck.messageAsMedia.play() },
            "한마디(ASSISTANT)" to { audioCheck.messageAsAssistant.play() },
            "가짜 내비 안내" to { audioCheck.navigation.play() },
            "모두 정지" to { audioCheck.stopAll() },
        )
        keyText = section(
            column, "⑥ 핸들·미디어 버튼",
            "가짜 음악 재생 중에 핸들의 다음 곡·재생 버튼을 누르면 여기에 표시됩니다.",
        )
        speechText = section(
            column, "⑦ 말로 대답하기 (Pleos 음성 인식)",
            "마이크 대신 files/stt/*.pcm(16kHz·16비트·모노)을 엔진에 넣어, 들은 말과 맞춘 대답을 봅니다.",
            "파일로 알아듣기" to { speechCheck.runFiles() },
        )
        column.addView(text("최근 기록", 22f, bold = true))
        logText = text("", 15f)
        column.addView(logText)

        return ScrollView(this).apply { addView(column) }
    }

    private fun section(parent: LinearLayout, heading: String, hint: String, vararg actions: Pair<String, () -> Unit>): TextView {
        parent.addView(text(heading, 22f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) })
        parent.addView(text(hint, 15f))
        if (actions.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.forEach { (label, action) ->
                row.addView(Button(this).apply {
                    text = label
                    textSize = 18f
                    isAllCaps = false
                    setOnClickListener { action() }
                })
            }
            parent.addView(row)
        }
        return text("-", 19f).also { parent.addView(it) }
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 1
        private const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"
    }
}
