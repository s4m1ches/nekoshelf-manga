<#
.SYNOPSIS
  Пересобирает «Манга» (ветка meebook) поверх нового релиза Komikku.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\meebook-update.ps1
  powershell -ExecutionPolicy Bypass -File scripts\meebook-update.ps1 -Tag v1.14.1
#>
param([string]$Tag)

# Windows PowerShell 5.1: stderr у git не должен ронять скрипт, проверяем $LASTEXITCODE сами.
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Fail([string]$msg) {
    Write-Host $msg -ForegroundColor Red
    exit 1
}

function Git {
    & git @args
    if ($LASTEXITCODE -ne 0) { Fail ("Ошибка: git " + ($args -join ' ') + " (код $LASTEXITCODE)") }
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }

# --- 0. Предусловия
if (-not (Test-Path '.meebook-base')) { Fail 'Нет файла .meebook-base (должен содержать тег текущей базы, например v1.14.1).' }
$oldBase = (Get-Content '.meebook-base' -Raw).Trim()
$dirty = & git status --porcelain
if ($dirty) { Fail 'Обновление остановлено: в рабочем дереве есть незакоммиченные изменения.' }
& git rev-parse --verify --quiet "refs/heads/meebook" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'Нет ветки meebook.' }

# --- 1. Fetch и выбор тега
Git fetch upstream --tags
if (-not $Tag) {
    $Tag = (& git tag -l 'v*' --sort=-v:refname | Where-Object { $_ -match '^v\d+\.\d+\.\d+$' } | Select-Object -First 1)
    if (-not $Tag) { Fail 'Не найден ни один релизный тег v*.' }
}
& git rev-parse --verify --quiet "refs/tags/$Tag^{commit}" | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Тег $Tag не найден в upstream." }
Write-Host "База: $oldBase -> $Tag" -ForegroundColor Cyan

# --- 2. Rebase. --empty=drop: коммиты, ставшие пустыми (cherry-pick апстримного фикса,
# который уже есть в новом релизе), тихо отбрасываются и не останавливают обновление.
& git rebase --empty=drop --onto $Tag $oldBase meebook
if ($LASTEXITCODE -ne 0) {
    $conflicts = (& git diff --name-only --diff-filter=U) -join ', '
    & git rebase --abort
    if (-not $conflicts) { $conflicts = '(файлы не определены, см. вывод git выше)' }
    Fail "Обновление остановлено: конфликт в $conflicts. Rebase отменён, ветка meebook не тронута. Разберите конфликт вручную."
}

# --- 3. Проверки и сборка (стоп на первой ошибке)
function Gradle([string[]]$gradleArgs) {
    Write-Host (".\gradlew.bat " + ($gradleArgs -join ' ')) -ForegroundColor Cyan
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) {
        Fail ("Обновление остановлено: упала задача gradle " + ($gradleArgs -join ' ') + ". Ветка meebook уже перебазирована на $Tag; .meebook-base НЕ обновлён.")
    }
}
Gradle @('spotlessApply')
Gradle @('spotlessCheck')
Gradle @(':app:testMeebookUnitTest', '--tests', 'komikku.meebook.bridge.*')
Gradle @(':app:assembleMeebook')

# --- 4. Фиксация результата
$dirty = & git status --porcelain
if ($dirty) {
    Git add -A
    Git commit -m 'style(meebook): spotlessApply after rebase' -m 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'
}
if ($oldBase -ne $Tag) {
    Set-Content -Path '.meebook-base' -Value $Tag -Encoding Ascii
    Git add .meebook-base
    Git commit -m "chore(meebook): rebase onto $Tag" -m 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'
}

# --- 5. Итог
$apk = Join-Path $repo 'app\build\outputs\apk\meebook\app-universal-meebook.apk'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
Write-Host ''
Write-Host "Готово: «Манга» собрана на $Tag." -ForegroundColor Green
Write-Host "APK: $apk"
Write-Host 'Установка на устройство:'
Write-Host "  & `"$adb`" install -r `"$apk`""
exit 0
