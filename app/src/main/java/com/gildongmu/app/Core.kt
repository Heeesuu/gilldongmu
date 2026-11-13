package com.gildongmu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.gildongmu.app.data.DeviceId
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.Settings
import com.gildongmu.app.drive.DriveTracker
import com.gildongmu.app.drive.DrivingMode
import com.gildongmu.app.drive.VehicleSignals
import com.gildongmu.app.gleo.GleoActions
import com.gildongmu.app.gleo.GleoCommand
import com.gildongmu.app.gleo.GleoReply
import com.gildongmu.app.gleo.GleoReply.Mode
import com.gildongmu.app.gleo.GleoReply.Status
import com.gildongmu.app.guide.GuideController
import com.gildongmu.app.record.MyMessages
import com.gildongmu.app.record.RecordController

/**
 * 화면이 없어도 도는 길동무의 몸통: 위치 → 앞쪽 한마디 → "들어 볼까요?" → 재생, 녹음, 내 발자취, Gleo 명령.
 * 포그라운드 서비스([GuideService])가 살아 있는 동안 하나만 있고([GildongmuApp.core]), 화면(MainActivity)은 이것을 읽어 그리기만 한다.
 * 내비를 전체 화면으로 띄워 길동무가 뒤로 숨어도 안내가 이어지게 하려는 것이다(안드로이드는 포그라운드 서비스가 있어야 화면 밖에서 위치를 준다).
 * 모든 메서드는 메인 스레드에서 부른다. 앱 Context로 만든다.
 */
class Core(private val context: Context) : GleoActions.Target {

    private val handler = Handler(Looper.getMainLooper())

    val settings = Settings(context)
    val api = ServerApi(BuildConfig.SERVER_URL, DeviceId.get(context), BuildConfig.VERSION_NAME)
    val vehicle = VehicleSignals(context)
    val myMessages = MyMessages(api)
    val guide: GuideController = GuideController(
        context, api,
        // 녹음하는 동안에는 "들어 볼까요?"를 묻지 않는다.
        canPrompt = { record.phase == RecordController.Phase.IDLE },
        filterOn = { settings.filterOn },
        voiceOn = { settings.voiceAnswer && hasMic() },
        nickname = { settings.profile?.nickname },
    )
    val record: RecordController = RecordController(context, api, vehicle, currentDrive = { tracker.latest }) { id -> myMessages.track(id) }
    val tracker = DriveTracker(context) {
        guide.onDrive(it)
        record.onDrive(it)
    }
    val drivingMode = DrivingMode(context) { driving -> onDrivingChanged?.invoke(driving) }

    /** 주행·정차가 바뀔 때 화면이 받는다. 화면이 없으면 null. */
    var onDrivingChanged: ((Boolean) -> Unit)? = null

    /** 화면 밖에서 상태가 바뀌었을 때(예: Gleo 명령) 화면이 다시 그린다. 화면이 없으면 null. */
    var onChanged: (() -> Unit)? = null

    /** 알림의 "끄기" 등으로 정리됐을 때 화면이 닫힌다. */
    var onReleased: (() -> Unit)? = null

    var released = false
        private set

    fun start() {
        GleoActions.target = this
        EventLog.add("앱", "길동무 시작 · 서버 ${BuildConfig.SERVER_URL.ifEmpty { "(미설정)" }}")
        drivingMode.connect()
        vehicle.start()
        myMessages.start()
        startTracking()
    }

    /** 위치 권한이 있으면 위치를 받기 시작한다. 이미 받고 있으면 그대로 둔다. */
    fun startTracking(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && tracker.start()

    fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun release() {
        if (released) return
        released = true
        if (GleoActions.target === this) GleoActions.target = null
        handler.removeCallbacksAndMessages(null)
        record.release()
        myMessages.stop()
        vehicle.stop()
        drivingMode.disconnect()
        tracker.stop()
        guide.release()
        EventLog.add("앱", "길동무 안내를 껐어요")
        onReleased?.invoke()
    }

    /**
     * Gleo 음성 명령 처리. 화면이 없어도(내비를 띄운 채로) 동작한다.
     * 결과 문장은 Gleo가 대답을 만들 때 쓰므로 영어로 쓴다.
     * 앱이 바로 소리를 내는 명령(듣기·넘기기)은 Gleo가 말하지 않게(SILENT), 녹음은 삑 소리 뒤에 시작한다.
     */
    override fun onGleo(command: GleoCommand): GleoReply {
        val phase = guide.phase
        val listening = phase != GuideController.Phase.IDLE && phase != GuideController.Phase.PROMPTING
        val reply = when (command) {
            GleoCommand.LISTEN -> when {
                listening -> GleoReply(Status.SUCCESS, "Road messages are already playing.", Mode.SILENT)
                record.phase == RecordController.Phase.RECORDING -> GleoReply(Status.FAIL, "A message is being recorded right now.", Mode.VOICE)
                guide.listenNow() -> GleoReply(Status.SUCCESS, "Playing the road messages ahead.", Mode.SILENT)
                else -> GleoReply(Status.FAIL, "There are no road messages ahead right now.", Mode.VOICE)
            }
            GleoCommand.SKIP -> if (phase == GuideController.Phase.IDLE) {
                GleoReply(Status.FAIL, "No road message is playing.", Mode.VOICE)
            } else {
                guide.skip()
                GleoReply(Status.SUCCESS, "Skipped the road message.", Mode.SILENT)
            }
            GleoCommand.STOP -> if (phase == GuideController.Phase.IDLE) {
                GleoReply(Status.FAIL, "No road message is playing.", Mode.VOICE)
            } else {
                guide.stop()
                GleoReply(Status.SUCCESS, "Stopped the road messages.", Mode.BEEP)
            }
            GleoCommand.THANK -> if (guide.canThank) {
                guide.thanks()
                // 다음 한마디가 이어서 나올 수 있으면 말 대신 효과음만.
                GleoReply(Status.SUCCESS, "Sent thanks to the driver who left the message.", if (listening) Mode.BEEP else Mode.VOICE)
            } else {
                GleoReply(Status.FAIL, "There is no road message to thank yet.", Mode.VOICE)
            }
            GleoCommand.RECORD -> when {
                record.phase == RecordController.Phase.RECORDING -> {
                    record.toggle()
                    GleoReply(Status.SUCCESS, "Finished recording the message.", Mode.BEEP)
                }
                phase != GuideController.Phase.IDLE -> GleoReply(Status.FAIL, "Please wait until the road message finishes.", Mode.VOICE)
                !hasMic() -> GleoReply(Status.FAIL, "Microphone permission is needed in Gildongmu.", Mode.VOICE)
                tracker.latest == null -> GleoReply(Status.FAIL, "The current location is not ready yet.", Mode.VOICE)
                else -> {
                    // Gleo의 삑 소리와 마이크 사용이 끝난 뒤에 녹음을 시작한다.
                    handler.postDelayed({ if (record.phase == RecordController.Phase.IDLE) record.toggle() }, GLEO_RECORD_DELAY_MS)
                    GleoReply(Status.SUCCESS, "Recording starts after the beep. It stops after 15 seconds.", Mode.BEEP)
                }
            }
        }
        onChanged?.invoke()
        return reply
    }

    companion object {
        private const val GLEO_RECORD_DELAY_MS = 1_200L
    }
}
