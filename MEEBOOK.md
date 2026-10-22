# «Манга» — Meebook build of Komikku

Personal fork of [Komikku](https://github.com/komikku-app/komikku) for the Meebook E6 e-reader.
Branch `meebook`, based on upstream tag `v1.14.1`. Upstream remote: `upstream`.

## What is changed

- New build type `meebook` (`./gradlew :app:assembleMeebook`): `initWith(release)`, applicationId
  `app.komikku.meebook` (installs next to stock Komikku), not minified, signed with the owner's key
  from `<keystore.properties>` (never committed). Telemetry stays off.
- Launcher label «Манга» (`app/src/meebook/res/values/strings.xml`, `meebook_app_name`).
- Black-and-white line adaptive icon (open book + speech bubble) in `app/src/meebook/res/drawable/`.
- All our code is marked `// MEEBOOK -->` / `// MEEBOOK <--`; our strings live only in
  `app/src/meebook/res/values/strings.xml`. Upstream locale files are never edited.

## Install and e-ink refresh

    adb install -r app/build/outputs/apk/meebook/app-universal-meebook.apk

E-ink row (Regal, refresh 5) for `app.komikku.meebook` only, via `content insert/update` on
`content://com.android.systemui.eink/einksettings` with `package_name=app.komikku.meebook`,
`refresh_mode=10`, `refresh_frequency=5`.

## Migration from stock Komikku (owner)

1. In Komikku: Настройки → Резервное копирование → Создать.
2. In «Манга»: first launch onboarding (storage folder `/storage/emulated/0/Manga`), then Восстановить
   from `/sdcard/Manga/autobackup` or the chosen file.
3. Reinstall extensions if they are missing.
4. Check that the library opens, then uninstall Komikku.
