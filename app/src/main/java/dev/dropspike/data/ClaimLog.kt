package dev.dropspike.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** A drop PocketDrop claimed, for the rewards history. */
data class ClaimEntry(
    val atMs: Long,
    val game: String,
    val gameId: String,
    val campaign: String,
    val drop: String,
    val imageUrl: String?,
    val status: String,
)

/** Every claim PocketDrop made, kept on the device (claims.jsonl, newest last). */
object ClaimLog {
    private lateinit var file: File
    private val _entries = MutableStateFlow<List<ClaimEntry>>(emptyList())
    val entries: StateFlow<List<ClaimEntry>> = _entries

    fun init(context: Context) {
        file = File(context.filesDir, "claims.jsonl")
        _entries.value = runCatching {
            file.takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
                runCatching {
                    val j = JSONObject(line)
                    ClaimEntry(
                        j.getLong("at"), j.optString("game"), j.optString("gameId"), j.optString("campaign"),
                        j.optString("drop"), j.optString("image").ifEmpty { null }, j.optString("status"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(entry: ClaimEntry) {
        val json = JSONObject()
            .put("at", entry.atMs).put("game", entry.game).put("gameId", entry.gameId).put("campaign", entry.campaign)
            .put("drop", entry.drop).put("image", entry.imageUrl ?: "").put("status", entry.status)
        runCatching { file.appendText(json.toString() + "\n") }
        _entries.value = _entries.value + entry
    }
}
