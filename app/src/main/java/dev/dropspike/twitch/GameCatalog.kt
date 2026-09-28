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
        val fromDashboard = if (IntegrityRenewer.ensureFresh(context, prefs, api.defaultOrigin().url)) {
            val result = api.dashboard(useIntegrity = true)
            result.campaigns
                ?.filter { it.status == "ACTIVE" && it.endsAtMs > now && it.game.isNotEmpty() }
                ?.groupBy { it.gameId.ifEmpty { it.game.lowercase() } }
                ?.map { (_, cs) ->
                    val c = cs.first()
                    // Linked (or no link required) if any campaign says so; otherwise the game can't earn.
                    val needsLink = cs.all { it.linked == false }
                    WatchedGame(
                        c.gameId, c.game, c.gameSlug ?: slugOf(c.game), cs.size,
                        needsLink = needsLink,
                        linkUrl = if (needsLink) cs.firstNotNullOfOrNull { it.linkUrl } else null,
                    )
                }
                ?: throw IllegalStateException("Twitch hid the campaign list (${result.errors.joinToString().ifEmpty { "integrity check failed" }})")
        } else {
            throw IllegalStateException("Couldn't unlock the campaign list. Try again, or mint a token in Settings → Advanced.")
        }
        val fromInventory = runCatching { api.inventoryCampaigns() }.getOrDefault(emptyList())
            .filter { it.endsAtMs > now }
            .map { WatchedGame(it.gameId, it.gameName, it.gameSlug ?: slugOf(it.gameName)) }

        val merged = (fromDashboard + fromInventory)
            .groupBy { it.id.ifEmpty { it.name.lowercase() } }
            // Inventory entries are in progress, so they are linked: they override "needs link".
            .map { (_, gs) -> gs.maxBy { it.campaigns }.let { best -> if (gs.any { it.campaigns == 0 }) best.copy(needsLink = false) else best } }
            .sortedBy { it.name.lowercase() }
        prefs.gameCatalog = merged
        prefs.gameCatalogAt = now
        DiagLog.i("games: ${merged.size} games with active campaigns, ${merged.count { it.needsLink }} need an account link: ${merged.filter { it.needsLink }.joinToString { it.name }}")
        // Keep the watch list's link status in step with the catalogue.
        prefs.watchedGames = prefs.watchedGames.map { w -> merged.firstOrNull { it.slug == w.slug } ?: w }
        merged
    }
}
