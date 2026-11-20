package dev.dropspike.service

import dev.dropspike.data.Prefs
import dev.dropspike.twitch.RewardCampaign
import dev.dropspike.twitch.WatchedGame
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset

/**
 * Twitch's watch-time reward campaigns (e.g. "GTAVI Launch Celebration": watch GTA VI streamers an
 * hour a day on six days for six chat badges). Twitch reports no progress on them, so the minutes
 * PocketDrop watches for one are counted here, per UTC day, and the miner gives the campaign's game
 * that much time each day before going back to drops.
 */
object RewardWatch {

    /** Minutes watched beyond the goal, in case Twitch missed one or two. */
    const val BUFFER_MIN = 5

    data class Progress(val day: String, val minutes: Int, val doneDays: List<String>)

    /** One reward the miner should still work on, with the watched game it counts for. */
    data class Due(val reward: RewardCampaign, val game: WatchedGame, val minutesToday: Int, val target: Int)

    fun today(now: Long = System.currentTimeMillis()): String = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** "Each day", "daily", "six different days"…: the goal resets every day. */
    fun isDaily(r: RewardCampaign) = Regex("\\b(each day|every day|daily|per day|days)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn("${r.name}\n${r.summary}")

    /** Today's minutes, or 0 when the stored day is an earlier one. */
    fun minutesToday(p: Progress?, day: String = today()) = if (p?.day == day) p.minutes else 0

    /** Days the goal was reached (for a one-off campaign, at most one entry). */
    fun daysDone(p: Progress?) = p?.doneDays?.size ?: 0

    fun isFinished(r: RewardCampaign, p: Progress?, day: String = today()): Boolean =
        if (isDaily(r)) day in p?.doneDays.orEmpty() else daysDone(p) > 0

    /**
     * The game [r] counts for: the user's choice if they made one (blank = none), else the campaign's
     * own game, else a watched game whose name or initials appear in the campaign's text
     * ("GTAVI" → Grand Theft Auto VI).
     */
    fun gameFor(r: RewardCampaign, games: List<WatchedGame>, overrides: Map<String, String>): WatchedGame? {
        overrides[r.id]?.let { name -> return games.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        if (r.game.isNotBlank()) games.firstOrNull { compact(it.name) == compact(r.game) }?.let { return it }
        val text = compact("${r.name} ${r.summary} ${r.rewards.joinToString(" ")}")
        return games.firstOrNull { g -> tokens(g.name).any { it in text } }
    }

    /** Rewards the miner should spend time on now, in watch-list order. */
    fun due(prefs: Prefs, now: Long = System.currentTimeMillis()): List<Due> {
        val watched = prefs.watchedGames
        if (watched.isEmpty()) return emptyList()
        val progress = prefs.rewardProgress
        val overrides = prefs.rewardGames
        val day = today(now)
        return prefs.rewardCampaigns
            .filter { it.minutesGoal > 0 && it.startsAtMs <= now && it.endsAtMs > now }
            .mapNotNull { r ->
                val p = progress[r.id]
                if (isFinished(r, p, day)) return@mapNotNull null
                val game = gameFor(r, watched, overrides) ?: return@mapNotNull null
                val done = if (isDaily(r)) minutesToday(p, day) else p?.minutes ?: 0
                Due(r, game, done, r.minutesGoal + BUFFER_MIN)
            }
            .sortedBy { d -> watched.indexOfFirst { it.name == d.game.name } }
    }

    /** Adds one watched minute to every due reward for [game]; returns the rewards that just reached their goal. */
    fun addMinute(prefs: Prefs, game: WatchedGame, now: Long = System.currentTimeMillis()): List<Pair<Due, Progress>> {
        val dueNow = due(prefs, now).filter { it.game.name.equals(game.name, ignoreCase = true) }
        if (dueNow.isEmpty()) return emptyList()
        val day = today(now)
        val all = prefs.rewardProgress.toMutableMap()
        val reached = mutableListOf<Pair<Due, Progress>>()
        for (d in dueNow) {
            val p = all[d.reward.id]
            val daily = isDaily(d.reward)
            val minutes = (if (daily) minutesToday(p, day) else p?.minutes ?: 0) + 1
            var doneDays = p?.doneDays.orEmpty()
            if (minutes >= d.target) doneDays = (doneDays + (if (daily) day else "once")).distinct()
            val next = Progress(day, minutes, doneDays)
            all[d.reward.id] = next
            if (minutes >= d.target) reached += d to next
        }
        prefs.rewardProgress = all
        return reached
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
