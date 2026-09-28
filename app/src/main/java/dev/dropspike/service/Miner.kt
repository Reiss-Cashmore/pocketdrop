package dev.dropspike.service

import android.content.Context
import dev.dropspike.data.DiagLog
import dev.dropspike.data.MinuteStatus
import dev.dropspike.data.Prefs
import dev.dropspike.twitch.IntegrityRenewer
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.twitch.LiveStream
import dev.dropspike.twitch.TwitchApi
import dev.dropspike.twitch.WatchedGame
import dev.dropspike.twitch.slugOf

/**
 * The mining loop, one [tick] per minute. A minimal port of DropForge's watch flow:
 * pick a game (watched games first, in the user's order, then the in-progress drop closest to
 * completion), find a live drops-enabled channel for it (respecting a campaign's channel
 * allow-list), and send that channel's "minute-watched" heartbeat.
 *
 * Progress is read back from the ungated inventory as the total unclaimed minutes for the
 * game, so a watched game whose campaign hasn't started yet is credited as soon as Twitch
 * adds it to the inventory. Finished drops are claimed (integrity-gated; the token is
 * renewed off screen first).
 */
class Miner(private val context: Context, private val prefs: Prefs, private val api: TwitchApi) {

    private data class Target(val game: WatchedGame, val stream: LiveStream, val spadeUrl: String)

    private var target: Target? = null
    private var lastMinutes = -1
    private var ticksWithoutCredit = 0
    private var ticksOnTarget = 0

    /** Game key → until when it is skipped because watching it earned nothing. */
    private val cooldown = mutableMapOf<String, Long>()

    /** instanceId → when we last tried to claim it, so a failing claim isn't retried every minute. */
    private val claimAttempts = mutableMapOf<String, Long>()

    /** Consecutive ticks with nothing to mine (the service uses it to stop itself). */
    var idleTicks = 0
        private set

    /** What the miner is doing, for the UI. */
    var describe: String = "Starting"
        private set

    suspend fun tick(): Pair<MinuteStatus, String> {
        val campaigns = try {
            api.inventoryCampaigns()
        } catch (e: Exception) {
            return MinuteStatus.Failed to "Inventory failed: ${e.message}"
        }
        claimFinished(campaigns)

        // Credit check: did the game's unclaimed minutes go up since the last reading?
        var credited = false
        target?.let { t ->
            val minutes = gameMinutes(campaigns, t.game)
            if (lastMinutes >= 0 && minutes > lastMinutes) credited = true
            lastMinutes = minutes
            ticksWithoutCredit = if (credited) 0 else ticksWithoutCredit + 1
            val reason = when {
                // Twitch credits in bursts, but 6 minutes without progress means this isn't counting.
                ticksWithoutCredit >= 6 -> "no progress in 6 min".also { cooldown[key(t.game)] = System.currentTimeMillis() + COOLDOWN_MS }
                // Everything for this game is earned (and it isn't just not started yet).
                hasCampaign(campaigns, t.game) && !hasOpenDrops(campaigns, t.game) -> "all drops earned"
                // Re-check the pick periodically: the channel may have gone offline or changed game.
                ticksOnTarget >= 30 -> "periodic re-check"
                else -> null
            }
            if (reason != null) {
                DiagLog.i("miner: leaving ${t.stream.login} (${t.game.name}): $reason")
                target = null
            }
        }

        val t = target ?: select(campaigns)?.also {
            target = it
            lastMinutes = gameMinutes(campaigns, it.game)
            ticksWithoutCredit = 0
            ticksOnTarget = 0
            DiagLog.i("miner: watching ${it.stream.login} for ${it.game.name}")
        }
        if (t == null) {
            idleTicks++
            describe = "Nothing to mine right now"
            return MinuteStatus.Idle to "No watched or in-progress game has a live drops channel"
        }
        idleTicks = 0
        ticksOnTarget++

        val progress = "${t.game.name} · ${progressText(campaigns, t.game)} via ${t.stream.login}"
        describe = progress
        val code = try {
            api.sendMinuteWatched(t.spadeUrl, t.stream)
        } catch (e: Exception) {
            target = null
            return MinuteStatus.Failed to "Heartbeat failed: ${e.message} · $progress"
        }
        if (code != 204) {
            target = null
            return MinuteStatus.Failed to "Heartbeat HTTP $code · $progress"
        }
        return (if (credited) MinuteStatus.Credited else MinuteStatus.Sent) to progress
    }

    /** Is anything minable right now? Used by the background wake check; sends nothing. */
    suspend fun hasWork(): String? {
        val campaigns = api.inventoryCampaigns()
        for (game in candidates(campaigns)) {
            val stream = runCatching { findStream(game, campaigns) }.getOrNull() ?: continue
            return "${game.name} is live on ${stream.login}"
        }
        return null
    }

    /** Watched games first (user order), then in-progress games closest to finishing. */
    private fun candidates(campaigns: List<InvCampaign>): List<WatchedGame> {
        val now = System.currentTimeMillis()
        val watched = prefs.watchedGames.filter {
            // A watched game is worth trying unless its campaigns are in the inventory and fully earned.
            !hasCampaign(campaigns, it) || hasOpenDrops(campaigns, it)
        }
        val inProgress = if (prefs.onlyWatched) emptyList() else campaigns
            .filter { it.endsAtMs > now }
            .flatMap { c -> openDrops(c).map { c to it } }
            .sortedByDescending { (_, d) -> d.minutes.toFloat() / d.required }
            .map { (c, _) -> WatchedGame(c.gameId, c.gameName, c.gameSlug ?: slugOf(c.gameName)) }
        return (watched + inProgress)
            .distinctBy { key(it) }
            .filter { (cooldown[key(it)] ?: 0L) < now }
    }

    private suspend fun select(campaigns: List<InvCampaign>): Target? {
        for (game in candidates(campaigns)) {
            val stream = runCatching { findStream(game, campaigns) }.getOrElse {
                DiagLog.i("miner: channel search for ${game.name} failed: ${it.message}")
                null
            } ?: continue
            val spade = runCatching { api.spadeUrl(stream.login) }.getOrElse {
                DiagLog.i("miner: spade URL for ${stream.login} failed: ${it.message}")
                null
            } ?: continue
            return Target(game, stream, spade)
        }
        return null
    }

    private suspend fun findStream(game: WatchedGame, campaigns: List<InvCampaign>): LiveStream? {
        // If an in-progress campaign for this game only counts certain channels, use one of those.
        val acl = campaigns.filter { game.matches(it.gameId, it.gameName) && openDrops(it).isNotEmpty() }
            .flatMap { it.allowedLogins }.distinct()
        if (acl.isNotEmpty()) {
            for (login in acl.take(15)) {
                val s = runCatching { api.streamInfo(login) }.getOrNull() ?: continue
                if (game.matches(s.gameId, s.gameName)) return s
            }
            return null
        }
        return api.liveStreams(game).firstOrNull()
    }

    private fun openDrops(c: InvCampaign) = c.drops.filter {
        !it.claimed && !it.done && it.preconditionsMet && it.required > 0 && it.endsAtMs > System.currentTimeMillis()
    }

    private fun hasCampaign(campaigns: List<InvCampaign>, game: WatchedGame) =
        campaigns.any { game.matches(it.gameId, it.gameName) }

    private fun hasOpenDrops(campaigns: List<InvCampaign>, game: WatchedGame) =
        campaigns.any { game.matches(it.gameId, it.gameName) && openDrops(it).isNotEmpty() }

    private fun gameMinutes(campaigns: List<InvCampaign>, game: WatchedGame) =
        campaigns.filter { game.matches(it.gameId, it.gameName) }.sumOf { c -> c.drops.filter { !it.claimed }.sumOf { it.minutes } }

    private fun progressText(campaigns: List<InvCampaign>, game: WatchedGame): String {
        val drop = campaigns.filter { game.matches(it.gameId, it.gameName) }.flatMap { openDrops(it) }
            .maxByOrNull { it.minutes.toFloat() / it.required }
        return drop?.let { "${it.name} ${it.minutes}/${it.required}" } ?: "campaign not started yet"
    }

    private fun key(g: WatchedGame) = g.id.ifEmpty { g.name.lowercase() }

    private suspend fun claimFinished(campaigns: List<InvCampaign>) {
        val now = System.currentTimeMillis()
        val finished = campaigns.flatMap { c -> c.drops.filter { it.done && !it.claimed && it.instanceId != null }.map { c to it } }
            .filter { (_, d) -> now - (claimAttempts[d.instanceId] ?: 0L) > COOLDOWN_MS }
        if (finished.isEmpty()) return
        if (!IntegrityRenewer.ensureFresh(context, prefs, api.defaultOrigin().url)) {
            DiagLog.i("miner: ${finished.size} drop(s) ready to claim, but no integrity token")
            return
        }
        for ((c, d) in finished) {
            val id = d.instanceId ?: continue
            claimAttempts[id] = now
            val result = runCatching { api.claimDrop(id) }
            DiagLog.i("miner: claim ${c.gameName} · ${d.name} → ${result.getOrElse { it.message }}")
        }
    }

    companion object {
        private const val COOLDOWN_MS = 30 * 60 * 1000L
    }
}
