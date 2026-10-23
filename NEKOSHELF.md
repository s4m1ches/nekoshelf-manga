# Nekoshelf Manga

A small fork of [Komikku](https://github.com/komikku-app/komikku) made for an e-ink reader
(developed on a Meebook E6) and for [Nekoshelf Launcher](https://github.com/s4m1ches/nekoshelf),
which shows the manga library on its home screen. On the device the app is labelled «Манга».

Branch `nekoshelf`, based on the upstream release tag stored in `.nekoshelf-base`. Everything
else is upstream Komikku, unchanged.

## What the fork adds

All of it is exposed by a separate build type, `nekoshelf`, with its own applicationId
`app.nekoshelf.manga`, so it installs next to stock Komikku. Upstream's own build types and
applicationId are untouched.

- **Bridge provider** `content://app.nekoshelf.manga.bridge/…` (read-only, exported, guarded by
  the signature permission `app.nekoshelf.manga.permission.BRIDGE`):
  - `library` — one row per favourite manga: title, source, read/total chapters, next unread
    chapter, cover version and `chapters_new` (unread chapters fetched after the last read);
  - `cover/<manga_id>` — the cached cover image;
  - `backup` — a freshly created full backup (normal `.tachibk` content), used by the
    launcher's cloud backup;
  - `stats_daily` and `totals` — reading statistics (see below).

  When the reader is paused the app calls `notifyChange` on
  `content://app.nekoshelf.manga.bridge/`.
- **Open activity** `komikku.nekoshelf.bridge.BridgeOpenActivity` (action
  `app.nekoshelf.manga.action.OPEN_MANGA`, extra `manga_id`, same permission): opens the reader at
  the chapter in progress, else at the next unread chapter, else the manga's details screen.
- **Reading log / statistics**: a page counts as read after 5 s on screen and its time is capped
  at 120 s (the same rules as KOReader's statistics). Pages and seconds are stored per local day
  in the app's own `nekoshelf_bridge.db`. On first start the existing Komikku reading history is
  imported once (`backfilled = 1`, seconds only). Incognito mode is respected.
- **E-ink**: a black-and-white line adaptive icon and the plain «Манга» label
  (`app/src/nekoshelf/res/`). The build is not minified. Telemetry stays off.

The full interface (columns, types, behaviour) is the bridge contract kept in the launcher
repository.

Code conventions: our Kotlin code is in `komikku.nekoshelf.bridge`
(`app/src/main/java/komikku/nekoshelf/bridge/`, tests under `app/src/test/`), our manifest and
resources are in `app/src/nekoshelf/`, and every edit in an upstream file is wrapped in
`// NEKOSHELF -->` / `// NEKOSHELF <--` (`-- NEKOSHELF -->` in SQL). Upstream locale files are
never edited. This keeps rebases onto new Komikku releases small.

## Build

    ./gradlew :app:assembleNekoshelf

Output: `app/build/outputs/apk/nekoshelf/app-universal-nekoshelf.apk` (plus per-ABI APKs).

Signing: set the Gradle property (for example in `~/.gradle/gradle.properties`) or the
environment variable `NEKOSHELF_KEYSTORE_PROPERTIES` to the path of a `.properties` file with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it the build is signed with
the default debug key, which is fine for trying the app. If the variable is set but the file is
missing, the build fails instead of silently using the debug key. Never commit the keystore or
its properties file.

**Both apps must be signed with the same key.** The bridge permission is `signature`-level:
Nekoshelf Launcher only gets access if it is signed with the same certificate as Nekoshelf Manga.
Install Nekoshelf Manga first (or reinstall the launcher afterwards) — Android only grants a
custom permission that already exists when the client is installed.

Unit tests of the fork's code:

    ./gradlew :app:testNekoshelfUnitTest --tests 'komikku.nekoshelf.bridge.*'

## Install

    adb install -r app/build/outputs/apk/nekoshelf/app-universal-nekoshelf.apk

Coming from stock Komikku: make a backup there (Settings → Backup and restore → Create backup),
restore it in «Манга», reinstall extensions if needed, and compare the number of titles before
removing the old app. Both apps coexist, so nothing is lost by keeping the old one until then.

On the Meebook E6 the per-app e-ink refresh mode is a row in the system provider
`content://com.android.systemui.eink/einksettings` keyed by `package_name`
(`app.nekoshelf.manga`); `adb shell content insert/update` with e.g. `refresh_mode=10`,
`refresh_frequency=5` sets it. Other devices have their own settings.

## Updating to a new Komikku release

Remote `upstream` must point to `https://github.com/komikku-app/komikku`. From the repository
root (Windows PowerShell 5.1):

    powershell -ExecutionPolicy Bypass -File scripts\nekoshelf-update.ps1 [-Tag v1.15.0]

Without `-Tag` the newest `v*` release tag is used. The script runs `git fetch upstream --tags`,
then `git rebase --empty=drop --onto <tag> <old base> nekoshelf` (the old base is read from
`.nekoshelf-base`; commits that become empty, e.g. a cherry-picked upstream fix, are dropped),
records the new base in `.nekoshelf-base`, then runs `spotlessApply`, `spotlessCheck`, the
`komikku.nekoshelf.bridge.*` tests and `:app:assembleNekoshelf`, and prints the `adb install`
command. It refuses to start with uncommitted changes and **never pushes**.

On a rebase conflict the rebase is aborted, the conflicting files are listed and the branch is
left as it was; resolve the conflict by hand. If the build or tests fail after a successful
rebase, the branch is already on the new tag: fix, commit and run the script again — it sees that
the branch contains the tag, skips the rebase and repeats the checks and the build.

## Licence

Apache License 2.0, like Komikku (see `LICENSE`). The additions listed above are by Samich and
are released under the same licence. Not affiliated with Komikku, Mihon or Meebook.
