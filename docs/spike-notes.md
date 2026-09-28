# Spike notes

How PocketDrop's first build proved its three assumptions (Sep 2026). Kept for history; the
app has moved on, but the reasoning still explains why things are built the way they are.

## Running the spike

Work top to bottom in the app, then use **Copy report** in Diagnostics and share it.
The report contains no tokens.

| Step | What a pass looks like |
|---|---|
| 1. Sign in | "Signed in as …". The client ID is shown too; `kimne78…` is the web client. |
| 2. Mint token | "Token valid until …", `is_bad_bot = false`, and an in-WebView campaign check that shows a count, not `null`. |
| 3. Gate test | "Without token: dropCampaigns: null" and "With token: N campaigns". |
| 4. Background | Start, lock the phone for an hour or more, come back. The tick count should roughly equal minutes elapsed, with the largest gap near 60s. |

Try both mint options: **Light** (Streamlink's blank page) vs **Full site** (DropForge's
approach), and **On screen** vs **Off screen**. Off screen matters most, because a
background miner will have to renew tokens with no UI.

### Reading the results
- **In-WebView check passes, gate test "with token" fails:** Twitch accepts the token but
  rejects the app's own HTTP client (OkHttp's fingerprint differs from Chromium's).
  Fix: route gated GQL calls through the WebView's `fetch`.
- **`is_bad_bot = true` or the in-WebView check fails:** Twitch distrusts the WebView.
  Try Full site / On screen; if it still fails, fall back to the public campaign catalogue
  (as DropForge does) for discovery.
- **Big tick gaps:** tap "Allow background". On GrapheneOS, also check
  Settings → Apps → DropSpike → Battery → Unrestricted.

## GrapheneOS notes
- No Google Play Services are used anywhere.
- The WebView is Vanadium's. Its hardening (e.g. JIT restrictions) may slow Kasada's
  script; the 60s timeout allows for this. If minting fails only on GrapheneOS, try
  another device before concluding the approach is dead.
- Notification permission is requested when you start the background session.

## Layout
```
app/src/main/java/dev/dropspike/
  twitch/IntegrityMinter.kt   Kasada + /integrity in a WebView (port of DropForge integrity.py)
  twitch/TwitchApi.kt         validate, ViewerDropsDashboard, Inventory (persisted queries)
  service/MinerService.kt     foreground service, 60s tick, inventory every 5 ticks
  ui/                         Compose UI, login WebView, view model
  data/                       prefs, in-app diagnostics log
```

## After the spike: porting DropForge
If steps 2 and 3 pass, port these next, in this order:
1. `models/channel.py`: channel selection and the watch heartbeat (`send_watch`, a
   minute-watched POST to the channel's spade URL; no video is downloaded).
2. `models/inventory.py` and `network/twitch.py`: campaign/drop models, progress, claiming
   (`DropsPage_ClaimDropRewards`, which is integrity-gated).
3. `network/websocket.py`: PubSub for live progress and claim events.
4. `web/catalog.py`: ttvdrops catalogue fallback for discovery.

