package dev.dropspike.service

import android.content.Context
import dev.dropspike.data.DiagLog
import dev.dropspike.data.MinuteStatus
import dev.dropspike.data.Prefs
import dev.dropspike.twitch.IntegrityRenewer
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.twitch.InvDrop
import dev.dropspike.twitch.LiveStream
import dev.dropspike.twitch.TwitchApi

/**
 * The mining loop, one [tick] per minute. A minimal port of DropForge's watch flow:
 * pick the unfinished drop closest to completion, find a live drops-enabled channel for its
 * game (respecting the campaign's channel allow-list), and send that channel's
 * "minute-watched" heartbeat. Progress is read back from the (ungated) inventory, and
 * finished drops are claimed (integrity-gated, so the token is renewed off screen first).
 */
class Miner(private val context: Context, private val prefs: Prefs, private val api: TwitchApi) {

    private data class Target(val campaign: InvCampaign, val drop: InvDrop, val stream: LiveStream, val spadeUrl: String)

    private var target: Target? = null
    private var lastMinutes = -1
    private var ticksWithoutCredit = 0
    private var ticksOnTarget = 0
    /** instanceId → when we last tried to claim it, so a failing claim isn't retried every minute. */
    private val claimAttempts = mutableMapOf<String, Long>()

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

        // Credit check: did the drop we were watching move since the last reading?
        var credited = false
        target?.let { t ->
            val drop = campaigns.firstOrNull { it.id == t.campaign.id }?.drops?.firstOrNull { it.id == t.drop.id }
            if (drop != null && lastMinutes >= 0 && drop.minutes > lastMinutes) credited = true
            lastMinutes = drop?.minutes ?: -1
            ticksWithoutCredit = if (credited) 0 else ticksWithoutCredit + 1
            val stale = drop == null || drop.claimed || drop.done ||
                // Twitch credits in bursts, but 6 minutes without progress means this channel isn't counting.
                ticksWithoutCredit >= 6 ||
                // Re-check the pick periodically: the channel may have gone offline or changed game.
                ticksOnTarget >= 30
            if (stale) {
                DiagLog.i("miner: leaving ${t.stream.login} (${if (drop?.done == true) "drop finished" else if (ticksWithoutCredit >= 6) "no progress" else "periodic re-check"})")
                target = null
            } else if (drop != null) {
                target = t.copy(drop = drop)
            }
        }

        val t = target ?: select(campaigns)?.also {
            target = it
            lastMinutes = it.drop.minutes
            ticksWithoutCredit = 0
            ticksOnTarget = 0
            DiagLog.i("miner: watching ${it.stream.login} for ${it.campaign.gameName} · ${it.drop.name} (${it.drop.minutes}/${it.drop.required})")
        }
        if (t == null) {
            describe = "Nothing to mine right now"
            return MinuteStatus.Idle to "No unfinished drop with a live channel (${campaigns.size} campaigns in progress)"
        }
        ticksOnTarget++

        val progress = "${t.campaign.gameName} · ${t.drop.name} ${lastMinutes.coerceAtLeast(0)}/${t.drop.required} via ${t.stream.login}"
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

    private suspend fun select(campaigns: List<InvCampaign>): Target? {
        val now = System.currentTimeMillis()
        val candidates = campaigns
            .filter { it.endsAtMs > now }
            .flatMap { c ->
                c.drops.filter { !it.claimed && !it.done && it.preconditionsMet && it.required > 0 && it.endsAtMs > now }
                    .map { c to it }
            }
            .sortedByDescending { (_, d) -> d.minutes.toFloat() / d.required }
        for ((campaign, drop) in candidates.distinctBy { it.first.id }) {
            val stream = runCatching { findStream(campaign) }.getOrElse {
                DiagLog.i("miner: channel search for ${campaign.gameName} failed: ${it.message}")
                null
            } ?: continue
            val spade = runCatching { api.spadeUrl(stream.login) }.getOrElse {
                DiagLog.i("miner: spade URL for ${stream.login} failed: ${it.message}")
                null
            } ?: continue
            return Target(campaign, drop, stream, spade)
        }
        return null
    }

    private suspend fun findStream(campaign: InvCampaign): LiveStream? {
        if (campaign.allowedLogins.isNotEmpty()) {
            // Only these channels count; take the first one that is live on the right game.
            for (login in campaign.allowedLogins.take(15)) {
                val s = runCatching { api.streamInfo(login) }.getOrNull() ?: continue
                if (s.gameId == campaign.gameId || s.gameName.equals(campaign.gameName, ignoreCase = true)) return s
            }
            return null
        }
        return api.liveStreams(campaign).firstOrNull()
    }

    private suspend fun claimFinished(campaigns: List<InvCampaign>) {
        val now = System.currentTimeMillis()
        val finished = campaigns.flatMap { c -> c.drops.filter { it.done && !it.claimed && it.instanceId != null }.map { c to it } }
            .filter { (_, d) -> now - (claimAttempts[d.instanceId] ?: 0L) > 30 * 60 * 1000L }
        if (finished.isEmpty()) return
        if (!IntegrityRenewer.ensureFresh(context, prefs, api.defaultOrigin().url)) {
            DiagLog.i("miner: ${finished.size} drop(s) ready to claim, but no integrity token")
            return
        }
        for ((c, d) in finished) {
            claimAttempts[d.instanceId!!] = now
            val result = runCatching { api.claimDrop(d.instanceId!!) }
            DiagLog.i("miner: claim ${c.gameName} · ${d.name} → ${result.getOrElse { it.message }}")
        }
    }
}
