package com.gildongmu.app.data

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 길동무 서버 API (docs/api.md).
 * 요청은 백그라운드 스레드 하나에서 차례로 보내고, 결과는 메인 스레드 콜백으로 돌려준다.
 * 외부 라이브러리 없이 HttpURLConnection과 org.json만 쓴다.
 */
class ServerApi(
    private val baseUrl: String,
    private val deviceId: String,
    private val appVersion: String,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** 제출용 빌드는 배포 주소가 정해질 때까지 비어 있다. */
    val configured get() = baseUrl.isNotEmpty()

    fun nearby(query: NearbyQuery, callback: (Result<NearbyResult>) -> Unit) = run(callback) {
        val params = listOf(
            "lat" to "%.6f".format(Locale.US, query.lat),
            "lon" to "%.6f".format(Locale.US, query.lon),
            "heading" to "%.1f".format(Locale.US, query.heading),
            "speed_kmh" to "%.1f".format(Locale.US, query.speedKmh),
            "filter" to if (query.filterOn) "on" else "off",
            "limit" to query.limit.toString(),
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        parseNearby(JSONObject(request("GET", "messages/nearby?$params").decodeToString()))
    }

    /** 녹음 파일(OGG/Opus)과 녹음 시작 때의 위치·방향을 올린다. 검수는 서버가 따로 하므로 씨앗으로 돌아온다. */
    fun upload(audio: File, meta: UploadMeta, callback: (Result<UploadResult>) -> Unit) = run(callback) {
        val metaJson = JSONObject()
            .put("recorded_at", meta.recordedAt)
            .put("lat", meta.lat)
            .put("lon", meta.lon)
            .put("speed_kmh", meta.speedKmh)
            .put("duration_ms", meta.durationMs)
        // 모르면 보내지 않는다 (docs/api.md 3.2). 방향이 없으면 서버는 양쪽 방향 모두에 들려준다.
        meta.heading?.let { metaJson.put("heading", it) }
        meta.wiperOn?.let { metaJson.put("wiper_on", it) }
        meta.headlightOn?.let { metaJson.put("headlight_on", it) }

        val boundary = "gildongmu-${UUID.randomUUID()}"
        val body = Multipart.body(
            boundary,
            fields = listOf("meta" to metaJson.toString()),
            file = Multipart.FilePart("audio", audio.name, AUDIO_MIME, audio.readBytes()),
        )
        val json = JSONObject(request("POST", "messages", body, Multipart.contentType(boundary)).decodeToString())
        UploadResult(id = json.getString("id"), stage = Stage.of(json.getString("stage")))
    }

    fun myMessages(callback: (Result<List<MyMessage>>) -> Unit) = run(callback) {
        val items = JSONObject(request("GET", "me/messages").decodeToString()).optJSONArray("messages")
        (0 until (items?.length() ?: 0)).map { i ->
            val m = items!!.getJSONObject(i)
            MyMessage(
                id = m.getString("id"),
                stage = Stage.of(m.getString("stage")),
                kind = Kind.of(m.optString("kind")),
                review = Review.of(m.getString("review")),
                durationMs = m.getInt("duration_ms"),
                listenCount = m.getInt("listen_count"),
                thanksCount = m.getInt("thanks_count"),
                createdAt = m.getString("created_at"),
            )
        }
    }

    /** `audioUrl`은 nearby 응답의 `audio_url`(예: `/v1/messages/{id}/audio`)이다. */
    fun audio(audioUrl: String, callback: (Result<ByteArray>) -> Unit) = run(callback) {
        request("GET", audioUrl)
    }

    fun thanks(id: String, callback: (Result<ThanksResult>) -> Unit) = run(callback) {
        val json = JSONObject(request("POST", "messages/$id/thanks").decodeToString())
        ThanksResult(
            thanksCount = json.getInt("thanks_count"),
            stage = Stage.of(json.getString("stage")),
            alreadyThanked = json.getBoolean("already_thanked"),
        )
    }

    fun listen(id: String, result: ListenResult, playedMs: Long, callback: (Result<Unit>) -> Unit) = run(callback) {
        val body = JSONObject().put("result", result.wire).put("played_ms", playedMs)
        request("POST", "messages/$id/listen", body.toString().toByteArray(), "application/json")
        Unit
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun <T> run(callback: (Result<T>) -> Unit, block: () -> T) {
        worker.execute {
            val result = runCatching(block)
            main.post { callback(result) }
        }
    }

    /**
     * `path`가 `messages/...`처럼 상대 경로면 `baseUrl`(…/v1) 아래로,
     * `/v1/...`처럼 `/`로 시작하면 서버 주소 바로 아래로 붙는다.
     */
    private fun request(method: String, path: String, body: ByteArray? = null, contentType: String? = null): ByteArray {
        val url = URL(URL("$baseUrl/"), path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("X-Device-Id", deviceId)
            setRequestProperty("X-App-Version", appVersion)
        }
        try {
            if (method == "POST") {
                // 본문이 없는 POST도 Content-Length: 0을 보내도록 비어 있는 본문을 쓴다.
                val bytes = body ?: ByteArray(0)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val ok = status in 200..299
            val bytes = (if (ok) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (!ok) throw apiError(status, bytes)
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(status: Int, bytes: ByteArray): ApiException {
        val error = runCatching { JSONObject(bytes.decodeToString()).getJSONObject("error") }.getOrNull()
        return ApiException(
            status = status,
            code = error?.optString("code").orEmpty().ifEmpty { "HTTP_$status" },
            message = error?.optString("message").orEmpty().ifEmpty { "서버가 $status 으로 응답했습니다." },
        )
    }

    private fun parseNearby(json: JSONObject): NearbyResult {
        val items = json.optJSONArray("messages")
        val messages = (0 until (items?.length() ?: 0)).map { i ->
            val m = items!!.getJSONObject(i)
            NearbyMessage(
                id = m.getString("id"),
                stage = Stage.of(m.getString("stage")),
                kind = Kind.of(m.optString("kind")),
                lat = m.getDouble("lat"),
                lon = m.getDouble("lon"),
                heading = if (m.isNull("heading")) null else m.getDouble("heading"),
                distanceM = m.getInt("distance_m"),
                etaSec = if (m.isNull("eta_sec")) null else m.getInt("eta_sec"),
                durationMs = m.getInt("duration_ms"),
                thanksCount = m.getInt("thanks_count"),
                audioUrl = m.getString("audio_url"),
            )
        }
        return NearbyResult(
            totalAhead = json.getInt("total_ahead"),
            messages = messages,
            nextPollSec = json.optInt("next_poll_sec", 10),
        )
    }

    companion object {
        private const val AUDIO_MIME = "audio/ogg"
    }
}
