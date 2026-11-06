# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
People who send their own videos to a private Telegram bot and watch them on an Android TV, seated at a distance and driving the UI with a D-pad remote. Confirmed audience: a personal/household owner, and also shared or public use (confirmed in init). Note: the current design (specs/001) authorizes exactly one Telegram user per install; how shared/public use relates to that is not yet decided.

## Product Purpose
TelePlay is an Android TV player for videos sent to a Telegram bot. The viewer enters a bot token, pairs their Telegram account with a one-time code, then browses and streams the videos they send to the bot. Success: a video sent from a phone plays on the TV with seeking and resume, with no personal Telegram login and no file-size limit.

## Positioning
The app signs in as the bot over MTProto directly (no Telegram library, no user-account login), so files of any size stream with seeking and a resumable size-capped cache. Telegram is the library and transfer layer; the TV is only the screen.

## Operating Context
- Android TV, 10-foot viewing, remote-only input (D-pad, Back, play/pause); focus-driven navigation.
- Setup flow: token entry (typed with the remote, or sent from a phone by scanning a QR code that opens a local web setup page, spec 002) → 8-character pairing code sent to the bot from Telegram (expires in 15 minutes) → library.
- Library: newest first, with a "Continue watching" row for partly watched videos; duplicate sends of the same file collapse into one entry; start-from-beginning action.
- Telegram keeps unread bot messages about 24 hours, so first connection must happen in that window; the app keeps its own history afterwards.
- Settings: clear cache, reset pairing, disconnect (erases token, history, cache).

## Capabilities and Constraints
- Videos stream before full download; downloads resume; cache default 2 GB, oldest evicted first, erased on disconnect.
- History, metadata and progress are stored locally on the device.
- Only videos from the one authorized (paired) Telegram user are listed.
- A bot token gives full control of the bot; it must be handled as a secret (QR setup uses a one-time, short-lived access secret).
- Requires the builder's own Telegram `api_id`/`api_hash` in `local.properties`.
- Project governed by spec-kit specs in `specs/` and constitution in `.specify/memory/constitution.md`.

## Brand Commitments
Name: TelePlay. Copy so far is plain-language and calm (see `app/src/main/res/values/strings.xml`). No logo, brand colors or typography are committed; undecided.

## Evidence on Hand
Feature specs and UI screen contracts: `specs/001-telegram-bot-player/contracts/ui-screens.md`, `specs/002-qr-token-setup/contracts/ui-screens.md`; feature 003 (Telegram remote and notifications) has no TV screens beyond two Settings toggles, see `specs/003-remote-control-notifications/`. No testimonials, user numbers or store listing exist; none may be fabricated.

## Product Principles
1. The remote is the whole input device: every screen works with a D-pad and shows where focus is.
2. Setup is the product's hardest moment: reduce typing, explain what is happening, and always give a recovery path.
3. Plain language over Telegram or MTProto jargon; errors say what to do next.
4. Treat the token as a secret and the owner's library as private.
5. Playback first: from library to picture in as few presses as possible, resuming where the viewer left off.

## Accessibility & Inclusion
No product-specific standard established. Baseline for TV: legible at couch distance, visible focus, not reliant on color alone. Undecided beyond that.
