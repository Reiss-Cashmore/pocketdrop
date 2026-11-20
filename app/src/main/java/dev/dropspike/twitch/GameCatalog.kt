package dev.dropspike.twitch

import android.content.Context
import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs

/**
 * The list of games the user can choose to watch: every game with an active drops campaign
 * (from the integrity-gated dashboard) plus the games already in the inventory.
 */
object GameCatalog {

    suspend fun refresh(context: Context, prefs: Prefs, api: TwitchApi): Result<List<WatchedGame>> = runCatching {
        val now = System.currentTimeMillis()
        var rewards = emptyList<RewardCampaign>()
        val fromDashboard = if (IntegrityRenewer.ensureFresh(context, prefs, api.defaultOrigin().url)) {
            val result = api.dashboard(useIntegrity = true)
            rewards = result.rewards.filter { it.endsAtMs > now && it.startsAtMs <= now }
            result.campaigns
                ?.filter { it.status == "ACTIVE" && it.endsAtMs > now && it.game.isNotEmpty() }
                ?.groupBy { it.gameId.ifEmpty { it.game.lowercase() } }
                ?.map { (_, cs) ->
                    val c = cs.first()
                    // Linked (or no link required) if any campaign says so. Twitch also says "not connected"
                    // for campaigns with nothing to link (e.g. Just Chatting), so a link page is required too.
                    val needsLink = cs.all { it.linked == false && it.linkUrl != null }
                    WatchedGame(
                        c.gameId, c.game, c.gameSlug ?: slugOf(c.game), cs.size,
                        needsLink = needsLink,
                        linkUrl = if (needsLink) cs.firstNotNullOfOrNull { it.linkUrl } else null,
                        endsAtMs = cs.minOf { it.endsAtMs },
                        startsAtMs = cs.maxOf { it.startsAtMs },
                    )
                }
                ?: throw IllegalStateException("Twitch hid the campaign list (${result.errors.joinToString().ifEmpty { "integrity check failed" }})")
        } else {
            throw IllegalStateException("Couldn't unlock the campaign list. Try again, or mint a token in Settings → Advanced.")
        }
        val fromInventory = runCatching { api.inventoryCampaigns() }.getOrDefault(emptyList())
            .filter { it.endsAtMs > now }
            .map { WatchedGame(it.gameId, it.gameName, it.gameSlug ?: slugOf(it.gameName), endsAtMs = it.endsAtMs, startsAtMs = it.startsAtMs) }

        // Watch-time reward campaigns (e.g. a Twitch badge for watching a game) make their game worth
        // watching even when it has no drop campaign.
        val fromRewards = rewards.filter { it.game.isNotEmpty() && it.minutesGoal > 0 }
            .groupBy { it.game.lowercase() }
            .map { (_, rs) ->
                val r = rs.first()
                val id = fromDashboard.firstOrNull { it.name.equals(r.game, ignoreCase = true) }?.id.orEmpty()
                WatchedGame(id, r.game, r.gameSlug ?: slugOf(r.game), rs.size, endsAtMs = rs.minOf { it.endsAtMs }, startsAtMs = rs.maxOf { it.startsAtMs })
            }

        val merged = (fromDashboard + fromInventory + fromRewards)
            .groupBy { it.name.lowercase() }
            // Inventory entries are in progress, so they are linked: they override "needs link".
            .map { (_, gs) ->
                gs.maxBy { it.campaigns }
                    .let { best -> if (gs.any { it.campaigns == 0 }) best.copy(needsLink = false) else best }
                    .let { best -> best.copy(id = best.id.ifEmpty { gs.firstNotNullOfOrNull { g -> g.id.ifEmpty { null } }.orEmpty() }) }
                    .copy(
                        endsAtMs = gs.filter { it.endsAtMs > 0 }.minOfOrNull { it.endsAtMs } ?: 0,
                        startsAtMs = gs.maxOf { it.startsAtMs },
                    )
            }
            .sortedBy { it.name.lowercase() }
        prefs.gameCatalog = merged
        prefs.rewardCampaigns = rewards
        prefs.gameCatalogAt = now
        DiagLog.i("games: ${merged.size} games with active campaigns, ${merged.count { it.needsLink }} need an account link: ${merged.filter { it.needsLink }.joinToString { "${it.name} (${it.linkUrl})" }}")
        DiagLog.i(
            "games: ${rewards.size} Twitch reward campaign(s)" + rewards.joinToString("") { r ->
                "\n  ${r.name} · ${r.game.ifEmpty { r.brand.ifEmpty { "sitewide" } }} · " +
                    listOfNotNull(r.minutesGoal.takeIf { it > 0 }?.let { "watch $it min" }, r.subsGoal.takeIf { it > 0 }?.let { "$it sub(s)" }).joinToString(" or ").ifEmpty { "no goal given" } +
                    " · ${r.rewards.joinToString().ifEmpty { "reward not named" }} · ends ${java.util.Date(r.endsAtMs)}"
            },
        )
        // Keep the watch list's link status in step with the catalogue.
        prefs.watchedGames = prefs.watchedGames.map { w -> merged.firstOrNull { it.slug == w.slug } ?: w }
        merged
    }
}
