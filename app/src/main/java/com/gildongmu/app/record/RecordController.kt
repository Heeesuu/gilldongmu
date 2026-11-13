package com.gildongmu.app.record

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gildongmu.app.EventLog
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.UploadMeta
import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.VehicleSignals
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * 한마디 남기기: 녹음을 시작한 순간의 위치·방향·차량 상황을 기억했다가, 녹음이 끝나면 올린다.
 * 위치와 방향이 녹음 시작 값인 이유는 "이 앞 공사 중"처럼 말을 꺼낸 자리를 가리키기 때문이다 (docs/api.md 3.2).
 * 방향을 아직 모르면(막힌 길에서 앱을 막 켠 경우) 녹음은 그대로 하고, 방향은 [LateHeading]으로 나중에 붙인다.
 * 주행 중에는 다시 듣기·고치기를 하지 않는다 (정차 중 다시 듣기는 2차).
 * 모든 메서드는 메인 스레드에서 부른다.
 */
class RecordController(
    context: Context,
    private val api: ServerApi,
    private val vehicle: VehicleSignals,
    private val currentDrive: () -> DriveState?,
    private val lateHeading: LateHeading = LateHeading(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    // 녹음 파일을 차 안 음성 인식으로 글자로 바꾼다(검수용). 서버는 이 글자로 검수하고, 음성을 외부 음성 인식 서비스로 보내지 않는다.
    private val transcribe: (File, (String) -> Unit) -> Unit = { _, done -> done("") },
    private val onUploaded: (id: String) -> Unit,
) {
    enum class Phase { IDLE, RECORDING }

    /** 녹음을 시작한 순간의 값. 길이는 녹음이 끝나야 안다. `heading`은 모르면 null. */
    private class Start(
        val recordedAt: String,
        val startedAtMs: Long,
        val lat: Double,
        val lon: Double,
        val heading: Double?,
        val speedKmh: Double,
        val wiperOn: Boolean?,
        val headlightOn: Boolean?,
    )

    /** 녹음은 끝났고 방향을 기다리는 한마디. */
    private class Waiting(val start: Start, val recording: VoiceRecorder.Recording)

    private val handler = Handler(Looper.getMainLooper())
    private val recorder = VoiceRecorder(context) { onRecorded(it) }
    private var start: Start? = null
    private val waiting = mutableListOf<Waiting>()
    private var released = false

    // 위치가 한동안 안 들어와도(터널 등) 10분이 지나면 방향 없이 올린다.
    private val waitCheck = object : Runnable {
        override fun run() {
            resolveWaiting(currentDrive())
            if (waiting.isNotEmpty()) handler.postDelayed(this, WAIT_CHECK_MS)
        }
    }

    var phase = Phase.IDLE
        private set
    private var status = "운전하다 떠오른 한마디를 15초까지 남길 수 있어요."

    /** 녹음 중이면 끝내고, 아니면 시작한다. */
    fun toggle() {
        when (phase) {
            Phase.IDLE -> begin()
            Phase.RECORDING -> recorder.stop()
        }
    }

    /** 위치가 바뀔 때마다 부른다. 방향을 기다리던 한마디에 방향을 붙인다. */
    fun onDrive(state: DriveState) = resolveWaiting(state)

    fun statusText(): String = when (phase) {
        Phase.RECORDING -> "녹음 중 %d / %d초 · 버튼을 한 번 더 누르면 끝나요".format(
            Locale.KOREA, recorder.elapsedMs / 1_000, VoiceRecorder.MAX_DURATION_MS / 1_000,
        )
        Phase.IDLE -> if (waiting.isEmpty()) status else "한마디 ${waiting.size}개를 담아 뒀어요. 차가 조금 움직이면 방향을 붙여 올려요."
    }

    /** 앱이 닫히면 방향을 기다리던 한마디는 방향 없이 바로 올려 본다. 닫히는 중이라 못 보낼 수도 있다. */
    fun release() {
        recorder.cancel()
        handler.removeCallbacks(waitCheck)
        waiting.toList().forEach { upload(it.start, it.recording, heading = null) }
        waiting.clear()
        released = true
    }

    private fun begin() {
        val drive = currentDrive()
        if (!api.configured) {
            status = "서버 주소가 설정되지 않아 남길 수 없어요."
            return
        }
        if (drive == null) {
            // 위치는 꼭 있어야 한다. 한마디를 남길 자리이기 때문이다.
            status = "GPS 위치를 잡는 중이에요. 잠시 뒤에 남겨 주세요."
            return
        }
        if (!recorder.start()) {
            status = "마이크를 열지 못했어요."
            return
        }
        start = Start(
            recordedAt = Instant.now().toString(),
            startedAtMs = clock(),
            lat = drive.lat,
            lon = drive.lon,
            heading = drive.heading,
            speedKmh = drive.speedKmh,
            wiperOn = vehicle.wiperOn,
            headlightOn = vehicle.headlightOn,
        )
        phase = Phase.RECORDING
    }

    private fun onRecorded(recording: VoiceRecorder.Recording?) {
        val begun = start
        start = null
        phase = Phase.IDLE
        if (recording == null || begun == null) {
            status = "1초보다 짧아서 남기지 않았어요."
            return
        }
        if (begun.heading != null) {
            upload(begun, recording, begun.heading)
            return
        }
        EventLog.add("남기기", "방향을 아직 몰라서 담아 뒀어요. 움직이면 방향을 붙여 올려요.")
        waiting += Waiting(begun, recording)
        // 녹음하는 동안 움직였으면 바로 올라간다.
        resolveWaiting(currentDrive())
        if (waiting.isNotEmpty()) {
            handler.removeCallbacks(waitCheck)
            handler.postDelayed(waitCheck, WAIT_CHECK_MS)
        }
    }

    private fun resolveWaiting(state: DriveState?) {
        if (waiting.isEmpty()) return
        val now = clock()
        waiting.toList().forEach { w ->
            val decision = lateHeading.decide(w.start.lat, w.start.lon, w.start.startedAtMs, state, now)
            when (decision) {
                LateHeading.Decision.Wait -> return@forEach
                is LateHeading.Decision.Use -> {
                    EventLog.add("남기기", "방향 %.0f°를 붙여 올려요".format(Locale.KOREA, decision.heading))
                    upload(w.start, w.recording, decision.heading)
                }
                LateHeading.Decision.WithoutHeading -> {
                    EventLog.add("남기기", "방향을 끝내 몰라서 양쪽 방향 모두에 들리게 올려요")
                    upload(w.start, w.recording, heading = null)
                }
            }
            waiting -= w
        }
        if (waiting.isEmpty()) handler.removeCallbacks(waitCheck)
    }

    private fun upload(begun: Start, recording: VoiceRecorder.Recording, heading: Double?) {
        status = "말을 글자로 바꾸는 중이에요."
        transcribe(recording.file) { transcript -> send(begun, recording, heading, transcript) }
    }

    private fun send(begun: Start, recording: VoiceRecorder.Recording, heading: Double?, transcript: String) {
        status = "올리는 중이에요."
        val meta = UploadMeta(
            recordedAt = begun.recordedAt,
            lat = begun.lat,
            lon = begun.lon,
            heading = heading,
            speedKmh = begun.speedKmh,
            durationMs = recording.durationMs,
            wiperOn = begun.wiperOn,
            headlightOn = begun.headlightOn,
            transcript = transcript,
        )
        api.upload(recording.file, meta) { result ->
            // MVP는 실패해도 다시 보내지 않는다. 녹음 파일은 어느 쪽이든 지운다.
            recording.file.delete()
            if (released) return@upload
            result.onSuccess {
                status = "한마디를 남겼어요. 확인이 끝나면 길 위에 길동무로 서요."
                EventLog.add("남기기", "한마디를 남겼어요 (${recording.durationMs / 1_000}초, 방향 ${heading?.let { "%.0f°".format(Locale.KOREA, it) } ?: "모름"}, 와이퍼 ${begun.wiperOn ?: "모름"}, 전조등 ${begun.headlightOn ?: "모름"})")
                onUploaded(it.id)
            }.onFailure {
                status = "올리지 못했어요: ${it.message}"
                EventLog.add("남기기", status)
            }
        }
    }

    companion object {
        private const val WAIT_CHECK_MS = 30_000L
    }
}
