package com.gildongmu.app.record

import android.content.Context
import com.gildongmu.app.EventLog
import com.gildongmu.app.data.ServerApi
import com.gildongmu.app.data.UploadMeta
import com.gildongmu.app.drive.DriveState
import com.gildongmu.app.drive.VehicleSignals
import java.time.Instant
import java.util.Locale

/**
 * 한마디 남기기: 녹음을 시작한 순간의 위치·방향·차량 상황을 기억했다가, 녹음이 끝나면 바로 올린다.
 * 위치와 방향이 녹음 시작 값인 이유는 "이 앞 공사 중"처럼 말을 꺼낸 자리를 가리키기 때문이다 (docs/api.md 3.2).
 * 주행 중에는 다시 듣기·고치기를 하지 않는다 (정차 중 다시 듣기는 2차).
 * 모든 메서드는 메인 스레드에서 부른다.
 */
class RecordController(
    context: Context,
    private val api: ServerApi,
    private val vehicle: VehicleSignals,
    private val currentDrive: () -> DriveState?,
    private val onUploaded: (id: String) -> Unit,
) {
    enum class Phase { IDLE, RECORDING, UPLOADING }

    /** 녹음을 시작한 순간의 값. 길이는 녹음이 끝나야 안다. */
    private class Start(
        val recordedAt: String,
        val lat: Double,
        val lon: Double,
        val heading: Double,
        val speedKmh: Double,
        val wiperOn: Boolean?,
        val headlightOn: Boolean?,
    )

    private val recorder = VoiceRecorder(context) { onRecorded(it) }
    private var start: Start? = null

    var phase = Phase.IDLE
        private set
    private var status = "운전하다 떠오른 한마디를 15초까지 남길 수 있어요."

    /** 녹음 중이면 끝내고, 아니면 시작한다. */
    fun toggle() {
        when (phase) {
            Phase.IDLE -> begin()
            Phase.RECORDING -> recorder.stop()
            Phase.UPLOADING -> Unit
        }
    }

    fun statusText(): String = when (phase) {
        Phase.RECORDING -> "녹음 중 %d / %d초 · 버튼을 한 번 더 누르면 끝나요".format(
            Locale.KOREA, recorder.elapsedMs / 1_000, VoiceRecorder.MAX_DURATION_MS / 1_000,
        )
        else -> status
    }

    fun release() = recorder.cancel()

    private fun begin() {
        val drive = currentDrive()
        val heading = drive?.heading
        if (!api.configured) {
            status = "서버 주소가 설정되지 않아 남길 수 없어요."
            return
        }
        if (drive == null || heading == null) {
            // 방향을 알아야 같은 방향으로 가는 차에만 들려줄 수 있다.
            status = "움직이기 시작한 뒤에 남길 수 있어요."
            return
        }
        if (!recorder.start()) {
            status = "마이크를 열지 못했어요."
            return
        }
        start = Start(
            recordedAt = Instant.now().toString(),
            lat = drive.lat,
            lon = drive.lon,
            heading = heading,
            speedKmh = drive.speedKmh,
            wiperOn = vehicle.wiperOn,
            headlightOn = vehicle.headlightOn,
        )
        phase = Phase.RECORDING
    }

    private fun onRecorded(recording: VoiceRecorder.Recording?) {
        val begun = start
        start = null
        if (recording == null || begun == null) {
            phase = Phase.IDLE
            status = "1초보다 짧아서 남기지 않았어요."
            return
        }
        phase = Phase.UPLOADING
        status = "올리는 중이에요."
        val meta = UploadMeta(
            recordedAt = begun.recordedAt,
            lat = begun.lat,
            lon = begun.lon,
            heading = begun.heading,
            speedKmh = begun.speedKmh,
            durationMs = recording.durationMs,
            wiperOn = begun.wiperOn,
            headlightOn = begun.headlightOn,
        )
        api.upload(recording.file, meta) { result ->
            // MVP는 실패해도 다시 보내지 않는다. 녹음 파일은 어느 쪽이든 지운다.
            recording.file.delete()
            phase = Phase.IDLE
            result.onSuccess {
                status = "씨앗을 심었어요. 검수가 끝나면 새싹이 돋아요."
                EventLog.add("남기기", "씨앗을 심었어요 (${recording.durationMs / 1_000}초, 와이퍼 ${begun.wiperOn ?: "모름"}, 전조등 ${begun.headlightOn ?: "모름"})")
                onUploaded(it.id)
            }.onFailure {
                status = "올리지 못했어요: ${it.message}"
                EventLog.add("남기기", status)
            }
        }
    }
}
