package dev.dropspike.data

import android.content.Context
import android.os.Process
import android.os.SystemClock
import dev.dropspike.DropSpikeApp
import dev.dropspike.service.MinerState
import dev.dropspike.service.WakeWorker
import dev.dropspike.twitch.IntegrityRenewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The shareable diagnostics report: every piece of state that explains what the miner did and
 * why, plus the uptime history and the persistent log. Contains no tokens (only whether they
 * exist, when they expire and their format prefix).
 */
object ReportBuilder {
    private const val MINUTE = 60_000L
    private const val LOG_LINES = 6000
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val dayHour = SimpleDateFormat("MM-dd HH:00", Locale.US)

    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val app = DropSpikeApp.instance
        val prefs = app.prefs
        val status = MinerState.status.value
        val now = System.currentTimeMillis()
        val out = StringBuilder()

        fun section(title: String) = out.append("\n==== ").append(title).append(" ====\n")
        fun kv(k: String, v: Any?) = out.append("  ").append(k).append(": ").append(v).append('\n')
        fun line(s: String) {
            out.append("  ").append(s).append('\n')
        }

        out.append("DropSpike diagnostics report\n")
        kv("Generated", SystemInfo.time(now))
        kv("App", SystemInfo.appVersion(context))
        kv("Device", SystemInfo.device())
        kv("WebView", SystemInfo.webView())
        kv("Process", "pid ${Process.myPid()}, up ${(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60_000} min")

        section("Account")
        kv("Signed in as", prefs.login ?: "not signed in")
        kv("User id", prefs.userId ?: "?")
        kv("Client id", prefs.clientId + if (prefs.clientId == Prefs.WEB_CLIENT_ID) " (web)" else "")
        kv("Auth token stored", prefs.authToken != null)
        kv("Device id", prefs.deviceId.take(6) + "…")

        section("Integrity token")
        val integrity = prefs.integrityToken
        kv("Stored", integrity != null)
        if (integrity != null) {
            kv("Format", integrity.split('.').take(2).joinToString(".").take(16) + "…")
            kv("Expires", "${SystemInfo.time(prefs.integrityExpiry)} (${(prefs.integrityExpiry - now) / MINUTE} min from now)")
            kv("Fresh enough for background use", IntegrityRenewer.isFresh(prefs))
        }

        section("Settings")
        kv("Watched games", prefs.watchedGames.mapIndexed { i, g -> "${i + 1}. ${g.name} [${g.slug}]" }.joinToString("  ").ifEmpty { "none" })
        kv("Only mine watched games", prefs.onlyWatched)
        kv("Background check", if (prefs.wakeIntervalMin == 0) "off" else "every ${prefs.wakeIntervalMin} min")
        kv("Game list", "${prefs.gameCatalog.size} games, updated ${SystemInfo.time(prefs.gameCatalogAt)}")

        section("Power, permissions, network")
        SystemInfo.power(context).forEach { (k, v) -> kv(k, v) }

        section("Background check (WorkManager)")
        kv("WorkManager", WakeWorker.describe(context))
        kv("Last check", prefs.lastWakeCheck.ifEmpty { "none yet" })

        section("Mining service")
        kv("Running", status.running)
        kv("Session started", SystemInfo.time(prefs.sessionStartedAt))
        kv("Last tick", SystemInfo.time(prefs.lastTickAt) + if (prefs.lastTickAt > 0) " (${(now - prefs.lastTickAt) / 1000}s ago)" else "")
        kv("Ticks this process", status.ticks)
        kv("Largest gap between ticks", "${status.maxGapSec}s")
        kv("Ended without Stop (killed?)", !status.running && prefs.sessionActive)
        kv("Doing", status.summary.ifEmpty { "—" })
        status.detail.forEach { (k, v) -> kv(k, v) }

        val entries = UptimeLog.entries.value.sortedBy { it.atMs }
        uptimeHourly(entries, now, status.running, ::line)
        uptimeDetail(entries, now, ::line)

        section("Inventory snapshot (fetched now)")
        val inventory = withTimeoutOrNull(20_000) { runCatching { app.api.inventoryCampaigns() } }
        when {
            prefs.authToken == null -> line("not signed in")
            inventory == null -> line("timed out")
            inventory.isFailure -> line("failed: ${inventory.exceptionOrNull()?.message}")
            else -> inventory.getOrThrow().sortedBy { it.gameName.lowercase() }.forEach { c ->
                line(
                    "${c.gameName} · ${c.name} · ends ${SystemInfo.time(c.endsAtMs)}" +
                        (if (c.allowedLogins.isNotEmpty()) " · ${c.allowedLogins.size} allowed channels" else ""),
                )
                c.drops.forEach { d ->
                    val state = when {
                        d.claimed -> "claimed"
                        d.done -> "DONE, unclaimed"
                        !d.preconditionsMet -> "locked (needs earlier drop)"
                        else -> "open"
                    }
                    line("    - ${d.name}: ${d.minutes}/${d.required} min, $state")
                }
            }
        }

        section("How previous processes ended")
        SystemInfo.exitReasons(context).forEach(::line)

        section("Log (last $LOG_LINES lines, persistent across restarts)")
        DiagLog.readAll().takeLast(LOG_LINES).forEach { out.append(it).append('\n') }
        out.toString()
    }

    /** Per-hour counts for the last 24h, with missed minutes derived from gaps between ticks. */
    private fun uptimeHourly(entries: List<MinuteEntry>, now: Long, running: Boolean, line: (String) -> Unit) {
        line("")
        line("---- Uptime by hour (last 24h) ----")
        val start = now - 24 * 60 * MINUTE
        val missed = missedMinutes(entries, now, running).filter { it >= start }
        val inWindow = entries.filter { it.atMs >= start }
        if (inWindow.isEmpty() && missed.isEmpty()) {
            line("no mining recorded in the last 24h")
            return
        }
        line("hour         credited  sent  failed  idle  missed")
        val byHour = inWindow.groupBy { it.atMs / (60 * MINUTE) }
        val missedByHour = missed.groupingBy { it / (60 * MINUTE) }.eachCount()
        (byHour.keys + missedByHour.keys).toSortedSet().forEach { h ->
            val es = byHour[h].orEmpty()
            fun n(s: MinuteStatus) = es.count { it.status == s }
            line(
                "%s   %8d  %4d  %6d  %4d  %6d".format(
                    dayHour.format(Date(h * 60 * MINUTE)),
                    n(MinuteStatus.Credited), n(MinuteStatus.Sent), n(MinuteStatus.Failed), n(MinuteStatus.Idle), missedByHour[h] ?: 0,
                ),
            )
        }
        val total = inWindow.size + missed.size
        val credited = inWindow.count { it.status == MinuteStatus.Credited }
        line("total: $total minutes tracked, $credited credited (${if (total > 0) credited * 100 / total else 0}%), ${missed.size} missed")
    }

    /** Every recorded minute for the last 6h, with gaps called out. */
    private fun uptimeDetail(entries: List<MinuteEntry>, now: Long, line: (String) -> Unit) {
        line("")
        line("---- Uptime minute by minute (last 6h) ----")
        val recent = entries.filter { it.atMs >= now - 6 * 60 * MINUTE }
        if (recent.isEmpty()) {
            line("nothing recorded")
            return
        }
        var previous: MinuteEntry? = null
        for (e in recent) {
            previous?.let { p ->
                val gap = e.atMs - p.atMs
                if (gap > 90_000) line("        … ${gap / MINUTE} min gap ${if (gap < 3 * 60 * MINUTE) "(MISSED: service not ticking)" else "(stopped)"} …")
            }
            line("${hm.format(Date(e.atMs))}  ${e.status.name.padEnd(8)}  ${e.note}")
            previous = e
        }
    }

    /** Minute timestamps where a tick should have happened but didn't (same rule as the chart). */
    private fun missedMinutes(entries: List<MinuteEntry>, now: Long, running: Boolean): List<Long> {
        val out = mutableListOf<Long>()
        fun fill(from: Long, to: Long) {
            var t = from + MINUTE
            while (t < to) { out += t; t += MINUTE }
        }
        entries.zipWithNext().forEach { (a, b) ->
            val gap = b.atMs - a.atMs
            if (gap > 90_000 && gap < 3 * 60 * MINUTE) fill(a.atMs, b.atMs)
        }
        entries.lastOrNull()?.let { if (running && now - it.atMs > 90_000) fill(it.atMs, now) }
        return out
    }

    /** Writes the report to a shareable file (see the FileProvider in the manifest). */
    fun writeFile(context: Context, text: String): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return File(dir, "dropspike-report-$stamp.txt").apply { writeText(text) }
    }
}
