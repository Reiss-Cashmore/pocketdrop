package dev.dropspike.ui

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import dev.dropspike.data.Prefs
import dev.dropspike.service.AutoMine
import dev.dropspike.service.WakeWorker
import dev.dropspike.twitch.ApiOrigin
import dev.dropspike.twitch.BrowserProbe
import dev.dropspike.twitch.Campaign
import dev.dropspike.twitch.DashboardResult
import dev.dropspike.twitch.DeviceClient
import dev.dropspike.twitch.DeviceCode
import dev.dropspike.twitch.GameCatalog
import dev.dropspike.twitch.InvCampaign
import dev.dropspike.twitch.IntegrityMinter
import dev.dropspike.twitch.IntegrityToken
import dev.dropspike.twitch.MintPage
import dev.dropspike.twitch.MintSurface
import dev.dropspike.twitch.Queries
import dev.dropspike.twitch.Reward
import dev.dropspike.twitch.WatchedGame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AccountState(val login: String? = null, val clientId: String? = null, val busy: Boolean = false, val error: String? = null)

/** Device-code sign-in in progress: show [code] to the user while polling Twitch. */
data class DeviceLoginState(val code: DeviceCode? = null, val busy: Boolean = false, val error: String? = null)

data class IntegrityState(
    val page: MintPage = MintPage.Light,
    val surface: MintSurface = MintSurface.Visible,
    /** Non-null while an on-screen mint is waiting for its WebView. */
    val pendingVisibleMint: Long? = null,
    val busy: Boolean = false,
    val expiresAt: Long = 0,
    val isBadBot: String? = null,
    val probe: BrowserProbe? = null,
    val error: String? = null,
)

data class GamesState(
    val watched: List<WatchedGame> = emptyList(),
    val catalog: List<WatchedGame> = emptyList(),
    val catalogAt: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val onlyWatched: Boolean = false,
    val wakeIntervalMin: Int = 0,
    val lastWakeCheck: String = "",
    val autoMine: Boolean = true,
)

data class MiningSettings(val twoChannels: Boolean, val onlyCharging: Boolean, val onlyWifi: Boolean)

data class RewardsState(
    val account: List<Reward> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)

data class InventoryState(
    val campaigns: List<InvCampaign> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)

/** One request in the gate test. [ok]: true = pass, false = fail, null = informational. */
data class GateResult(val label: String, val ok: Boolean?, val text: String)

data class GateState(
    val busy: Boolean = false,
    val rows: List<GateResult> = emptyList(),
    val campaigns: List<Campaign>? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = DropSpikeApp.instance.prefs
    private val api = DropSpikeApp.instance.api

    private val _account = MutableStateFlow(AccountState(login = prefs.login, clientId = prefs.authToken?.let { prefs.clientId }))
    val account: StateFlow<AccountState> = _account

    private val _integrity = MutableStateFlow(
        IntegrityState(
            expiresAt = prefs.integrityExpiry,
            isBadBot = prefs.integrityToken?.let { IntegrityMinter.decodeClaims(it)?.opt("is_bad_bot")?.toString() },
        ),
    )
    val integrity: StateFlow<IntegrityState> = _integrity

    private val _games = MutableStateFlow(loadGames())
    val games: StateFlow<GamesState> = _games

    private fun loadGames(busy: Boolean = false, error: String? = null) = GamesState(
        watched = prefs.watchedGames,
        catalog = prefs.gameCatalog,
        catalogAt = prefs.gameCatalogAt,
        busy = busy,
        error = error,
        onlyWatched = prefs.onlyWatched,
        wakeIntervalMin = prefs.wakeIntervalMin,
        lastWakeCheck = prefs.lastWakeCheck,
        autoMine = prefs.autoMine,
    )

    /** Re-read settings the background check may have changed (e.g. its last result). */
    fun reloadGames() {
        _games.value = loadGames(busy = _games.value.busy, error = _games.value.error)
    }

    fun refreshGameCatalog() = viewModelScope.launch {
        _games.value = loadGames(busy = true)
        val result = GameCatalog.refresh(getApplication(), prefs, api)
        _games.value = loadGames(error = result.exceptionOrNull()?.message)
    }

    fun toggleWatched(game: WatchedGame) {
        val current = prefs.watchedGames
        val removing = current.any { it.slug == game.slug }
        prefs.watchedGames = if (removing) current.filterNot { it.slug == game.slug } else current + game
        DiagLog.i("games: ${if (removing) "unwatched" else "watching"} ${game.name}; list now ${prefs.watchedGames.joinToString { it.name }.ifEmpty { "empty" }}")
        reloadGames()
    }

    fun moveWatchedUp(game: WatchedGame) {
        val list = prefs.watchedGames.toMutableList()
        val i = list.indexOfFirst { it.slug == game.slug }
        if (i > 0) {
            list.add(i - 1, list.removeAt(i))
            prefs.watchedGames = list
            reloadGames()
        }
    }

    fun moveWatchedDown(game: WatchedGame) {
        val list = prefs.watchedGames.toMutableList()
        val i = list.indexOfFirst { it.slug == game.slug }
        if (i in 0 until list.lastIndex) {
            list.add(i + 1, list.removeAt(i))
            prefs.watchedGames = list
            reloadGames()
        }
    }

    fun setOnlyWatched(on: Boolean) {
        prefs.onlyWatched = on
        DiagLog.i("games: only watched = $on")
        reloadGames()
    }

    fun setWakeInterval(minutes: Int) {
        prefs.wakeIntervalMin = minutes
        WakeWorker.schedule(getApplication())
        DiagLog.i("wake: check ${if (minutes == 0) "off" else "every $minutes min"}")
        reloadGames()
    }

    /** Auto mine on (with a background check, 30 min unless one is already set) or off. */
    fun setAutoMine(on: Boolean) {
        prefs.autoMine = on
        prefs.autoMinePausedUntil = 0
        DiagLog.i("auto mine: turned ${if (on) "on" else "off"}")
        if (on && prefs.wakeIntervalMin == 0) setWakeInterval(30) else reloadGames()
        if (on) autoMineCheck("auto mine turned on")
    }

    /** Start mining without a tap if auto mine is on and a watched game is live. */
    fun autoMineCheck(trigger: String) = viewModelScope.launch {
        AutoMine.check(getApplication(), trigger)
        reloadGames()
    }

    private var mintHost: CompletableDeferred<WebView>? = null

    /** Called by the screen once the on-screen mint WebView exists. */
    fun attachMintHost(view: WebView) {
        mintHost?.complete(view)
    }

    private val _inventory = MutableStateFlow(InventoryState())
    val inventory: StateFlow<InventoryState> = _inventory

    private val _themeMode = MutableStateFlow(prefs.themeMode)
    val themeMode: StateFlow<String> = _themeMode

    fun setThemeMode(mode: String) {
        prefs.themeMode = mode
        _themeMode.value = mode
        applyNightMode(getApplication(), mode)
        DiagLog.i("appearance: theme $mode")
    }

    private val _mining = MutableStateFlow(loadMining())
    val mining: StateFlow<MiningSettings> = _mining

    private fun loadMining() = MiningSettings(prefs.twoChannels, prefs.onlyCharging, prefs.onlyWifi)

    fun setTwoChannels(on: Boolean) {
        prefs.twoChannels = on
        DiagLog.i("mining: two channels = $on")
        _mining.value = loadMining()
    }

    fun setOnlyCharging(on: Boolean) {
        prefs.onlyCharging = on
        DiagLog.i("mining: only while charging = $on")
        WakeWorker.schedule(getApplication())
        _mining.value = loadMining()
    }

    fun setOnlyWifi(on: Boolean) {
        prefs.onlyWifi = on
        DiagLog.i("mining: only on Wi-Fi = $on")
        WakeWorker.schedule(getApplication())
        _mining.value = loadMining()
    }

    private val _rewards = MutableStateFlow(RewardsState())
    val rewards: StateFlow<RewardsState> = _rewards

    /** Rewards on the account (Inventory gameEventDrops); PocketDrop's own claims come from ClaimLog. */
    fun refreshRewards() = viewModelScope.launch {
        if (prefs.authToken == null || _rewards.value.loading) return@launch
        _rewards.value = _rewards.value.copy(loading = true, error = null)
        val result = runCatching { api.rewards() }
        _rewards.value = RewardsState(
            account = result.getOrDefault(_rewards.value.account),
            loading = false,
            error = result.exceptionOrNull()?.message,
            updatedAt = if (result.isSuccess) System.currentTimeMillis() else _rewards.value.updatedAt,
        )
        DiagLog.i("rewards: ${result.map { "${it.size} on the account" }.getOrElse { "failed: ${it.message}" }}")
    }

    private val _dynamicColor = MutableStateFlow(prefs.dynamicColor)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor

    fun setDynamicColor(on: Boolean) {
        prefs.dynamicColor = on
        _dynamicColor.value = on
    }

    /** Loads the inventory for the home screen (the miner publishes its own copy while running). */
    fun refreshInventory() {
        if (prefs.authToken == null || _inventory.value.loading) return
        _inventory.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { api.inventoryCampaigns() }
                .onSuccess { list -> _inventory.value = InventoryState(campaigns = list, updatedAt = System.currentTimeMillis()) }
                .onFailure { e -> _inventory.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** First run after sign-in: fetch the game list quietly so the picker is ready. */
    private fun warmUpAfterSignIn() {
        refreshInventory()
        autoMineCheck("signed in")
        if (prefs.gameCatalog.isEmpty() || System.currentTimeMillis() - prefs.gameCatalogAt > 6 * 60 * 60 * 1000L) {
            refreshGameCatalog()
        }
    }

    init {
        // Posted (not immediate) so it runs after every property below is initialised.
        if (prefs.authToken != null) viewModelScope.launch(Dispatchers.Main) { warmUpAfterSignIn() }
    }

    private val _deviceLogin = MutableStateFlow(DeviceLoginState())
    val deviceLogin: StateFlow<DeviceLoginState> = _deviceLogin
    private var deviceLoginJob: Job? = null

    private val _gate = MutableStateFlow(GateState())
    val gate: StateFlow<GateState> = _gate

    /** Called after LoginActivity (or a pasted token) stored a token: confirm it with Twitch. */
    fun onTokenStored() = viewModelScope.launch {
        val token = prefs.authToken ?: return@launch
        _account.update { it.copy(busy = true, error = null) }
        runCatching { api.validate(token) }
            .onSuccess { info ->
                prefs.login = info.login
                prefs.userId = info.userId
                prefs.clientId = info.clientId
                DiagLog.i("account: ${info.login}, client ${info.clientId}, ${if (info.expiresInSec == 0L) "never expires" else "expires in ${info.expiresInSec}s"}")
                _account.value = AccountState(login = info.login, clientId = info.clientId)
                warmUpAfterSignIn()
            }
            .onFailure { e ->
                DiagLog.i("account: validate failed: ${e.message}")
                prefs.authToken = null
                _account.value = AccountState(error = e.message)
            }
    }

    /** Twitch's device-code flow: no embedded login page; the code is entered in any browser. */
    fun startDeviceLogin(client: DeviceClient) {
        deviceLoginJob?.cancel()
        _deviceLogin.value = DeviceLoginState(busy = true)
        deviceLoginJob = viewModelScope.launch {
            val code = runCatching { api.startDeviceLogin(client) }.getOrElse { e ->
                DiagLog.i("device login (${client.label}): ${e.message}")
                _deviceLogin.value = DeviceLoginState(error = e.message)
                return@launch
            }
            DiagLog.i("device login (${client.label}): code issued, waiting for activation")
            _deviceLogin.value = DeviceLoginState(code = code, busy = true)
            while (System.currentTimeMillis() < code.expiresAtMs) {
                delay(code.intervalSec * 1000)
                val token = runCatching { api.pollDeviceLogin(code) }.getOrNull() ?: continue
                DiagLog.i("device login (${client.label}): activated")
                _deviceLogin.value = DeviceLoginState()
                prefs.authToken = token
                onTokenStored()
                return@launch
            }
            _deviceLogin.value = DeviceLoginState(error = "Code expired, try again")
        }
    }

    fun cancelDeviceLogin() {
        deviceLoginJob?.cancel()
        _deviceLogin.value = DeviceLoginState()
    }

    fun pasteToken(token: String) {
        prefs.authToken = token.trim().removePrefix("OAuth ").removePrefix("oauth:")
        onTokenStored()
    }

    fun signOut() {
        prefs.signOut()
        CookieManager.getInstance().removeAllCookies(null)
        _account.value = AccountState()
        _integrity.update { IntegrityState(page = it.page, surface = it.surface) }
        _gate.value = GateState()
        _inventory.value = InventoryState()
        DiagLog.i("account: signed out")
    }

    fun setMintPage(p: MintPage) = _integrity.update { it.copy(page = p) }
    fun setMintSurface(s: MintSurface) = _integrity.update { it.copy(surface = s) }

    fun mint() {
        if (prefs.authToken == null) {
            _integrity.update { it.copy(error = "Sign in first") }
            return
        }
        _integrity.update { it.copy(busy = true, error = null) }
        when (_integrity.value.surface) {
            // The screen shows a WebView and hands it over via attachMintHost; the mint itself
            // runs here, so recomposition can't cancel it.
            MintSurface.Visible -> {
                val host = CompletableDeferred<WebView>().also { mintHost = it }
                _integrity.update { it.copy(pendingVisibleMint = System.nanoTime()) }
                viewModelScope.launch {
                    val view = withTimeoutOrNull(10_000) { host.await() }
                    if (view == null) {
                        _integrity.update { it.copy(busy = false, pendingVisibleMint = null, error = "WebView never appeared") }
                    } else {
                        runMint(view)
                    }
                }
            }
            MintSurface.Offscreen -> viewModelScope.launch {
                val view = WebView(getApplication<Application>())
                // Never attached to a window; give it a phone-sized layout so the page has a viewport.
                view.measure(1080, 2400)
                view.layout(0, 0, 1080, 2400)
                try {
                    runMint(view)
                } finally {
                    view.destroy()
                }
            }
        }
    }

    /** Runs on the main thread with a WebView supplied by the screen or by [mint]. */
    suspend fun runMint(view: WebView) {
        val state = _integrity.value
        val site = api.defaultOrigin().url
        DiagLog.i("mint: start (${state.page.label}, ${state.surface.label}, $site, client ${prefs.clientId})")
        val token = prefs.authToken ?: run {
            _integrity.update { it.copy(busy = false, pendingVisibleMint = null, error = "Sign in first") }
            return
        }
        val headers = mapOf(
            "Client-ID" to prefs.clientId,
            "Authorization" to "OAuth $token",
            "Client-Session-Id" to prefs.sessionId,
        )
        val result = runCatching {
            IntegrityMinter(view).mint(
                page = state.page,
                siteUrl = site,
                authToken = token,
                setSessionCookie = prefs.clientId == Prefs.WEB_CLIENT_ID,
                headers = headers,
                deviceId = prefs.deviceId,
                scriptUrl = prefs.kasadaScriptUrl,
                probeQuery = Queries.dashboard(),
            )
        }
        result.onSuccess { t: IntegrityToken ->
            prefs.integrityToken = t.token
            prefs.integrityExpiry = t.expiresAtMs
            val mins = (t.expiresAtMs - System.currentTimeMillis()) / 60_000
            DiagLog.i("mint: ok, expires in ${mins}min, is_bad_bot=${t.isBadBot}, claims keys=${t.claims?.keys()?.asSequence()?.toList()}, format=${t.token.split('.').take(2).joinToString(".").take(24)}…")
            t.probe?.let { DiagLog.i("mint: in-WebView probe HTTP ${it.status}, campaigns=${it.campaigns}, errors=${it.errors}") }
            _integrity.update {
                it.copy(busy = false, pendingVisibleMint = null, expiresAt = t.expiresAtMs, isBadBot = t.isBadBot, probe = t.probe, error = null)
            }
        }.onFailure { e ->
            DiagLog.i("mint: failed: ${e.message}")
            _integrity.update { it.copy(busy = false, pendingVisibleMint = null, error = e.message ?: e.toString()) }
        }
    }

    /**
     * The go/no-go test, as a matrix so one run explains a failure: an ungated call (auth works
     * at all?), then the dashboard without/with the integrity token, claiming each site origin.
     */
    fun runGateTest() = viewModelScope.launch {
        _gate.value = GateState(busy = true)
        val rows = mutableListOf<GateResult>()
        var campaigns: List<Campaign>? = null

        val token = prefs.authToken
        val v = if (token == null) Result.failure(IllegalStateException("Not signed in")) else runCatching { api.validate(token) }
        rows += GateResult(
            "Token still valid",
            v.isSuccess,
            v.fold({ "yes, ${it.login}, client ${it.clientId.take(6)}…, ${if (it.expiresInSec == 0L) "no expiry" else "${it.expiresInSec / 3600}h left"}" }, { it.message ?: it.toString() }),
        )

        val inv = runCatching { api.inventory() }
        rows += GateResult(
            "Inventory (not gated)",
            inv.isSuccess,
            inv.fold({ "OK, ${it.size} drops" }, { it.message ?: it.toString() }),
        )

        val combos = buildList {
            ApiOrigin.entries.forEach { add(false to it) }
            if (prefs.integrityToken != null) ApiOrigin.entries.forEach { add(true to it) }
        }
        for ((useIntegrity, origin) in combos) {
            val label = "${if (useIntegrity) "With" else "Without"} token, ${origin.label}"
            val r = runCatching { api.dashboard(useIntegrity, origin) }
            val row = r.fold(
                { res ->
                    if (res.campaigns != null && campaigns == null) campaigns = res.campaigns
                    // Without a token "null" is the expected gated answer, so it isn't a failure.
                    GateResult(label, if (res.campaigns != null) true else if (useIntegrity) false else null, describe(res))
                },
                { GateResult(label, false, it.message ?: it.toString()) },
            )
            rows += row
            DiagLog.i("gate: $label → ${row.text}")
            _gate.value = GateState(busy = true, rows = rows.toList(), campaigns = campaigns)
        }
        if (prefs.integrityToken == null) rows += GateResult("With token", null, "No integrity token minted")
        DiagLog.i("gate: client ${prefs.clientId}; ${rows.take(2).joinToString { "${it.label} → ${it.text}" }}")
        _gate.value = GateState(busy = false, rows = rows.toList(), campaigns = campaigns)
    }

    fun inventoryOnce() = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { api.inventory() } }
            .onSuccess { drops ->
                DiagLog.i("inventory: ${drops.size} drops")
                drops.take(10).forEach { DiagLog.i("  ${it.game} · ${it.drop}: ${it.minutes}/${it.required}${if (it.claimed) " (claimed)" else ""}") }
            }
            .onFailure { DiagLog.i("inventory: failed: ${it.message}") }
    }

    companion object {
        fun describe(r: DashboardResult): String = buildString {
            append(if (r.campaigns == null) "dropCampaigns: null" else "${r.campaigns.size} campaigns")
            if (r.errors.isNotEmpty()) append(" · errors: ${r.errors.joinToString()}")
        }
    }
}
