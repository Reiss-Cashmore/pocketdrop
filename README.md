<img src="art/pocketdrop.svg" width="96" alt="PocketDrop icon">

# PocketDrop

Earn Twitch drops in the background on Android. No video is streamed: PocketDrop sends Twitch
the same once-a-minute "minute-watched" heartbeat its player does, picks live drops-enabled
channels for the games you choose, and claims finished drops automatically.

- **Home:** what's being mined right now, a minute-by-minute uptime chart, and every campaign
  in progress.
- **Games:** your watch list (mined first, in order), a searchable box-art picker of every game
  with an active campaign, account-link warnings, and an optional background check (30 min to
  2 h). With **Auto mine** on (the default), mining starts by itself whenever the app wakes up
  and a watched game is live: on each check, when you open the app, and after a reboot or an
  update. Tapping Stop pauses auto mine for an hour.
- **Settings:** account, background permissions, appearance, a full shareable diagnostics
  report, and advanced tools.

It began as a spike to prove three things on Android, all now confirmed on a Pixel 10 Pro Fold
(GrapheneOS): a WebView login yields a web-client session, an Android WebView can mint a
Client-Integrity token Twitch accepts (unlocking the campaign list gated since 2026-09-18), and
a foreground service keeps mining overnight with the screen off.

The design follows [DropForge](https://github.com/HimanM/DropForge) (MIT). Its
`network/integrity.py` gets the same token by running Twitch's Kasada script in desktop
Chrome over CDP; here an Android WebView plays that role.

## Install

### From CI (no Android tooling needed)
1. Push this folder to a GitHub repo.
2. **Actions → Build APK → latest run → Artifacts → PocketDrop-apk.** Unzip it and install
   the APK on the phone.
3. For updates straight to the phone, push a tag (`git tag v0.1.0 && git push --tags`) and
   point [Obtainium](https://github.com/ImranR98/Obtainium) at the repo's releases.

Every build is signed with the same throwaway key in `keystore/`, so new builds install
over old ones. Swap in a private key before giving the app to anyone else.

### Locally
Open in Android Studio (or run `./gradlew assembleRelease` with the Android SDK installed).

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

## Credits
Integrity flow and GraphQL operations derived from
[DropForge](https://github.com/HimanM/DropForge) (MIT, © 2026 HimanM, © 2024 DevilXD) and
[Streamlink](https://github.com/streamlink/streamlink) (BSD-2-Clause).
