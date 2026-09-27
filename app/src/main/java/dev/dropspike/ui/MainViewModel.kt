package dev.dropspike.ui

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dropspike.DropSpikeApp
import dev.dropspike.data.DiagLog
import dev.dropspike.twitch.BrowserProbe
import dev.dropspike.twitch.DashboardResult
import dev.dropspike.twitch.IntegrityMinter
import dev.dropspike.twitch.IntegrityToken
import dev.dropspike.twitch.MintPage
import dev.dropspike.twitch.MintSurface
import dev.dropspike.twitch.Queries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountState(val login: String? = null, val clientId: String? = null, val busy: Boolean = false, val error: String? = null)

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

data class GateState(
    val busy: Boolean = false,
    val without: DashboardResult? = null,
    val with: DashboardResult? = null,
    val withoutError: String? = null,
    val withError: String? = null,
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
                DiagLog.i("account: ${info.login}, client ${info.clientId}, expires in ${info.expiresInSec}s")
                _account.value = AccountState(login = info.login, clientId = info.clientId)
            }
            .onFailure { e ->
                DiagLog.i("account: validate failed: ${e.message}")
                prefs.authToken = null
                _account.value = AccountState(error = e.message)
            }
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
            // The screen adds a WebView to the layout and calls runMint with it.
            MintSurface.Visible -> _integrity.update { it.copy(pendingVisibleMint = System.nanoTime()) }
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
        DiagLog.i("mint: start (${state.page.label}, ${state.surface.label})")
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
                authToken = token,
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
            DiagLog.i("mint: ok, expires in ${mins}min, is_bad_bot=${t.isBadBot}, claims keys=${t.claims?.keys()?.asSequence()?.toList()}")
            t.probe?.let { DiagLog.i("mint: in-WebView probe HTTP ${it.status}, campaigns=${it.campaigns}, errors=${it.errors}") }
            _integrity.update {
                it.copy(busy = false, pendingVisibleMint = null, expiresAt = t.expiresAtMs, isBadBot = t.isBadBot, probe = t.probe, error = null)
            }
        }.onFailure { e ->
            DiagLog.i("mint: failed: ${e.message}")
            _integrity.update { it.copy(busy = false, pendingVisibleMint = null, error = e.message ?: e.toString()) }
        }
    }

    /** The go/no-go test: does the campaign dashboard come back with an integrity token and not without? */
    fun runGateTest() = viewModelScope.launch {
        _gate.value = GateState(busy = true)
        val without = runCatching { api.dashboard(useIntegrity = false) }
        without.getOrNull()?.let { DiagLog.i("gate: without token → ${describe(it)}") }
        without.exceptionOrNull()?.let { DiagLog.i("gate: without token failed: ${it.message}") }

        val withToken = if (prefs.integrityToken != null) runCatching { api.dashboard(useIntegrity = true) } else null
        withToken?.getOrNull()?.let { DiagLog.i("gate: with token → ${describe(it)}") }
        withToken?.exceptionOrNull()?.let { DiagLog.i("gate: with token failed: ${it.message}") }

        _gate.value = GateState(
            busy = false,
            without = without.getOrNull(),
            withoutError = without.exceptionOrNull()?.message,
            with = withToken?.getOrNull(),
            withError = withToken?.exceptionOrNull()?.message ?: if (withToken == null) "No integrity token minted" else null,
        )
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
