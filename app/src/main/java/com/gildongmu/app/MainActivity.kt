package com.gildongmu.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.gildongmu.app.data.Kind
import com.gildongmu.app.data.Look
import com.gildongmu.app.data.Profile
import com.gildongmu.app.data.MyMessage
import com.gildongmu.app.data.Review
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Settings
import com.gildongmu.app.data.Stage
import com.gildongmu.app.drive.DriveTracker
import com.gildongmu.app.drive.DrivingMode
import com.gildongmu.app.guide.GuideController
import com.gildongmu.app.guide.SteeringKeys
import com.gildongmu.app.record.MyMessages
import com.gildongmu.app.record.RecordController
import com.gildongmu.app.ui.ActionButton
import com.gildongmu.app.ui.ActionButton.Look as ButtonLook
import com.gildongmu.app.ui.ContextWords
import com.gildongmu.app.ui.MateDrawable
import com.gildongmu.app.ui.ProfileEditor
import com.gildongmu.app.ui.RoadStripView
import kotlin.math.roundToInt

/**
 * 길동무 본 화면. 디자인은 B안 "길 띠": 왼쪽 띠에 앞쪽 길 위 한마디를 놓고, 오른쪽에 안내 카드와 남기기 버튼을 둔다.
 * - 주행 중: 길 띠, 안내 한 줄, 그때 필요한 큰 버튼만. 글자 입력·애니메이션·설정은 없다 (CarUxRestrictions).
 * - 정차 중: 위에 더해 탭으로 내 발자취와 설정을 볼 수 있다. 처음 실행하면 내 길동무(닉네임·모습)를 정한다.
 * - 빈 곳은 Pleos 기본 앱처럼 정보로 채운다: 카드의 작은 표시(언제·비·반응), "이번 길" 타일, 남기기 옆 내 길동무.
 * - 낮·밤 색은 차 설정을 따르고(values-night), 창이 세로로 길면(화면 나누기) 띠를 위로 눕힌다.
 */
class MainActivity : Activity() {

    private enum class Tab { ROAD, GARDEN, SETTINGS }

    private val ui = Handler(Looper.getMainLooper())

    // 안내·녹음·위치는 화면 밖에서도 도는 Core에 있다(포그라운드 서비스가 살려 둔다). 화면은 읽어 그리기만 한다.
    private lateinit var core: Core
    private val settings: Settings get() = core.settings
    private val api: ServerApi get() = core.api
    private val guide: GuideController get() = core.guide
    private val tracker: DriveTracker get() = core.tracker
    private val drivingMode: DrivingMode get() = core.drivingMode
    private val record: RecordController get() = core.record
    private val myMessages: MyMessages get() = core.myMessages

    private lateinit var modeText: TextView
    private lateinit var headerMate: ImageView
    private lateinit var headerName: TextView
    private lateinit var profilePage: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabButtons: Map<Tab, ActionButton>
    private lateinit var pages: Map<Tab, View>

    // 길 탭
    private lateinit var strip: RoadStripView
    private lateinit var stageIcon: ImageView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var orderText: TextView
    private lateinit var metText: TextView
    private lateinit var thankedText: TextView
    private lateinit var nextText: TextView
    private lateinit var myMate: ImageView
    private lateinit var myName: TextView
    private lateinit var myStat: TextView
    private lateinit var actions: LinearLayout
    private lateinit var listenButton: ActionButton
    private lateinit var skipButton: ActionButton
    private lateinit var stopButton: ActionButton
    private lateinit var thanksButton: ActionButton
    private lateinit var recordText: TextView
    private lateinit var recordButton: ActionButton

    // 내 꽃밭 탭
    private lateinit var newsText: TextView
    private lateinit var bloomText: TextView
    private lateinit var gardenGrid: LinearLayout

    // 설정 탭
    private lateinit var settingsMate: ImageView
    private lateinit var settingsName: TextView
    private lateinit var filterButton: ActionButton
    private lateinit var voiceButton: ActionButton
    private var driveText: TextView? = null
    private var serverText: TextView? = null
    private var logText: TextView? = null

    private var tab = Tab.ROAD
    /** 내 길동무. 내 기기에서만 보인다. 서버에 저장하고 Settings에도 기억해 둔다. */
    private var myProfile: Profile? = null
    private var editor: ProfileEditor? = null
    /** null이면 편집 중이 아님. true면 처음 실행이라 취소 없이 정해야 한다. */
    private var editingFirstRun: Boolean? = null
    private var chipsKey = ""
    private var myMateKey = ""
    private var shownMates: List<GuideController.Shown> = emptyList()
    private var filterDialog: AlertDialog? = null
    /** 내 꽃밭을 다시 그릴지 정하는 값. 0.5초마다 render()가 돌아서 바뀔 때만 다시 만든다. */
    private var gardenKey = ""
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)

    private val drivingListener: (Boolean) -> Unit = { onDrivingChanged(it) }
    private val changedListener: () -> Unit = { render() }
    private val releasedListener: () -> Unit = { finish() }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        core = (application as GildongmuApp).core()
        myProfile = settings.profile
        core.onDrivingChanged = drivingListener
        core.onChanged = changedListener
        // 알림의 "끄기"로 안내를 끄면 화면도 닫는다.
        core.onReleased = releasedListener

        setContentView(buildLayout())
        // 화면이 보일 때 시작해야 화면 밖에서도 위치를 받는다(GuideService 설명 참고).
        GuideService.start(this)
        // 위치·알림과, 말로 대답하기가 켜져 있으면 마이크도 처음에 한 번에 묻는다.
        val missing = listOfNotNull(
            Manifest.permission.ACCESS_FINE_LOCATION.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            Manifest.permission.ACCESS_COARSE_LOCATION.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            Manifest.permission.POST_NOTIFICATIONS.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            Manifest.permission.RECORD_AUDIO.takeIf { settings.voiceAnswer && !hasMic() },
        )
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_START)
        loadProfile()
    }

    /** 서버에서 내 길동무를 읽는다. 아직 없으면 처음 실행 화면을 띄운다(정차 중에만 보인다). */
    private fun loadProfile() {
        if (!api.configured) return
        api.profile { result ->
            result.onSuccess { profile ->
                if (profile != null) {
                    settings.profile = profile
                    myProfile = profile
                } else if (myProfile == null) {
                    openEditor(firstRun = true)
                }
            }.onFailure {
                EventLog.add("내 길동무", "서버에서 읽지 못했어요: ${it.message}")
                if (myProfile == null) openEditor(firstRun = true)
            }
            render()
        }
    }

    private fun openEditor(firstRun: Boolean) {
        editingFirstRun = firstRun
        val e = ProfileEditor(
            this,
            if (firstRun) null else myProfile,
            onSave = { nickname, look -> saveProfile(nickname, look) },
            onCancel = if (firstRun) null else ({ closeEditor() }),
        )
        editor = e
        profilePage.removeAllViews()
        profilePage.addView(ScrollView(this).apply { addView(e.view) })
        render()
    }

    private fun saveProfile(nickname: String, look: Look) {
        api.saveProfile(nickname, look) { result ->
            result.onSuccess {
                settings.profile = it
                myProfile = it
                EventLog.add("내 길동무", "${it.nickname} · 저장했어요")
                closeEditor()
            }.onFailure { editor?.showError(it.message ?: "저장하지 못했어요. 잠시 뒤에 다시 해 주세요.") }
        }
    }

    private fun closeEditor() {
        editingFirstRun = null
        editor = null
        profilePage.removeAllViews()
        hideKeyboard()
        myMateKey = ""
        render()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    /** 화면 나누기·낮밤 전환: 듣기·녹음은 그대로 두고 색과 배치만 새 설정으로 다시 만든다. 편집 중이면 편집 화면도 다시 연다. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(buildLayout())
        editingFirstRun?.let { openEditor(it) }
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

    /** 화면만 닫는다. 안내는 Core(포그라운드 서비스)에서 계속 돈다. 끄려면 알림의 "끄기". */
    override fun onDestroy() {
        filterDialog?.dismiss()
        // 새 화면이 먼저 등록했을 수 있으므로, 내가 등록한 것만 지운다.
        if (core.onChanged === changedListener) {
            core.onDrivingChanged = null
            core.onChanged = null
            core.onReleased = null
        }
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
            REQUEST_START -> {
                // 새로 받은 권한에 맞춰 서비스 종류를 다시 맞추고 위치를 받기 시작한다.
                GuideService.start(this)
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    EventLog.add("권한", "위치 권한이 있어야 앞쪽 한마디를 찾을 수 있어요")
                }
                if (settings.voiceAnswer && !hasMic()) EventLog.add("권한", "마이크 권한이 없으면 버튼으로만 대답할 수 있어요")
            }
            // 남기기 버튼을 눌러서 권한을 물었으므로, 허용되면 바로 녹음을 시작한다.
            REQUEST_RECORD -> if (granted) {
                GuideService.start(this)
                record.toggle()
            } else EventLog.add("권한", "마이크 권한이 있어야 한마디를 남길 수 있어요")
            REQUEST_VOICE -> setVoiceAnswer(granted)
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
        // 주행 중에는 설정을 열 수 없다. 정차 중에 띄운 확인 창도 닫고, 편집 화면은 정차할 때까지 숨긴다.
        if (driving) {
            filterDialog?.dismiss()
            hideKeyboard()
        }
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

    /** 말로 대답하기: 켤 때 마이크 권한이 없으면 먼저 묻는다. */
    private fun onVoicePressed() {
        when {
            settings.voiceAnswer -> setVoiceAnswer(false)
            hasMic() -> setVoiceAnswer(true)
            else -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_VOICE)
        }
    }

    private fun setVoiceAnswer(on: Boolean) {
        // 마이크 권한을 새로 받았으면 화면 밖에서도 마이크를 쓸 수 있게 서비스 종류를 다시 맞춘다.
        if (on) GuideService.start(this)
        settings.voiceAnswer = on
        EventLog.add("설정", "말로 대답하기 ${if (on) "켜짐" else "꺼짐"}")
        render()
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
        renderMyMate()
        // 내 길동무 편집은 글자를 입력하므로 정차 중에만 보인다.
        val editing = editingFirstRun != null && !driving
        profilePage.show(editing)
        tabBar.visibility = if (driving || editing) View.GONE else View.VISIBLE
        tabButtons.forEach { (t, button) -> button.look = if (t == tab) ButtonLook.SELECTED else ButtonLook.OUTLINE }
        pages.forEach { (t, page) -> page.visibility = if (t == tab && !editing) View.VISIBLE else View.GONE }
        if (editing) return
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
        // 카드 왼쪽에 지금 묻거나 듣는 길동무를 세운다. 바뀔 때만 다시 만든다.
        // 보여줄 길동무가 없으면(앞쪽이 조용할 때) 내 길동무가 선다.
        val mates = guide.shownMates()
        val mine = myProfile
        stageIcon.visibility = if (mates.isEmpty() && mine == null) View.GONE else View.VISIBLE
        if (mates != shownMates || (mates.isEmpty() && stageIcon.drawable == null)) {
            shownMates = mates
            stageIcon.setImageDrawable(
                if (mates.isEmpty()) mine?.let { myMateDrawable(it.look) }
                else MateDrawable(mates.map { MateDrawable.Mate(it.stage, MateDrawable.colorFor(it.id)) }),
            )
        }
        renderChips()
        val progress = guide.progress()
        progressBar.show(progress != null)
        progress?.let { progressBar.progress = (it * PROGRESS_MAX).roundToInt() }
        val order = guide.order()
        orderText.show(order != null)
        order?.let { (now, all) -> orderText.update((1..all).joinToString(" ") { if (it == now) "●" else "○" } + "  $now/$all") }
        renderTrip()

        strip.pins = guide.shownAhead().map {
            RoadStripView.Pin(it.message.id, (it.distanceM / 10).roundToInt() * 10, it.message.stage, guide.isHighlighted(it.message.id))
        }

        // 그때 할 수 있는 것만 버튼으로 보여준다.
        val phase = guide.phase
        val prompting = phase == GuideController.Phase.PROMPTING
        val listening = phase != GuideController.Phase.IDLE && !prompting
        listenButton.show(prompting)
        // 묻는 중에는 "넘기기", 묶음을 듣는 중에는 "다음 한마디". 둘 다 핸들의 다음 곡 버튼과 같다.
        skipButton.show(prompting || guide.hasNext)
        skipButton.setLabel(if (prompting) "지나갈게요" else "다음 길동무")
        stopButton.show(listening)
        thanksButton.show(phase == GuideController.Phase.PLAYING || phase == GuideController.Phase.AFTER || (phase == GuideController.Phase.IDLE && guide.lastHeard != null))
        thanksButton.setLabel(guide.lastThanks?.let { "고마워요 ${it.thanksCount}" } ?: "고마워요")
        val shown = listOf(listenButton, thanksButton, skipButton, stopButton).filter { it.visibility == View.VISIBLE }
        actions.show(shown.isNotEmpty())
        // 버튼 사이 간격만 두고, 오른쪽 끝 버튼 뒤에는 두지 않는다.
        shown.forEachIndexed { i, button ->
            val params = button.layoutParams as LinearLayout.LayoutParams
            val end = if (i == shown.lastIndex) 0 else dp(16)
            if (params.marginEnd != end) button.layoutParams = params.apply { marginEnd = end }
        }

        // 듣는 중에는 녹음하지 않는다. 녹음 중에는 끝내기 버튼으로 쓴다.
        val recording = record.phase == RecordController.Phase.RECORDING
        recordText.update(record.statusText())
        recordButton.look = if (recording) ButtonLook.REC else ButtonLook.SEED
        recordButton.setLabel(if (recording) "녹음 끝내기" else "나도 한마디 남기기")
        recordButton.setIcon(if (recording) R.drawable.ic_stop else R.drawable.ic_mic)
        recordButton.isEnabled = recording ||
            (record.phase == RecordController.Phase.IDLE && phase == GuideController.Phase.IDLE)
        val heard = myMessages.items.sumOf { it.listenCount }
        myStat.update(if (myMessages.items.isEmpty()) "아직 남긴 한마디가 없어요" else "내 한마디를 ${heard}명이 들었어요")
    }

    /** 카드의 작은 표시: 언제·비 오는 날·고마워요·들은 수. 바뀔 때만 다시 만든다. */
    private fun renderChips() {
        val chips = guide.contextMessage()?.let { ContextWords.chips(it) }.orEmpty()
        val key = chips.joinToString("|")
        if (key == chipsKey) return
        chipsKey = key
        chipsRow.removeAllViews()
        chips.forEach { chip ->
            chipsRow.addView(
                text(chip, 20f, maxLines = 1).apply {
                    background = rounded(R.color.gd_road, 20)
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) },
            )
        }
        chipsRow.show(chips.isNotEmpty())
    }

    /** "이번 길" 타일: 만난 길동무, 전한 고마워요, 그다음 길동무. */
    private fun renderTrip() {
        val trip = guide.trip()
        metText.update("길동무 ${trip.met}명")
        thankedText.update("고마워요 ${trip.thanked}번")
        val next = guide.nextAhead()
        nextText.update(
            next.firstOrNull()?.let { a ->
                val who = when {
                    next.size > 1 -> "${next.size}명"
                    a.message.stage == Stage.FLOWER -> "꽃 든 길동무"
                    else -> "길동무"
                }
                "${(a.distanceM / 10).roundToInt() * 10}m · $who"
            } ?: "아직 없어요",
        )
    }

    /** 머리줄·남기기 옆·설정의 내 길동무. 모습이 바뀔 때만 다시 그린다. */
    private fun renderMyMate() {
        val mine = myProfile
        headerName.update(mine?.nickname.orEmpty())
        myName.update(mine?.nickname ?: "내 길동무")
        settingsName.update(mine?.nickname ?: "아직 정하지 않았어요")
        val key = mine?.let { "${it.look.color}:${it.look.item}" } ?: "none"
        if (key == myMateKey) return
        myMateKey = key
        val look = mine?.look ?: Look(0, com.gildongmu.app.data.LookItem.NONE)
        headerMate.setImageDrawable(myMateDrawable(look))
        myMate.setImageDrawable(myMateDrawable(look))
        settingsMate.setImageDrawable(myMateDrawable(look))
        headerMate.show(mine != null)
    }

    private fun myMateDrawable(look: Look) = MateDrawable(Stage.SPROUT, MateDrawable.bodyColor(look.color), look.item)

    private fun renderGarden() {
        val news = myMessages.news
        newsText.update(news.orEmpty())
        newsText.visibility = if (news == null) View.GONE else View.VISIBLE
        bloomText.update(MyMessages.bloomSummary(myMessages.items) ?: "아직 남긴 발자취가 없어요. 운전하다 떠오른 한마디를 길 위에 남겨 보세요.")

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
        voiceButton.setLabel(if (settings.voiceAnswer && hasMic()) "켜짐 · 끄기" else "꺼짐 · 켜기")
        driveText?.update(tracker.latest?.let(DriveTracker::describe) ?: "GPS 위치를 기다리는 중")
        serverText?.update(guide.serverStatus)
        logText?.update(EventLog.recent())
    }

    private fun buildLayout(): View {
        gardenKey = ""
        shownMates = emptyList()
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
        headerMate = ImageView(this).also { header.addView(it, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }) }
        headerName = text("", 22f, medium = true, maxLines = 1).also { header.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(20) }) }
        modeText = text("", 22f, R.color.gd_text_sub).also { header.addView(it) }
        root.addView(header)

        // 탭: 정차 중에만
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabButtons = linkedMapOf(
            Tab.ROAD to ActionButton(this, "길", R.drawable.ic_route, ButtonLook.SELECTED, textSp = 24f),
            Tab.GARDEN to ActionButton(this, "내 발자취", R.drawable.ic_florist, ButtonLook.OUTLINE, textSp = 24f),
            Tab.SETTINGS to ActionButton(this, "설정", R.drawable.ic_settings, ButtonLook.OUTLINE, textSp = 24f),
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
        profilePage = FrameLayout(this).apply { visibility = View.GONE }
        content.addView(profilePage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chipsKey = ""
        myMateKey = ""
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
        stageIcon = ImageView(this).also { top.addView(it, LinearLayout.LayoutParams(dp(200), dp(200)).apply { marginEnd = dp(32) }) }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { words.addView(it, margins(bottom = 12)) }
        titleText = text("", 40f, medium = true, maxLines = 2).also { words.addView(it) }
        detailText = text("", 22f, R.color.gd_text_sub, maxLines = 2).also { words.addView(it, margins(top = 8)) }
        // 듣는 중: 진행 막대와 묶음 순서(1초마다 칸만 바뀌어 주행 중에도 쓸 수 있다)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            isIndeterminate = false
            progressTintList = ColorStateList.valueOf(getColor(R.color.gd_highlight))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.gd_road))
        }
        words.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16)).apply { topMargin = dp(16) })
        orderText = text("", 22f, R.color.gd_text_sub, maxLines = 1).also { words.addView(it, margins(top = 8)) }
        top.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(top)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listenButton = ActionButton(this, "들어 볼게요", R.drawable.ic_play, ButtonLook.GO).apply { onClick { guide.accept() } }
        skipButton = ActionButton(this, "지나갈게요", R.drawable.ic_skip, ButtonLook.OUTLINE).apply { onClick { guide.skip() } }
        stopButton = ActionButton(this, "그만 들을게요", R.drawable.ic_stop, ButtonLook.OUTLINE).apply { onClick { guide.stop() } }
        thanksButton = ActionButton(this, "고마워요", R.drawable.ic_favorite, ButtonLook.OUTLINE).apply { onClick { guide.thanks() } }
        // 한 번에 보이는 버튼은 많아야 셋이다. 보이지 않는 버튼 앞의 간격은 그 버튼과 함께 사라진다.
        listOf(listenButton, thanksButton, skipButton, stopButton).forEach { button ->
            actions.addView(button, LinearLayout.LayoutParams(0, dp(104), 1f).apply { marginEnd = dp(16) })
        }
        card.addView(actions, margins(top = 40))

        // "이번 길" 타일: Pleos 기본 앱처럼 이름 한 줄 + 값 한 줄
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metText = tile(tiles, "이번 길에서 만난")
        thankedText = tile(tiles, "내가 전한")
        nextText = tile(tiles, "그다음 길동무", last = true)

        // 남기기: 상태 한 줄 + 내 길동무 + 가장 큰 버튼
        val recordArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recordText = text("", 22f, R.color.gd_text_sub, maxLines = 2).also { recordArea.addView(it) }
        val recordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        myMate = ImageView(this).also { recordRow.addView(it, LinearLayout.LayoutParams(dp(96), dp(96))) }
        val me = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        myName = text("", 26f, medium = true, maxLines = 1).also { me.addView(it) }
        myStat = text("", 20f, R.color.gd_text_sub, maxLines = 1).also { me.addView(it) }
        recordRow.addView(me, LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        recordButton = ActionButton(this, "나도 한마디 남기기", R.drawable.ic_mic, ButtonLook.SEED, textSp = 30f).apply { onClick { onRecordPressed() } }
        recordRow.addView(recordButton, LinearLayout.LayoutParams(0, dp(112), 1f).apply { marginStart = dp(16) })
        recordArea.addView(recordRow, margins(top = 12))

        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(tiles, margins(top = 20))
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

    /** 타일 하나를 붙이고 값 글자를 돌려준다. */
    private fun tile(parent: LinearLayout, label: String, last: Boolean = false): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(R.color.gd_surface, 24)
            setPadding(dp(28), dp(20), dp(28), dp(20))
        }
        box.addView(text(label, 20f, R.color.gd_text_sub, maxLines = 1))
        val value = text("", 30f, medium = true, maxLines = 1).also { box.addView(it, margins(top = 4)) }
        parent.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!last) marginEnd = dp(16) })
        return value
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
        addView(ImageView(this@MainActivity).apply { setImageDrawable(MateDrawable(m.stage, MateDrawable.colorFor(m.id))) }, LinearLayout.LayoutParams(dp(88), dp(88)))
        addView(text(if (m.kind == Kind.UNKNOWN) "한마디" else "${m.kind.label} 한마디", 24f, medium = true, maxLines = 1).apply { gravity = Gravity.CENTER }, margins(top = 12))
        addView(text(stageLine(m), 20f, R.color.gd_text_sub, maxLines = 1).apply { gravity = Gravity.CENTER }, margins(top = 4))
        addView(text("들은 ${m.listenCount}명 · 고마워요 ${m.thanksCount}", 20f, R.color.gd_text_sub, maxLines = 1).apply { gravity = Gravity.CENTER }, margins(top = 4))
    }

    /** 내 발자취 칸의 한 줄: 내 길동무가 지금 어떤 모습인지. 검수 결과가 특별하면 그걸 보여준다. */
    private fun stageLine(m: MyMessage): String = when {
        m.review != Review.CLEAN && m.review != Review.PENDING -> m.review.label
        m.stage == Stage.SEED -> "확인 중이에요"
        m.stage == Stage.SPROUT -> "길 위에 섰어요"
        m.stage == Stage.FLOWER -> "꽃을 들었어요"
        else -> "추억이 됐어요"
    }

    private fun buildSettingsPage(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 내 길동무: 닉네임은 내 차에서만 보인다.
        val mateCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(R.color.gd_surface, 24)
            setPadding(dp(28), dp(20), dp(28), dp(20))
        }
        settingsMate = ImageView(this).also { mateCard.addView(it, LinearLayout.LayoutParams(dp(96), dp(96))) }
        val mateWords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mateWords.addView(text("내 길동무", 20f, R.color.gd_text_sub))
        settingsName = text("", 28f, medium = true, maxLines = 1).also { mateWords.addView(it) }
        mateWords.addView(text("닉네임은 내 차에서만 보여요. 한 달에 한 번 바꿀 수 있어요.", 20f, R.color.gd_text_sub), margins(top = 4))
        mateCard.addView(mateWords, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(20) })
        val change = ActionButton(this, "바꾸기", R.drawable.ic_settings, ButtonLook.OUTLINE, textSp = 24f).apply {
            onClick { if (!drivingMode.driving) openEditor(firstRun = myProfile == null) }
        }
        mateCard.addView(change, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(88)).apply { marginStart = dp(24) })
        column.addView(mateCard, margins(bottom = 16))

        filterButton = ActionButton(this, "켜짐 · 끄기", R.drawable.ic_settings, ButtonLook.OUTLINE, textSp = 24f).apply { onClick { onFilterPressed() } }
        column.addView(settingCard("비속어 필터", "켜 두면 거친 말이 섞인 한마디는 들려주지 않아요.", filterButton))
        voiceButton = ActionButton(this, "켜짐 · 끄기", R.drawable.ic_mic, ButtonLook.OUTLINE, textSp = 24f).apply { onClick { onVoicePressed() } }
        column.addView(
            settingCard(
                "말로 대답하기",
                "\"들어 볼까요?\"에 \"응\"·\"지나가\", 듣고 나서 \"고마워\"라고 말하면 돼요. 마이크는 대답을 기다리는 몇 초 동안만 켜져요.",
                voiceButton,
            ),
            margins(top = 16),
        )
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

    private fun View.show(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
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
        private const val REQUEST_START = 1
        private const val REQUEST_RECORD = 2
        private const val REQUEST_VOICE = 3
        private const val PROGRESS_MAX = 1_000
        private const val GARDEN_TILES = 12
    }
}
