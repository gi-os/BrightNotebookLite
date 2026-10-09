# BrightNotebook Lite

Notes and a calendar for the Light Phone III, built as an official Light SDK tool. Launcher
label **Notebook Lite**, tool id `com.gios.brightnotebooklite`.

This is the SDK-compatible cut of [BrightNotebook](https://github.com/gi-os/BrightNotebook), a
sideloaded app. The Light SDK restricts which Android APIs and libraries a tool may use, so this
version keeps the core of the original (plain-text notes, a calendar with repeating entries,
subscribed calendar feeds) and drops the parts the SDK cannot support. Each dropped feature is
listed below with the restriction behind it.

## What it does

**Notes.** A note is plain text that carries its own markers: `**bold**`, `- ` bullets,
`1. ` numbers and `- [ ]` / `- [x]` checkboxes. Bold runs are drawn bold and the markers are
drawn as bullets and boxes, but the stored text never changes shape, so a note reads the same
anywhere it ends up. Tapping a box rewrites that one line, addressed by line number, so two
identical `- [ ] milk` lines tick independently. The editor carries a list marker down when you
press return and ends the list on an empty item; its bottom bar has a bold key and a checkbox key.
The list is sorted by when each note was last touched.

**Calendar.** A month grid (tap a day to open it; the wheel or a sideways swipe steps months)
and a day view. Typing `9:30 dentist` files the entry at half past nine; a bare leading number
is not a time, so `3 loads of laundry` stays an all-day entry. An entry can have a start and end
time, last several days, and repeat.

**Repeats.** Daily, every weekday, weekly on the entry's weekday, every other week, monthly or
yearly, ending never or on a date. A series is one database row holding an RFC 5545 `RRULE`;
the days it lands on are worked out for whatever is on screen by `util/Recurrence.kt`, ported
unchanged from the original along with its tests. It reads `FREQ`, `INTERVAL`, `BYDAY`
(including `1MO` and `-1FR`), `BYMONTHDAY`, `COUNT`, `UNTIL` and `EXDATE`, and refuses the rest
rather than expanding it wrongly: a refused rule shows once, on its start day. Editing or deleting
one occurrence asks **just this one** or **all of them**. "Just this one" writes an exception date
on the series; editing first detaches that occurrence into an entry of its own.

**Subscribed calendars.** Calendars → SUBSCRIBE takes a published `.ics` feed or a `webcal://`
link, either scanned as a QR code with the SDK's `LightQrCodeScanner` or typed. The feed is
fetched immediately so a wrong address says so on the spot, then refreshed every hour by a
`LightWork` periodic job. Each refresh replaces that calendar's rows in one transaction; a failed
refresh keeps the last good copy and shows why it failed. The `.ics` parser handles folded lines,
`TZID` and UTC times, all-day dates, `RRULE`, `EXDATE` and moved instances (`RECURRENCE-ID`).
Feeds can be hidden, refreshed by hand or removed.

**US federal holidays** are computed on the phone from the rules, with observed dates as
separate entries (a Saturday Independence Day also puts "Independence Day (observed)" on the
Friday). They can be switched off on the Calendars page.

**A day reads as a diary once it has happened and as a calendar until then.** Today is split by
a NOW line at the current time; earlier rows are dimmed. Notes you wrote or came back to on a day
appear on that day at the time you touched them, marked "Wrote this" or "Came back to this", and
open the note on a tap. A day runs from 4am to 4am, so something written at 1am belongs to the
night before (`util/JournalDay.kt`).

**The wheel scrolls** the notes list, a note, a day and the Calendars page, and steps months on
the calendar. The wheel's keycodes are read in `LightViewModel.onKeyDown`.

**Storage** is Room, opened with the SDK's own `SealedLightContext.buildDatabase`, so the
screens and the background job share one database without reaching for an Android `Context`.
The holiday switch lives in the SDK's DataStore.

## What was dropped, and why

| Feature in BrightNotebook | Why it is not here |
| --- | --- |
| Photographing a page and having Claude transcribe it or extract events | Needs CameraX for a real capture. The SDK allowlist has no CameraX; the only camera access is `LightQrCodeScanner`. |
| Mirroring entries into the phone's calendar provider, importing from it, photos on the day, call log | All of these go through `contentResolver`, which the SDK blocks. |
| Exact-time reminders | Need `AlarmManager` (`android.app.*`, blocked) and a notification, and the SDK exposes no notification API to tools. |
| `lightnotebook://` links from other apps, and opening Roll for capture | Need `Intent` and `startActivity`, both blocked. |
| BrightPasses, Roll, BrightChat, BrightLedger and BrightRemote bridges | Each reads another app's data through an intent or content provider. |
| Shake to report a glitch | Needs `SensorManager` from `getSystemService`, which is blocked. |
| Weather, daylight, steps, screen time, places, backup files | Out of scope for a lite cut. Weather would be possible over the network, but it was left out to keep the tool small. |
| A pinch-zoom wall planner | Replaced by a plain month grid and day view. |
| A time zone override | Times use the phone's zone, which is shown on the Calendars page. |

## Build

You need JDK 17 and the Android SDK (platform 36).

```sh
./gradlew -DlightSdk.toolOnly=true :tool:assembleDebug
./gradlew -DlightSdk.toolOnly=true :tool:testDebugUnitTest :tool:lintDebug
```

All parsing and date logic sits in `tool/src/main/kotlin/com/gios/brightnotebooklite/util/`,
free of Android imports, with tests in `tool/src/test/`. CI (`.github/workflows/build-tool.yml`)
runs the tests, lint and the debug build on every push with the same flags as Light's tool builder.
It publishes nothing.

To install on a phone or emulator running the Light SDK server, see `./gradlew :tool:uploadTool`
in `tool/build.gradle.kts`.

## Permissions

- `INTERNET`, to fetch subscribed calendar feeds.
- `CAMERA`, for scanning a feed address as a QR code. It is asked for the first time the scanner
  opens, not at launch.

## Tool Library submission notes

- The tool id, label and version live in `tool/lighttool.toml`. Bump `versionCode` for every
  submission.
- Everything is in the `tool` module. `sdk/`, `plugin/` and `lint-rules/` are upstream and
  unmodified, so this repository can be rebased onto
  [lightphone/light-sdk](https://github.com/lightphone/light-sdk).
- Only allowlisted dependencies are used: Room (with its KSP compiler), DataStore, ktor with the
  OkHttp engine, WorkManager through `LightWork`, and Compose.
- No reflection, no blocked imports, no custom manifest.

## Credits

Built on [lightphone/light-sdk](https://github.com/lightphone/light-sdk) (MIT); see `LICENSE`.
The notes, recurrence, `.ics` and holiday logic are ported from
[gi-os/BrightNotebook](https://github.com/gi-os/BrightNotebook) (MIT).
