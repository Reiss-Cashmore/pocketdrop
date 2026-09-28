package dev.dropspike.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** What one mining minute achieved. */
enum class MinuteStatus {
    /** Twitch's drop progress went up this minute. */
    Credited,

    /** Heartbeat accepted (HTTP 204) but progress hasn't moved yet; Twitch often credits in bursts. */
    Sent,

    /** Something failed: no channel, heartbeat rejected, network error. */
    Failed,

    /** Running, but there was nothing to mine. */
    Idle,
}

data class MinuteEntry(val atMs: Long, val status: MinuteStatus, val note: String)

/**
 * One entry per mining tick, persisted as JSON lines so the history survives the service or
 * the app being killed. Keeps the last 24 hours.
 */
object UptimeLog {
    private const val KEEP_MS = 24 * 60 * 60 * 1000L
    private lateinit var file: File
    private val _entries = MutableStateFlow<List<MinuteEntry>>(emptyList())
    val entries: StateFlow<List<MinuteEntry>> = _entries

    fun init(context: Context) {
        file = File(context.filesDir, "uptime.jsonl")
        val cutoff = System.currentTimeMillis() - KEEP_MS
        _entries.value = runCatching {
            file.readLines().mapNotNull { line ->
                runCatching {
                    val j = JSONObject(line)
                    MinuteEntry(j.getLong("t"), MinuteStatus.valueOf(j.getString("s")), j.optString("n"))
                }.getOrNull()
            }.filter { it.atMs >= cutoff }
        }.getOrDefault(emptyList())
        // Drop entries older than 24h from disk; record() only appends.
        runCatching { file.writeText(_entries.value.joinToString("") { it.toLine() + "\n" }) }
    }

    @Synchronized
    fun record(status: MinuteStatus, note: String) {
        val entry = MinuteEntry(System.currentTimeMillis(), status, note)
        val cutoff = entry.atMs - KEEP_MS
        _entries.value = (_entries.value + entry).filter { it.atMs >= cutoff }
        runCatching { file.appendText(entry.toLine() + "\n") }
    }

    fun clear() {
        _entries.value = emptyList()
        runCatching { file.delete() }
    }

    private fun MinuteEntry.toLine() = JSONObject().put("t", atMs).put("s", status.name).put("n", note).toString()
}
