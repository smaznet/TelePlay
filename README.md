# TelePlay

Android TV player for videos you send to your own Telegram bot. Enter a bot token, pair your Telegram
account with a one-time code, and browse and stream the videos you send to the bot. No personal
account login, no Telegram library: the app speaks MTProto directly (`app/.../mtproto`), signing in as
the bot, so files of any size stream with seeking and a resumable 2 GB cache.

## Build

1. Get an `api_id` and `api_hash` at <https://my.telegram.org> (API development tools).
2. Add them to `local.properties` (git-ignored):
   ```
   telegram.apiId=123456
   telegram.apiHash=0123456789abcdef0123456789abcdef
   ```
3. `./gradlew :app:assembleStandardDebug` (JDK 17+). Unit tests: `./gradlew :app:testStandardDebugUnitTest`.
   Size gate: `./gradlew :app:checkTelegramLayerSize`.

## Use

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy its token.
2. Launch the app on the TV and enter the token with the remote, or set it up from your phone: scan the
   QR code on the token screen (phone on the same Wi-Fi as the TV), paste the token on the page that opens
   and send it. The link works once, expires after 10 minutes and a fresh QR code replaces it.
3. The TV shows an 8-character pairing code. Send it to your bot from your Telegram account (the first
   sender wins; the code expires after 15 minutes).
4. Send videos to the bot; they appear in the library. Telegram keeps unread bot messages for about
   24 hours, so connect within that window; the app keeps its own history afterwards.

Settings lets you clear the cache, reset pairing or disconnect (erases token, history and cache; saved proxies are kept).

### Remote control from Telegram

Once paired, the bot talks back to you in your private chat with it (and only there):

- **Added notices**: when a video you sent is added, the bot says so (several videos sent within about
  10 seconds come as one message; sending the same file again says "Already in your library").
- **Now playing remote**: while a video plays on the TV, one message is pinned in the chat with the title,
  progress and buttons for previous / back 30 s / play-pause / forward 30 s / next. Pressing a button
  controls the TV. The message is updated in place about every 30 seconds, and ends as "Stopped" or
  "Finished" (buttons removed, unpinned, message kept) when playback stops.

Both can be switched off separately in Settings ("Added-video notices", "Telegram remote"). The remote works
while the TV app is running (also in the background); it cannot start a closed app. Only your paired
account can use the buttons, and the messages never contain the token or the pairing code.

### Backing up and restoring the playlist

Changing or re-logging in can empty the library, so the bot can save and restore it. In your private chat
with the bot:

- `/export` sends a small backup file (titles, where each video lives in Telegram, watch progress; never
  the token or pairing code). Keep it in the chat.
- `/import` restores from a backup: reply `/import` to the file, or send the file with `/import` as its
  caption. Videos already in the library are not duplicated and newer local progress is kept. The bot
  replies with how many were restored, already present and skipped.
- `/help` explains the commands, which also show in the bot's menu.

Restoring works with the **same bot** that made the backup (Telegram file references belong to the bot).
If you changed Telegram accounts, forward the backup file to the new chat first, then reply `/import` to it.
Only your paired account can use these commands.

### Using a proxy (SOCKS5 and MTProto)

If Telegram is blocked or throttled where you are, TelePlay can reach it through a proxy. All Telegram
traffic (sign-in, updates, bot messages, update check and video streaming) goes through the proxy while
it is on; there is no silent fallback to a direct connection.

- **Types**: SOCKS5 (with optional username and password) and MTProto proxies with every secret kind:
  a plain 32-hex secret, `dd` secrets (random padding) and `ee` secrets (fake TLS towards a cover domain).
  Links in the `t.me/socks`, `t.me/proxy`, `tg://socks` and `tg://proxy` forms are understood, with hex or
  base64url secrets.
- **Setup**: the token screen has **Add or choose a proxy**, and the phone setup page has a "Telegram blocked?
  Add a proxy first" section (a link, or the details). A proxy added there is turned on straight away so the
  token can be checked.
- **Home**: a **Proxy** switch at the top of Home turns the selected proxy on or off without restarting, with
  the proxy's name and its state beside it. If it stops working, OK offers Retry, Choose another or Go direct.
- **Settings > Proxies**: add, edit, select, test and delete proxies. **Check all** above the list tests every
  saved proxy and shows which ones work, with latency ("3 of 7 working"). Saved proxies are kept when you
  disconnect, and removed only when you delete them.
- **From Telegram**: send `/proxies` followed by proxy links, or reply `/proxies` to a message that has them
  (up to 100 links). They are saved but never turned on by the bot; the reply only gives counts.

Proxy passwords and secrets are stored encrypted, are never logged and never appear in bot messages,
QR codes or backup files.

### F-Droid build

The `fdroid` flavor (`./gradlew :app:assembleFdroidRelease`) uses the package name `com.smaznet.teleplay.fdroid`,
has no in-app updater (no **Check for update** row, no `REQUEST_INSTALL_PACKAGES` permission, no update file provider)
and leaves updating to F-Droid. The `standard` flavor keeps the updater described below. Store listing texts live in
`fastlane/metadata/android/`.

### Updating from Telegram (standard flavor only)

Settings has **Check for update** (enabled once a bot is connected). It reads the public announcement in
post 2 of `@TelePlayUpdate` and compares the number with the installed version. To publish a release the
channel owner posts the signed APK, then edits post 2 to exactly:

```
latest:
code: <versionCode of that APK>
https://t.me/TelePlayUpdate/<post id of the APK>
```

If the number is higher the app asks, downloads the APK through the connected bot, checks it (size, package
name, version code) and opens the system installer. Nothing is sent to Telegram and nothing runs unless
you select the option. See `specs/004-telegram-app-update/`.

## Telegram types

The TL types are generated from a pinned schema for exactly the allowed methods:
`python3 tools/tlgen/tlgen.py` (stdlib only). `tools/tlgen/fixtures.py` writes test fixtures with an
independent serializer. Allowed methods are listed in `tools/tlgen/roots.txt` (including the four
message methods the Telegram remote uses, see `specs/003-remote-control-notifications/contracts/`, and the
two read-only channel methods the update check uses, see `specs/004-telegram-app-update/contracts/`).

## Live test

Set `TELEPLAY_LIVE_TOKEN` (and optionally `TELEPLAY_LIVE_MESSAGE_ID`) to run the opt-in live tests.

Specs and plan: `specs/001-telegram-bot-player/`.

## License

GPL-3.0-only, see [LICENSE](LICENSE).
