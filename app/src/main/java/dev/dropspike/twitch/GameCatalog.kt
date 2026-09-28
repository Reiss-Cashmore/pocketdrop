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
                    WatchedGame(c.gameId, c.game, c.gameSlug ?: slugOf(c.game), cs.size)
                }
                ?: throw IllegalStateException("Twitch hid the campaign list (${result.errors.joinToString().ifEmpty { "integrity check failed" }})")
        } else {
            throw IllegalStateException("No integrity token: mint one in step 2, then refresh")
        }
        val fromInventory = runCatching { api.inventoryCampaigns() }.getOrDefault(emptyList())
            .filter { it.endsAtMs > now }
            .map { WatchedGame(it.gameId, it.gameName, it.gameSlug ?: slugOf(it.gameName)) }

        val merged = (fromDashboard + fromInventory)
            .groupBy { it.id.ifEmpty { it.name.lowercase() } }
            .map { (_, gs) -> gs.maxBy { it.campaigns } }
            .sortedBy { it.name.lowercase() }
        prefs.gameCatalog = merged
        prefs.gameCatalogAt = now
        DiagLog.i("games: ${merged.size} games with active campaigns")
        merged
    }
}
