package dev.dropspike.service

import android.content.Context
import dev.dropspike.data.ClaimEntry
import dev.dropspike.data.ClaimLog
import dev.dropspike.data.DiagLog
import dev.dropspike.data.MinuteStatus
import dev.dropspike.data.Prefs
import dev.dropspike.data.SystemInfo
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
/** Snapshot of the current target for the UI. [drop] is null until Twitch starts the campaign. */
data class MiningNow(
    val game: WatchedGame,
    val channel: String,
    val viewers: Int,
    val campaign: String?,
    val drop: String?,
    val minutes: Int,
    val required: Int,
    val since: Long,
)

class Miner(private val context: Context, private val prefs: Prefs, private val api: TwitchApi) {

    private data class Target(val game: WatchedGame, val stream: LiveStream, val spadeUrl: String)

    /** One channel being watched, with its own credit bookkeeping. */
    private class Slot(val target: Target, var lastMinutes: Int) {
        var withoutCredit = 0
        var onTarget = 0
        var credited = false
        val since = System.currentTimeMillis()
        /** The stream's lowest-quality playlist, fetched on first watch and after a failure. */
        var playlist: String? = null
        /** Successful playlist watches this minute (the service pulses every ~20 s). */
        var watches = 0
    }

    /** What is being mined right now, for the home screen (the first channel). */
    var now: MiningNow? = null
        private set

    /** The second channel, when "Watch two channels" is on and a second game is live. */
    var second: MiningNow? = null
        private set

    /** The inventory as of the last tick, so the UI doesn't have to fetch it again. */
    var campaigns: List<InvCampaign> = emptyList()
        private set

    private val slots = mutableListOf<Slot>()

    /** Games already reported as earning without a linked account (logged once each). */
    private val unlinkedEarning = mutableSetOf<String>()

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

    private val maxSlots get() = if (prefs.twoChannels) 2 else 1

    /** Internal state, for the report. */
    fun detail(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val perSlot = slots.flatMapIndexed { i, s ->
            val t = s.target
            val p = if (slots.size > 1) "Channel ${i + 1}: " else ""
            listOf(
                "${p}Target game" to "${t.game.name} (id ${t.game.id.ifEmpty { "?" }}, slug ${t.game.slug})",
                "${p}Channel" to "${t.stream.login} (channel ${t.stream.channelId}, broadcast ${t.stream.broadcastId}, ${t.stream.viewers} viewers, playing ${t.stream.gameName})",
                "${p}Spade host" to android.net.Uri.parse(t.spadeUrl).host.orEmpty(),
                "${p}Unclaimed minutes at last reading" to s.lastMinutes.toString(),
                "${p}Minutes without credit" to s.withoutCredit.toString(),
                "${p}Minutes on this channel" to s.onTarget.toString(),
            )
        }
        return listOf("Channels" to "${slots.size} of $maxSlots") +
            (perSlot.ifEmpty { listOf("Target game" to "none") }) +
            listOf(
                "Idle ticks in a row" to idleTicks.toString(),
                "Games cooling down" to cooldown.filterValues { it > now }.entries.joinToString { "${it.key} until ${SystemInfo.time(it.value)}" }.ifEmpty { "none" },
                "Claims attempted" to claimAttempts.entries.joinToString { "${it.key.take(8)}… at ${SystemInfo.time(it.value)}" }.ifEmpty { "none" },
                "Claims Twitch accepts but hasn't delivered" to prefs.stuckClaims.entries.joinToString { "${it.key.take(8)}… ×${it.value.first}" }.ifEmpty { "none" },
            )
    }

    suspend fun tick(): Pair<MinuteStatus, String> {
        val campaigns = try {
            api.inventoryCampaigns()
        } catch (e: Exception) {
            return MinuteStatus.Failed to "Inventory failed: ${e.message}"
        }
        this.campaigns = campaigns
        val openCount = campaigns.sumOf { openDrops(it).size }
        val doneUnclaimed = campaigns.sumOf { c -> c.drops.count { it.done && !it.claimed } }
        DiagLog.i("miner: inventory ${campaigns.size} campaigns, $openCount open drops, $doneUnclaimed finished-unclaimed")
        claimFinished(campaigns)

        // Credit check per channel: did the game's unclaimed minutes go up since the last reading?
        for (slot in slots.toList()) {
            val reason = checkCredit(slot, campaigns)
            if (reason != null) {
                DiagLog.i("miner: leaving ${slot.target.stream.login} (${slot.target.game.name}): $reason")
                slots.remove(slot)
            }
        }
        // Turning "two channels" off mid-session drops the second one.
        while (slots.size > maxSlots) slots.removeAt(slots.lastIndex).also { DiagLog.i("miner: leaving ${it.target.stream.login}: two channels turned off") }

        while (slots.size < maxSlots) {
            val taken = slots.map { key(it.target.game) }.toSet()
            val t = select(campaigns, exclude = taken) ?: break
            slots += Slot(t, lastMinutes = gameMinutes(campaigns, t.game))
            DiagLog.i("miner: watching ${t.stream.login} for ${t.game.name}${if (slots.size > 1) " (second channel)" else ""}")
        }

        if (slots.isEmpty()) {
            now = null
            second = null
            idleTicks++
            describe = "Nothing to mine right now"
            return MinuteStatus.Idle to "No watched or in-progress game has a live drops channel"
        }
        idleTicks = 0

        val notes = mutableListOf<String>()
        var anyCredited = false
        var anySent = false
        for (slot in slots.toList()) {
            val t = slot.target
            slot.onTarget++
            val progress = "${t.game.name} · ${progressText(campaigns, t.game)} via ${t.stream.login}"
            // Watching is the playlist pulse (watch() here, then twice more before the next tick).
            // The minute-watched heartbeat no longer earns drops but is still what the web player
            // sends, so it's kept, best effort.
            runCatching { api.sendMinuteWatched(t.spadeUrl, t.stream) }
                .onFailure { DiagLog.i("miner: heartbeat ${t.stream.login} failed: ${it.message}") }
            val watched = watch(slot)
            DiagLog.i("miner: ${t.stream.login}: ${slot.watches} playlist watch(es) in the last minute${if (watched) "" else ", latest failed"}")
            val ok = slot.watches > 0
            slot.watches = 0
            if (!ok) {
                slots.remove(slot)
                notes += "Couldn't watch ${t.stream.login}'s stream · $progress"
                continue
            }
            anySent = true
            if (slot.credited) anyCredited = true
            notes += progress
        }
        now = slots.getOrNull(0)?.let { snapshot(it, campaigns, now) }
        second = slots.getOrNull(1)?.let { snapshot(it, campaigns, second) }
        describe = slots.joinToString(" + ") { "${it.target.game.name} · ${progressText(campaigns, it.target.game)}" }.ifEmpty { "Finding a channel" }
        val note = notes.joinToString(" + ")
        return when {
            anyCredited -> MinuteStatus.Credited to note
            anySent -> MinuteStatus.Sent to note
            else -> MinuteStatus.Failed to note
        }
    }

    /** Watches every current channel once; the service calls this between minute ticks. */
    suspend fun pulse() {
        for (slot in slots.toList()) watch(slot)
    }

    /** One playlist watch for [slot]: fetch the playlist URL if needed, then HEAD its chunks. */
    private suspend fun watch(slot: Slot): Boolean {
        val login = slot.target.stream.login
        return try {
            val url = slot.playlist ?: api.streamPlaylistUrl(login)?.also { slot.playlist = it }
                ?: throw java.io.IOException("no playable stream (offline?)")
            api.watchPlaylist(url)
            slot.watches++
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            slot.playlist = null // the URL expires; fetch a fresh one next time
            DiagLog.i("miner: playlist watch $login failed: ${e.message}")
            false
        }
    }

    /** Updates [slot]'s credit bookkeeping; returns why to leave the channel, or null to stay. */
    private suspend fun checkCredit(slot: Slot, campaigns: List<InvCampaign>): String? {
        val t = slot.target
        val minutes = gameMinutes(campaigns, t.game)
        slot.credited = slot.lastMinutes >= 0 && minutes > slot.lastMinutes
        // Evidence for "unlinked accounts still earn": say so the first time it happens per game.
        if (slot.credited && unlinkedEarning.add(key(t.game))) {
            val unlinkedCampaigns = campaigns.filter { t.game.matches(it.gameId, it.gameName) && !it.accountConnected }
            val watchedNeedsLink = prefs.watchedGames.any { it.needsLink && it.matches(t.game.id, t.game.name) }
            if (unlinkedCampaigns.isNotEmpty() || watchedNeedsLink) {
                DiagLog.i(
                    "miner: ${t.game.name} credited WITHOUT a linked game account (${unlinkedCampaigns.joinToString { it.name }.ifEmpty { "campaign not in inventory yet" }}): " +
                        "Twitch counts the time; the drops will need linking to claim",
                )
            }
        }
        slot.withoutCredit = if (slot.credited) 0 else slot.withoutCredit + 1
        DiagLog.i("miner: ${t.game.name} unclaimed minutes ${slot.lastMinutes} → $minutes (${if (slot.credited) "credited" else "no change"}), ${slot.withoutCredit} min without credit, ${slot.onTarget} min on ${t.stream.login}")
        slot.lastMinutes = minutes
        if (slot.withoutCredit == SESSION_CHECK_AT || slot.withoutCredit == SESSION_CHECK_AT * 2) {
            // Is Twitch still counting towards something else, e.g. a finished drop (the 4171/30 case)?
            val session = runCatching { api.currentDrop(t.stream.channelId) }
            DiagLog.i("miner: ${slot.withoutCredit} min without credit on ${t.stream.login}; Twitch current drop session: ${session.getOrNull() ?: session.exceptionOrNull()?.message ?: "none"}")
            val s = session.getOrNull()
            val otherGame = s?.gameName?.let { !t.game.matches("", it) } == true
            if (s != null && (s.stuck || otherGame)) {
                cooldown[key(t.game)] = System.currentTimeMillis() + COOLDOWN_MS
                return if (s.stuck) "Twitch session stuck on a finished drop ($s)" else "Twitch is counting another game (${s.gameName})"
            }
        }
        return when {
            // Twitch credits in bursts (sometimes 6+ minutes apart), so give it a while before giving up.
            slot.withoutCredit >= NO_CREDIT_LIMIT -> "no progress in $NO_CREDIT_LIMIT min".also { cooldown[key(t.game)] = System.currentTimeMillis() + COOLDOWN_MS }
            // Everything for this game is earned (and it isn't just not started yet).
            hasCampaign(campaigns, t.game) && !hasOpenDrops(campaigns, t.game) -> "all drops earned"
            // Re-check the pick periodically: the channel may have gone offline or changed game.
            slot.onTarget >= 30 -> "periodic re-check"
            else -> null
        }
    }

    private fun snapshot(slot: Slot, campaigns: List<InvCampaign>, previous: MiningNow?): MiningNow {
        val t = slot.target
        val drop = campaigns.filter { t.game.matches(it.gameId, it.gameName) }
            .flatMap { c -> openDrops(c).map { c to it } }
            .maxByOrNull { (_, d) -> d.minutes.toFloat() / d.required }
        return MiningNow(
            game = t.game.copy(id = t.game.id.ifEmpty { t.stream.gameId }),
            channel = t.stream.login,
            viewers = t.stream.viewers,
            campaign = drop?.first?.name,
            drop = drop?.second?.name,
            minutes = drop?.second?.minutes ?: 0,
            required = drop?.second?.required ?: 0,
            since = previous?.takeIf { it.channel == t.stream.login }?.since ?: slot.since,
        )
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
            // A watched game is worth trying unless its campaigns are in the inventory and fully earned,
            // or (with "mine unlinked" off) none of its campaigns can be claimed without a linked account.
            (!hasCampaign(campaigns, it) || hasOpenDrops(campaigns, it)) &&
                (prefs.mineUnlinked || !(it.needsLink && !hasCampaign(campaigns, it)))
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

    private suspend fun select(campaigns: List<InvCampaign>, exclude: Set<String> = emptySet()): Target? {
        val list = candidates(campaigns).filter { key(it) !in exclude }
        val now = System.currentTimeMillis()
        val cooling = cooldown.filterValues { it > now }.keys
        val unlinked = if (prefs.mineUnlinked) emptyList() else prefs.watchedGames.filter { it.needsLink && !hasCampaign(campaigns, it) }
        DiagLog.i(
            "miner: choosing from ${list.size} candidate game(s): ${list.joinToString { it.name }.ifEmpty { "none" }}" +
                (if (unlinked.isNotEmpty()) " · skipped, account not linked: ${unlinked.joinToString { it.name }}" else "") +
                (if (cooling.isNotEmpty()) " · cooling down: ${cooling.joinToString()}" else "") +
                " · watched=${prefs.watchedGames.size}, onlyWatched=${prefs.onlyWatched}",
        )
        for (game in list) {
            val stream = runCatching { findStream(game, campaigns) }.getOrElse {
                DiagLog.i("miner: channel search for ${game.name} failed: ${it.message}")
                null
            } ?: run {
                DiagLog.i("miner: ${game.name}: no live drops-enabled channel")
                null
            } ?: continue
            DiagLog.i("miner: ${game.name}: picked ${stream.login} (${stream.viewers} viewers, broadcast ${stream.broadcastId}, playing ${stream.gameName})")
            val spade = runCatching { api.spadeUrl(stream.login) }.getOrElse {
                DiagLog.i("miner: spade URL for ${stream.login} failed: ${it.message}")
                null
            } ?: continue
            DiagLog.i("miner: ${stream.login}: spade endpoint ${android.net.Uri.parse(spade).host}")
            return Target(game, stream, spade)
        }
        return null
    }

    private suspend fun findStream(game: WatchedGame, campaigns: List<InvCampaign>): LiveStream? {
        // If an in-progress campaign for this game only counts certain channels, use one of those.
        val acl = campaigns.filter { game.matches(it.gameId, it.gameName) && openDrops(it).isNotEmpty() }
            .flatMap { it.allowedLogins }.distinct()
        if (acl.isNotEmpty()) {
            DiagLog.i("miner: ${game.name}: campaign restricted to ${acl.size} channel(s), checking ${acl.take(15).joinToString()}")
            for (login in acl.take(15)) {
                val s = runCatching { api.streamInfo(login) }.getOrNull()
                if (s == null) continue
                if (game.matches(s.gameId, s.gameName)) return s
                DiagLog.i("miner: ${game.name}: $login is live but playing ${s.gameName}")
            }
            return null
        }
        val streams = api.liveStreams(game)
        DiagLog.i("miner: ${game.name} (slug ${game.slug}): ${streams.size} live drops-enabled channel(s)${streams.take(3).joinToString(prefix = if (streams.isEmpty()) "" else ": ") { "${it.login}/${it.viewers}" }}")
        return streams.firstOrNull()
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
        var stuck = prefs.stuckClaims
        val present = campaigns.flatMap { c -> c.drops.map { it.id } }.toSet()

        // Claims are recorded once Twitch's inventory confirms them, not when the mutation answers:
        // Twitch can answer ELIGIBLE_FOR_ALL and still never deliver the drop.
        for (c in campaigns) for (d in c.drops) {
            if (d.claimed && stuck.containsKey(d.id)) {
                ClaimLog.record(ClaimEntry(now, c.gameName, c.gameId, c.name, d.name, d.imageUrl, "CLAIMED"))
                DiagLog.i("miner: ${c.gameName} · ${d.name} is now claimed")
                stuck = stuck - d.id
            }
        }
        stuck = stuck.filterKeys { it in present } // campaign ended or drop gone

        val finished = campaigns.flatMap { c -> c.drops.filter { it.done && !it.claimed && it.instanceId != null }.map { c to it } }
            .filter { (_, d) -> now - (claimAttempts[d.id] ?: 0L) > COOLDOWN_MS && (stuck[d.id]?.second ?: 0L) <= now }
        if (finished.isEmpty()) {
            prefs.stuckClaims = stuck
            return
        }
        DiagLog.i("miner: ${finished.size} finished drop(s) to claim: ${finished.joinToString { (c, d) -> "${c.gameName} · ${d.name}" }}")
        val origin = api.defaultOrigin().url
        if (!IntegrityRenewer.ensureFresh(context, prefs, origin)) {
            DiagLog.i("miner: ${finished.size} drop(s) ready to claim, but no integrity token")
            prefs.stuckClaims = stuck
            return
        }
        var reminted = false
        for ((c, d) in finished) {
            val id = d.instanceId ?: continue
            claimAttempts[d.id] = now
            var result = runCatching { api.claimDrop(id) }
            // The token was rejected (TwitchApi has discarded it): mint a new one once and retry.
            if (!reminted && result.exceptionOrNull()?.message?.contains("IntegrityCheckFailed") == true) {
                reminted = true
                DiagLog.i("miner: claim rejected the integrity token; minting a fresh one and retrying")
                if (IntegrityRenewer.ensureFresh(context, prefs, origin)) result = runCatching { api.claimDrop(id) }
            }
            DiagLog.i("miner: claim ${c.gameName} · ${d.name} → ${result.getOrElse { it.message }}")
            if (result.exceptionOrNull()?.message?.contains("IntegrityCheckFailed") == true) {
                DiagLog.i("miner: integrity still rejected after renewing; trying again later")
                break
            }
            val status = result.getOrNull() ?: continue
            if (status != "ELIGIBLE_FOR_ALL" && status != "DROP_INSTANCE_ALREADY_CLAIMED") continue
            // Accepted. If this keeps happening without the drop becoming claimed, stop retrying
            // every 30 minutes and tell the user: Twitch won't deliver it from here.
            val attempts = (stuck[d.id]?.first ?: 0) + 1
            val next = if (attempts >= STUCK_AFTER) now + STUCK_RETRY_MS else 0L
            stuck = stuck + (d.id to (attempts to next))
            if (attempts == STUCK_AFTER) {
                DiagLog.w(
                    "miner: Twitch accepted the claim for ${c.gameName} · ${d.name} $attempts times but hasn't delivered it" +
                        (if (!c.accountConnected) "; the game account isn't linked (${c.linkUrl ?: "no link URL"})" else "") +
                        "; retrying every ${STUCK_RETRY_MS / 3_600_000} h",
                )
            }
        }
        prefs.stuckClaims = stuck
    }

    companion object {
        private const val COOLDOWN_MS = 30 * 60 * 1000L
        private const val STUCK_AFTER = 3
        private const val STUCK_RETRY_MS = 6 * 60 * 60 * 1000L

        /** Twitch keeps accepting claims for this drop without delivering it (see claimFinished). */
        fun isStuck(prefs: Prefs, dropId: String) = (prefs.stuckClaims[dropId]?.first ?: 0) >= STUCK_AFTER
        private const val NO_CREDIT_LIMIT = 10
        private const val SESSION_CHECK_AT = 3
    }
}
