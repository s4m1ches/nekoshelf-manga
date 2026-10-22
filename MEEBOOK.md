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

## Миграция со стандартного Komikku (владелец)

Библиотека, история и категории переносятся через резервную копию. Komikku и «Манга» ставятся рядом
и не мешают друг другу, поэтому удалять Komikku можно только в конце.

1. В Komikku: Настройки -> Резервное копирование -> Создать. Запомните путь файла и **число
   манги в библиотеке** (его видно в заголовке/категориях библиотеки).
2. Скопируйте файл резервной копии туда, где «Манга» его увидит (например `/sdcard/Manga/autobackup`
   или `/sdcard/Download`).
3. Установите «Манга» (команда выше) и запустите: пройдите первичную настройку, папка хранилища
   `/storage/emulated/0/Manga`.
4. В «Манга»: Настройки -> Резервное копирование -> Восстановить, выберите файл из шага 1.
5. Если расширения (источники) не появились, установите их заново: Обзор -> Расширения.
6. Сверьте **число манги в библиотеке «Манга»** с записанным в шаге 1, откройте пару тайтлов и
   проверьте прогресс чтения. Если число не совпало, не удаляйте Komikku: повторите восстановление.
7. Только после совпадения удалите Komikku.

## Обновление

Чтобы пересобрать «Манга» на новом релизе Komikku, одной командой из корня репозитория
(Windows PowerShell 5.1):

    powershell -ExecutionPolicy Bypass -File scripts\meebook-update.ps1 [-Tag v1.15.0]

Без `-Tag` берётся новейший тег `v*` из `upstream`. Скрипт: `git fetch upstream --tags`, затем
`git rebase --onto <тег> <старая база> meebook` (старая база хранится в `.meebook-base`; коммиты,
ставшие пустыми после rebase, например cherry-pick апстримного фикса, отбрасываются автоматически),
затем `spotlessApply`, `spotlessCheck`, тесты `komikku.meebook.bridge.*` и `:app:assembleMeebook`.
При успехе обновляется `.meebook-base` и печатается команда `adb install -r`.
При конфликте rebase отменяется, выводится «Обновление остановлено: конфликт в ...» и список файлов,
ветка остаётся нетронутой; конфликт разбирается вручную. Скрипт ничего не пушит.
