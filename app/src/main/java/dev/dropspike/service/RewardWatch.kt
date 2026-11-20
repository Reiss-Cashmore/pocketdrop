package dev.dropspike.service

import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs
import dev.dropspike.data.SystemInfo
import dev.dropspike.twitch.DropSession
import dev.dropspike.twitch.RewardCampaign
import dev.dropspike.twitch.WatchedGame
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

/**
 * Twitch's watch-time reward campaigns (e.g. "GTAVI Launch Celebration": an hour a day of GTA VI for a
 * badge). The dashboard gives no progress, but Twitch's live session on a channel (DropCurrentSessionContext)
 * reports a reward group's id as the drop it is counting, with minutes and goal (Nov 2026: "2283245e…,
 * 3/60 min" on a GTA VI channel). So progress, completion and daily resets are all read from Twitch:
 *
 * - The miner watches a reward's game while the reward isn't paused, reading the session every minute.
 * - The session counting one of the reward's groups confirms the game and gives real progress.
 * - Reaching the goal, or the session not counting the reward for [UNRELATED_LIMIT] minutes, pauses it
 *   for a while; afterwards the miner looks again, which is how a daily reset is noticed.
 *
 * The game comes from the campaign when Twitch names it (sitewide ones don't), else the user's choice,
 * else a game the session has confirmed, else a watched game the campaign text mentions (only a guess
 * to try first: it is dropped if the session doesn't confirm it).
 */
object RewardWatch {

    /** Minutes the session may count something else before the reward is paused on that game. */
    const val UNRELATED_LIMIT = 5
    private const val DONE_PAUSE_MS = 3 * 60 * 60 * 1000L
    private const val UNRELATED_PAUSE_MS = 2 * 60 * 60 * 1000L

    data class State(
        val minutes: Int = 0,
        val required: Int = 0,
        /** When Twitch last reported this reward's progress. */
        val seenAt: Long = 0,
        /** Watched game the session confirmed the reward counts on. */
        val game: String = "",
        /** The reward is skipped until then. */
        val pausedUntil: Long = 0,
        val pauseReason: String = "",
        /** Minutes in a row the session counted something else. */
        val unrelated: Int = 0,
        /** Guessed games the session didn't confirm. */
        val rejected: List<String> = emptyList(),
        /** UTC days Twitch reported the goal reached. */
        val doneDays: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().put("min", minutes).put("req", required).put("seen", seenAt).put("game", game)
            .put("paused", pausedUntil).put("why", pauseReason).put("unrelated", unrelated)
            .put("rejected", JSONArray(rejected)).put("days", JSONArray(doneDays))

        companion object {
            fun fromJson(j: JSONObject) = State(
                j.optInt("min"), j.optInt("req"), j.optLong("seen"), j.optString("game"), j.optLong("paused"),
                j.optString("why"), j.optInt("unrelated"), j.optJSONArray("rejected").strings(), j.optJSONArray("days").strings(),
            )

            private fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { getString(it) }
        }
    }

    /** A reward the miner should watch [game] for now. */
    data class Due(val reward: RewardCampaign, val game: WatchedGame, val state: State?)

    fun today(now: Long = System.currentTimeMillis()): String = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()

    fun active(r: RewardCampaign, now: Long = System.currentTimeMillis()) =
        r.watchGroups.isNotEmpty() && r.startsAtMs <= now && r.endsAtMs > now

    /** The watched game [r] counts for, or null (see the class comment for the order). */
    fun gameFor(r: RewardCampaign, games: List<WatchedGame>, overrides: Map<String, String>, state: State?): WatchedGame? {
        fun named(name: String) = games.firstOrNull { compact(it.name) == compact(name) }
        overrides[r.id]?.let { return if (it.isEmpty()) null else named(it) }
        if (r.game.isNotBlank()) return named(r.game)
        state?.game?.takeIf { it.isNotEmpty() }?.let { named(it)?.let { g -> return g } }
        val rejected = state?.rejected.orEmpty().map(::compact).toSet()
        val text = compact("${r.name} ${r.summary}")
        return games.firstOrNull { g -> compact(g.name) !in rejected && tokens(g.name).any { it in text } }
    }

    /** Whether the game for [r] is a guess the session hasn't confirmed yet. */
    fun isGuess(r: RewardCampaign, overrides: Map<String, String>, state: State?) =
        r.id !in overrides && r.game.isBlank() && state?.game.isNullOrEmpty()

    /** Rewards to watch for now, in watch-list order. */
    fun due(prefs: Prefs, now: Long = System.currentTimeMillis()): List<Due> {
        val watched = prefs.watchedGames
        if (watched.isEmpty()) return emptyList()
        val states = prefs.rewardProgress
        val overrides = prefs.rewardGames
        return prefs.rewardCampaigns
            .filter { active(it, now) && (states[it.id]?.pausedUntil ?: 0) <= now }
            .mapNotNull { r -> gameFor(r, watched, overrides, states[r.id])?.let { Due(r, it, states[r.id]) } }
            .sortedBy { d -> watched.indexOfFirst { it.name == d.game.name } }
    }

    /**
     * Records one minute of watching [game] with Twitch's [session] on that channel (null: nothing counting,
     * or it couldn't be read). Returns whether any reward still wants this game.
     */
    fun observe(prefs: Prefs, game: WatchedGame, session: DropSession?, now: Long = System.currentTimeMillis()): Boolean {
        val dueHere = due(prefs, now).filter { it.game.name == game.name }
        if (dueHere.isEmpty()) return false
        val all = prefs.rewardProgress.toMutableMap()
        val overrides = prefs.rewardGames
        var stillDue = false
        for (d in dueHere) {
            val r = d.reward
            val st = all[r.id] ?: State()
            val counting = session?.takeIf { s -> r.watchGroups.any { it.id == s.dropId } }
            val next = when {
                counting != null && counting.minutes >= counting.required -> {
                    DiagLog.i("rewards: ${r.name}: Twitch reports ${counting.minutes}/${counting.required} min on ${game.name}, goal reached; checking again at ${SystemInfo.time(now + DONE_PAUSE_MS)}")
                    st.copy(
                        minutes = counting.minutes, required = counting.required, seenAt = now, game = game.name, unrelated = 0,
                        pausedUntil = now + DONE_PAUSE_MS, pauseReason = "goal reached", doneDays = (st.doneDays + today(now)).distinct(),
                    )
                }
                counting != null -> {
                    if (st.game != game.name) DiagLog.i("rewards: ${r.name}: Twitch counts it on ${game.name} (${counting.minutes}/${counting.required} min)")
                    stillDue = true
                    st.copy(minutes = counting.minutes, required = counting.required, seenAt = now, game = game.name, unrelated = 0, pauseReason = "")
                }
                st.unrelated + 1 >= UNRELATED_LIMIT -> {
                    // Only reject a guessed game when nothing at all counted there; another drop counting
                    // can hide the reward, so that just pauses it.
                    val guess = isGuess(r, overrides, st) && session == null
                    DiagLog.i(
                        "rewards: ${r.name}: Twitch's session on ${game.name} didn't count it for $UNRELATED_LIMIT min (${session ?: "nothing counting"}); " +
                            if (guess) "${game.name} was only a guess from the campaign text, dropping it" else "pausing it until ${SystemInfo.time(now + UNRELATED_PAUSE_MS)}",
                    )
                    if (guess) st.copy(unrelated = 0, rejected = (st.rejected + game.name).distinct())
                    else st.copy(unrelated = 0, pausedUntil = now + UNRELATED_PAUSE_MS, pauseReason = "Twitch didn't count it on ${game.name}")
                }
                else -> {
                    stillDue = true
                    st.copy(unrelated = st.unrelated + 1)
                }
            }
            all[r.id] = next
        }
        prefs.rewardProgress = all
        return stillDue
    }

    /** Session progress for the reward [session] is counting, if it's one of the active rewards' groups. */
    fun note(prefs: Prefs, game: WatchedGame, session: DropSession?, now: Long = System.currentTimeMillis()) {
        if (session == null) return
        val r = prefs.rewardCampaigns.firstOrNull { c -> active(c, now) && c.watchGroups.any { it.id == session.dropId } } ?: return
        val all = prefs.rewardProgress.toMutableMap()
        val st = all[r.id] ?: State()
        all[r.id] = st.copy(minutes = session.minutes, required = session.required, seenAt = now, game = game.name, unrelated = 0)
        prefs.rewardProgress = all
    }

    private fun compact(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").uppercase().replace(Regex("[^A-Z0-9]"), "")

    private val ROMAN = Regex("^(?=[IVXLC]+$)M*(C[MD]|D?C{0,3})(X[CL]|L?X{0,3})(I[XV]|V?I{0,3})$")

    /** The whole name, and its initials with numbers kept whole ("Grand Theft Auto VI" → "GTAVI"). */
    private fun tokens(name: String): List<String> {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val initials = words.joinToString("") { w ->
            val u = compact(w)
            if (u.all { it.isDigit() } || ROMAN.matches(u)) u else u.take(1)
        }
        return listOf(compact(name), initials).filter { it.length >= 4 }.distinct()
    }
}
