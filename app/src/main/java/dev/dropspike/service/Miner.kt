package dev.dropspike.service

import android.content.Context
import dev.dropspike.data.ClaimEntry
import dev.dropspike.data.ClaimLog
import dev.dropspike.data.DiagLog
import dev.dropspike.data.MinuteStatus
import dev.dropspike.data.Prefs
import dev.dropspike.data.SystemInfo
import dev.dropspike.twitch.DropSession
import dev.dropspike.twitch.IntegrityRenewer
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.twitch.InvDrop
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
        /** What Twitch's live session is counting for this game, while the inventory doesn't show it. */
        var live: DropSession? = null
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

    /**
     * Game key → minutes watched without credit while its campaign hasn't started on the account.
     * Per game rather than per channel, so switching channels doesn't reset it.
     */
    private val uncounted = mutableMapOf<String, Int>()

    /** Drop id → when we last tried to claim it outside the inventory (see claimDirect). */
    private val liveClaimAttempts = mutableMapOf<String, Long>()

    /** Game key → (fetched at, every active campaign with our progress); see gameDetails. */
    private val details = mutableMapOf<String, Pair<Long, List<InvCampaign>>>()

    /** Game key → the last channel Twitch credited it on; some campaigns only count certain channels. */
    private val creditedOn = mutableMapOf<String, String>()

    /** Game key → the live session last read for it, kept across channel switches. */
    private val lastLive = mutableMapOf<String, DropSession>()

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
                "Minutes not counted (campaign not started)" to uncounted.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" },
                "All drops earned (skipped)" to prefs.allEarned.filterValues { it > now }.entries.joinToString { "${it.key} until ${SystemInfo.time(it.value)}" }.ifEmpty { "none" },
                "Twitch not counting (skipped)" to prefs.notCounting.filterValues { it > now }.entries.joinToString { "${it.key} until ${SystemInfo.time(it.value)}" }.ifEmpty { "none" },
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
        forgetNotCounting(campaigns)

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
            slots += Slot(t, lastMinutes = gameMinutes(campaigns, t.game)).apply { live = lastLive[key(t.game)] }
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
            val progress = "${t.game.name} · ${progressText(campaigns, t.game, slot.live)} via ${t.stream.login}"
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
        describe = slots.joinToString(" + ") { "${it.target.game.name} · ${progressText(campaigns, it.target.game, it.live)}" }.ifEmpty { "Finding a channel" }
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
        val started = hasOpenDrops(campaigns, t.game)
        // Twitch's inventory can leave out a campaign it is counting (Escape from Tarkov and Hearthstone
        // in Oct 2026 showed 356/480 and 63/420 in the session while missing from the inventory), so
        // while the game has nothing open there, the live session is read every minute and is the credit.
        val session = if (started) null else runCatching { api.currentDrop(t.stream.channelId) }
        val live = session?.getOrNull()?.takeIf { it.required > 0 && t.game.matches("", it.gameName ?: t.game.name) }
        val previous = slot.live
        val liveCredited = live != null && (previous?.let { it.dropId != live.dropId || live.minutes > it.minutes } ?: false)
        slot.live = live
        if (live != null) lastLive[key(t.game)] = live else lastLive.remove(key(t.game))
        // The session went back to 0/0 right as a drop finished: that drop is waiting to be claimed
        // (Escape from Tarkov, Oct 2026: 479/480, then nothing counted until it was claimed).
        if (!started && live == null && previous != null && previous.minutes >= previous.required - 1) claimLive(t.game, previous.dropId)
        slot.credited = (slot.lastMinutes >= 0 && minutes > slot.lastMinutes) || liveCredited
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
        if (slot.credited) creditedOn[key(t.game)] = t.stream.login
        slot.withoutCredit = if (slot.credited) 0 else slot.withoutCredit + 1
        if (started || live != null) uncounted.remove(key(t.game)) else uncounted[key(t.game)] = (uncounted[key(t.game)] ?: 0) + 1
        DiagLog.i(
            "miner: ${t.game.name} unclaimed minutes ${slot.lastMinutes} → $minutes" +
                (if (started) "" else ", Twitch session ${live ?: "0/0"}") +
                " (${if (slot.credited) "credited" else "no change"}), ${slot.withoutCredit} min without credit, ${slot.onTarget} min on ${t.stream.login}",
        )
        slot.lastMinutes = minutes
        // A drop finished in the session but missing from the inventory has no claim button here; claim it directly.
        if (live != null && live.minutes >= live.required) claimLive(t.game, live.dropId)
        if (slot.withoutCredit == SESSION_CHECK_AT || slot.withoutCredit == SESSION_CHECK_AT * 2) {
            // Is Twitch still counting towards something else, e.g. a finished drop (the 4171/30 case)?
            val check = session ?: runCatching { api.currentDrop(t.stream.channelId) }
            DiagLog.i("miner: ${slot.withoutCredit} min without credit on ${t.stream.login}; Twitch current drop session: ${check.getOrNull() ?: check.exceptionOrNull()?.message ?: "none"}")
            val s = check.getOrNull()
            val otherGame = s?.gameName?.let { !t.game.matches("", it) } == true
            if (s != null && (s.stuck || otherGame)) {
                cooldown[key(t.game)] = System.currentTimeMillis() + COOLDOWN_MS
                return if (s.stuck) "Twitch session stuck on a finished drop ($s)" else "Twitch is counting another game (${s.gameName})"
            }
        }
        // A campaign that hasn't started on the account gets longer: Twitch can take a while to add it.
        // If for NOT_COUNTING_LIMIT minutes in a row Twitch's session hasn't counted a drop for this
        // game (0/0), it won't (usually the game account isn't linked): skip the game for a while.
        if (!started && (uncounted[key(t.game)] ?: 0) >= NOT_COUNTING_LIMIT) {
            uncounted.remove(key(t.game))
            // Before blaming the account, look at the game's campaigns: everything may be earned already
            // (a fully claimed campaign leaves the inventory too, as Once Human's did in Oct 2026).
            val known = gameDetails(t.game, maxAgeMs = 0)
            if (known != null) {
                claimDetails(known)
                if (known.isNotEmpty() && known.none { openDrops(it).isNotEmpty() }) {
                    markAllEarned(t.game, known)
                    return "all drops earned"
                }
            }
            val until = System.currentTimeMillis() + NOT_COUNTING_RETRY_MS
            prefs.notCounting = prefs.notCounting + (key(t.game) to until)
            val unlinked = known?.firstOrNull { openDrops(it).isNotEmpty() && !it.accountConnected }
            val link = unlinked?.linkUrl ?: prefs.watchedGames.firstOrNull { it.matches(t.game.id, t.game.name) }?.linkUrl
            DiagLog.w(
                "miner: Twitch didn't count ${t.game.name} for $NOT_COUNTING_LIMIT min (session 0/0); " +
                    (if (unlinked != null) "the game account isn't linked for ${unlinked.name}" else "the game account may need linking") +
                    "${link?.let { " ($it)" } ?: ""}. Skipping it until ${SystemInfo.time(until)}",
            )
            return "Twitch isn't counting this game (0/0 for $NOT_COUNTING_LIMIT min)"
        }
        return when {
            // Twitch credits in bursts (sometimes 6+ minutes apart), so give it a while before giving up.
            (started || live != null) && slot.withoutCredit >= NO_CREDIT_LIMIT -> "no progress in $NO_CREDIT_LIMIT min".also { cooldown[key(t.game)] = System.currentTimeMillis() + COOLDOWN_MS }
            // Everything for this game is earned (and it isn't just not started yet).
            hasCampaign(campaigns, t.game) && !hasOpenDrops(campaigns, t.game) && live == null -> "all drops earned"
            // Re-check the pick periodically: the channel may have gone offline or changed game.
            slot.onTarget >= 30 -> "periodic re-check"
            else -> null
        }
    }

    /** Claims a drop the live session shows as finished; the inventory doesn't list it, so its campaign is looked up. */
    private suspend fun claimLive(game: WatchedGame, dropId: String) {
        if (System.currentTimeMillis() - (liveClaimAttempts[dropId] ?: 0L) < COOLDOWN_MS) return
        val known = gameDetails(game, maxAgeMs = 0) ?: return
        val found = known.firstNotNullOfOrNull { c -> c.drops.firstOrNull { it.id == dropId }?.let { c to it } }
        if (found == null) {
            liveClaimAttempts[dropId] = System.currentTimeMillis()
            DiagLog.i("miner: ${game.name} drop ${dropId.take(8)}… finished, but no active campaign lists it; claim it on Twitch")
            return
        }
        claimDirect(found.first, found.second)
    }

    /** Claims every finished, unclaimed drop in [known] (campaigns from gameDetails). */
    private suspend fun claimDetails(known: List<InvCampaign>) {
        val now = System.currentTimeMillis()
        for (c in known) for (d in c.drops) {
            if (d.required > 0 && d.done && !d.claimed && (prefs.stuckClaims[d.id]?.second ?: 0L) <= now) claimDirect(c, d)
        }
    }

    /**
     * Claims a drop found outside the inventory. Without an instance id the claim id is built as
     * TwitchDropsMiner does (inventory.py generate_claim): "userId#campaignId#dropId".
     */
    private suspend fun claimDirect(c: InvCampaign, d: InvDrop) {
        val now = System.currentTimeMillis()
        if (now - (liveClaimAttempts[d.id] ?: 0L) < COOLDOWN_MS) return
        liveClaimAttempts[d.id] = now
        val userId = prefs.userId ?: return
        if (!IntegrityRenewer.ensureFresh(context, prefs, api.defaultOrigin().url)) {
            DiagLog.i("miner: ${c.gameName} · ${d.name} is finished, but no integrity token to claim it")
            return
        }
        val result = runCatching { api.claimDrop(d.instanceId ?: "$userId#${c.id}#${d.id}") }
        DiagLog.i("miner: claim ${c.gameName} · ${d.name} (not in the inventory) → ${result.getOrElse { it.message }}")
        val status = result.getOrNull() ?: return
        if (status == "ELIGIBLE_FOR_ALL" || status == "DROP_INSTANCE_ALREADY_CLAIMED") {
            ClaimLog.record(ClaimEntry(now, c.gameName, c.gameId, c.name, d.name, d.imageUrl, "CLAIMED"))
            details.remove(key(WatchedGame(c.gameId, c.gameName, "")))
        }
    }

    /**
     * Every active campaign of [game] with our progress (dashboard + DropCampaignDetails), cached for
     * [maxAgeMs]. Null when it couldn't be read. For games with nothing open in the inventory.
     */
    private suspend fun gameDetails(game: WatchedGame, maxAgeMs: Long = DETAILS_MAX_AGE_MS): List<InvCampaign>? {
        val now = System.currentTimeMillis()
        details[key(game)]?.let { (at, list) -> if (now - at <= maxAgeMs) return list }
        if (!IntegrityRenewer.ensureFresh(context, prefs, api.defaultOrigin().url)) return null
        val list = runCatching { api.gameCampaigns(game) }
            .onFailure { DiagLog.i("miner: reading ${game.name}'s campaigns failed: ${it.message}") }
            .getOrNull() ?: return null
        details[key(game)] = now to list
        DiagLog.i(
            "miner: ${game.name} campaigns: " + list.joinToString { c ->
                "${c.name} [" + c.drops.filter { it.required > 0 }.joinToString { d ->
                    "${d.minutes}/${d.required}${if (d.claimed) " claimed" else if (d.done) " done" else ""}"
                } + "]" + (if (c.allowedLogins.isNotEmpty()) " only on ${c.allowedLogins.take(3).joinToString()}" else "") +
                    (if (!c.accountConnected) " (not linked)" else "")
            }.ifEmpty { "none active" },
        )
        return list
    }

    private fun markAllEarned(game: WatchedGame, known: List<InvCampaign>) {
        val now = System.currentTimeMillis()
        val until = minOf(known.maxOfOrNull { it.endsAtMs } ?: now, now + ALL_EARNED_RETRY_MS).coerceAtLeast(now + COOLDOWN_MS)
        prefs.allEarned = prefs.allEarned + (key(game) to until)
        DiagLog.i("miner: every drop of ${game.name} is earned; skipping it until ${SystemInfo.time(until)}")
    }

    private fun snapshot(slot: Slot, campaigns: List<InvCampaign>, previous: MiningNow?): MiningNow {
        val t = slot.target
        val drop = campaigns.filter { t.game.matches(it.gameId, it.gameName) }
            .flatMap { c -> openDrops(c).map { c to it } }
            .maxByOrNull { (_, d) -> d.minutes.toFloat() / d.required }
        val live = slot.live.takeIf { drop == null }
        return MiningNow(
            game = t.game.copy(id = t.game.id.ifEmpty { t.stream.gameId }),
            channel = t.stream.login,
            viewers = t.stream.viewers,
            campaign = drop?.first?.name,
            drop = drop?.second?.name ?: live?.let { "Drop in progress" },
            minutes = drop?.second?.minutes ?: live?.minutes ?: 0,
            required = drop?.second?.required ?: live?.required ?: 0,
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
        val notCounting = prefs.notCounting
        val allEarned = prefs.allEarned
        return (watched + inProgress)
            .distinctBy { key(it) }
            .filter { (cooldown[key(it)] ?: 0L) < now && (notCounting[key(it)] ?: 0L) < now && (allEarned[key(it)] ?: 0L) < now }
    }

    /** Clears "not counting" and "all earned" marks that have expired, or whose game has open drops again. */
    private fun forgetNotCounting(campaigns: List<InvCampaign>) {
        val now = System.currentTimeMillis()
        fun keep(marks: Map<String, Long>) = marks.filter { (k, until) ->
            until > now && campaigns.none { key(WatchedGame(it.gameId, it.gameName, "")) == k && openDrops(it).isNotEmpty() }
        }
        val marks = prefs.notCounting
        val kept = keep(marks)
        if (kept.size != marks.size) {
            DiagLog.i("miner: counting again: ${(marks.keys - kept.keys).joinToString()}")
            prefs.notCounting = kept
        }
        val earned = prefs.allEarned
        val earnedKept = keep(earned)
        if (earnedKept.size != earned.size) prefs.allEarned = earnedKept
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
            // Nothing open in the inventory: it may be not started, fully claimed (gone from the
            // inventory), finished but unclaimed, or only open on certain channels. Ask Twitch.
            if (!hasOpenDrops(campaigns, game)) {
                val known = gameDetails(game)
                if (known != null) {
                    claimDetails(known)
                    if (known.isNotEmpty() && known.none { openDrops(it).isNotEmpty() }) {
                        markAllEarned(game, known)
                        continue
                    }
                }
            }
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
        // Campaigns missing from the inventory come from gameDetails, e.g. Once Human's streamer-only ones.
        val open = campaigns.filter { game.matches(it.gameId, it.gameName) && openDrops(it).isNotEmpty() }
            .ifEmpty { details[key(game)]?.second.orEmpty().filter { openDrops(it).isNotEmpty() } }
        val acl = if (open.any { it.allowedLogins.isEmpty() }) emptyList() else open.flatMap { it.allowedLogins }.distinct()
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
        // Escape from Tarkov (Oct 2026) counted on mrs_boocifer but stayed 0/0 on realkraftyy, and its
        // campaign wasn't in the dashboard to say why: go back to the channel that last counted.
        creditedOn[key(game)]?.let { login ->
            val s = runCatching { api.streamInfo(login) }.getOrNull()
            if (s != null && game.matches(s.gameId, s.gameName)) return s
        }
        val streams = api.liveStreams(game)
        DiagLog.i("miner: ${game.name} (slug ${game.slug}): ${streams.size} live drops-enabled channel(s)${streams.take(3).joinToString(prefix = if (streams.isEmpty()) "" else ": ") { "${it.login}/${it.viewers}" }}")
        return streams.firstOrNull()
    }

    private fun openDrops(c: InvCampaign) = c.drops.filter {
        !it.claimed && !it.done && it.preconditionsMet && it.required > 0 && it.endsAtMs > System.currentTimeMillis()
    }

    // Only campaigns with something to watch for: a subscription-only campaign in the inventory
    // (e.g. a chat badge) says nothing about whether the game's watch campaign is finished.
    private fun hasCampaign(campaigns: List<InvCampaign>, game: WatchedGame) =
        campaigns.any { c -> game.matches(c.gameId, c.gameName) && c.drops.any { it.required > 0 } }

    private fun hasOpenDrops(campaigns: List<InvCampaign>, game: WatchedGame) =
        campaigns.any { game.matches(it.gameId, it.gameName) && openDrops(it).isNotEmpty() }

    private fun gameMinutes(campaigns: List<InvCampaign>, game: WatchedGame) =
        campaigns.filter { game.matches(it.gameId, it.gameName) }.sumOf { c -> c.drops.filter { !it.claimed }.sumOf { it.minutes } }

    private fun progressText(campaigns: List<InvCampaign>, game: WatchedGame, live: DropSession? = null): String {
        val drop = campaigns.filter { game.matches(it.gameId, it.gameName) }.flatMap { openDrops(it) }
            .maxByOrNull { it.minutes.toFloat() / it.required }
        return drop?.let { "${it.name} ${it.minutes}/${it.required}" }
            ?: live?.let { "drop in progress ${it.minutes}/${it.required} (not in inventory yet)" }
            ?: "campaign not started yet"
    }

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

        /** When Twitch wouldn't count [game], until when the miner skips it; null if it isn't skipped. */
        fun notCountingUntil(prefs: Prefs, game: WatchedGame) =
            prefs.notCounting[key(game)]?.takeIf { it > System.currentTimeMillis() }

        /** When every drop of [game] is earned, until when the miner skips it; null if it isn't. */
        fun allEarnedUntil(prefs: Prefs, game: WatchedGame) =
            prefs.allEarned[key(game)]?.takeIf { it > System.currentTimeMillis() }

        private fun key(g: WatchedGame) = g.id.ifEmpty { g.name.lowercase() }

        private const val NO_CREDIT_LIMIT = 10
        private const val NOT_COUNTING_LIMIT = 30
        private const val NOT_COUNTING_RETRY_MS = 6 * 60 * 60 * 1000L
        private const val ALL_EARNED_RETRY_MS = 12 * 60 * 60 * 1000L
        private const val DETAILS_MAX_AGE_MS = 30 * 60 * 1000L
        private const val SESSION_CHECK_AT = 3
    }
}
