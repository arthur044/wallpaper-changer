<#
.SYNOPSIS
    Gera o APK de release assinado e, se o celular estiver conectado, instala por cima.

.DESCRIPTION
    1. Confere os pré-requisitos (keystore.properties, a chave, o SDK).
    2. Roda o Gradle (:app:assembleRelease).
    3. Confere que o APK saiu assinado com a sua chave.
    4. Guarda uma cópia em android\dist\ com versão, data e commit no nome.
    5. Com o celular conectado por USB, instala por cima (adb install -r),
       o que mantém login e configurações.

.PARAMETER Install
    Instala sem perguntar, se houver um celular conectado.

.PARAMETER NoInstall
    Só gera o APK; nunca instala.

.EXAMPLE
    .\build-apk.ps1
.EXAMPLE
    .\build-apk.ps1 -Install
#>
[CmdletBinding()]
param(
    [switch]$Install,
    [switch]$NoInstall
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Fail([string]$message) {
    Write-Host ""
    Write-Host "ERRO: $message" -ForegroundColor Red
    exit 1
}

function Step([string]$message) {
    Write-Host ""
    Write-Host "== $message" -ForegroundColor Cyan
}

# Java .properties values escape ':' as '\:' and '\' as '\\'.
function Read-Properties([string]$path) {
    $props = @{}
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*([^#!=\s][^=]*?)\s*=\s*(.*)$') {
            $props[$Matches[1]] = $Matches[2] -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
    return $props
}

# --- 1. Pré-requisitos -------------------------------------------------------
Step "Conferindo pré-requisitos"

if (-not (Test-Path 'keystore.properties')) {
    Fail "android\keystore.properties não existe. Sem ele o APK sai sem assinatura e não instala por cima do app que você usa."
}
$storeFile = (Read-Properties 'keystore.properties')['storeFile']
if (-not $storeFile) { Fail "keystore.properties não tem 'storeFile'." }
if (-not [System.IO.Path]::IsPathRooted($storeFile)) { $storeFile = Join-Path $PSScriptRoot "app\$storeFile" }
if (-not (Test-Path -LiteralPath $storeFile)) {
    Fail "A chave de assinatura não foi encontrada em '$storeFile'. Sem ela não dá para gerar um APK que atualize o app instalado."
}

if (-not (Test-Path 'local.properties')) { Fail "android\local.properties não existe (precisa de sdk.dir)." }
$sdkDir = (Read-Properties 'local.properties')['sdk.dir']
if (-not $sdkDir -or -not (Test-Path -LiteralPath $sdkDir)) { Fail "sdk.dir em local.properties não aponta para o SDK do Android." }
$adb = Join-Path $sdkDir 'platform-tools\adb.exe'
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkDir 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending | Select-Object -First 1
$apksigner = if ($buildTools) { Join-Path $buildTools.FullName 'apksigner.bat' } else { $null }

if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
    Fail "Java não encontrado (nem no PATH nem em JAVA_HOME). O Gradle precisa do JDK 17."
}
Write-Host "ok"

# --- 2. Build ----------------------------------------------------------------
Step "Gerando o APK de release (pode levar alguns minutos)"
& .\gradlew.bat --no-daemon :app:assembleRelease
if ($LASTEXITCODE -ne 0) { Fail "O build falhou. Veja as mensagens do Gradle acima." }

$apk = 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) {
    if (Test-Path 'app\build\outputs\apk\release\app-release-unsigned.apk') {
        Fail "O APK saiu SEM assinatura: confira os dados em keystore.properties."
    }
    Fail "O APK não foi encontrado em $apk."
}

# --- 3. Assinatura ------------------------------------------------------------
Step "Conferindo a assinatura"
if ($apksigner -and (Test-Path -LiteralPath $apksigner)) {
    $certs = & $apksigner verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "A assinatura do APK não confere:`n$certs" }
    ($certs | Select-String 'certificate DN') | ForEach-Object { Write-Host $_.Line.Trim() }
} else {
    Write-Host "apksigner não encontrado; pulando a conferência." -ForegroundColor Yellow
}

# --- 4. Cópia com nome identificável ----------------------------------------
Step "Guardando uma cópia"
$gradle = Get-Content -Raw 'app\build.gradle.kts'
$version = if ($gradle -match 'versionName\s*=\s*"([^"]+)"') { $Matches[1] } else { 'dev' }
$commit = (& git rev-parse --short HEAD 2>$null)
if (-not $commit) { $commit = 'nogit' }
$dirty = (& git status --porcelain --untracked-files=no 2>$null)
if ($dirty) { $commit = "$commit-alterado" }
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
New-Item -ItemType Directory -Force 'dist' | Out-Null
$copy = Join-Path 'dist' "wallpaper-changer-$version-$stamp-$commit.apk"
Copy-Item $apk $copy
$full = (Resolve-Path $copy).Path
Write-Host $full

# --- 5. Instalação -------------------------------------------------------------
if ($NoInstall) {
    Write-Host ""
    Write-Host "Pronto. APK em: $full" -ForegroundColor Green
    exit 0
}
if (-not (Test-Path -LiteralPath $adb)) {
    Write-Host ""
    Write-Host "adb não encontrado; instale o APK copiando o arquivo para o celular." -ForegroundColor Yellow
    Write-Host "Pronto. APK em: $full" -ForegroundColor Green
    exit 0
}

Step "Procurando o celular"
$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
$ready = @($devices | Where-Object { $_ -match '\tdevice$' })
$unauthorized = @($devices | Where-Object { $_ -match '\tunauthorized$' })

if ($unauthorized.Count -gt 0) {
    Write-Host "O celular está conectado mas não autorizou a depuração USB: desbloqueie a tela e aceite o aviso." -ForegroundColor Yellow
}
if ($ready.Count -eq 0) {
    Write-Host "Nenhum celular pronto conectado. Para instalar depois: conecte e rode de novo, ou copie o APK para o celular." -ForegroundColor Yellow
    Write-Host "Pronto. APK em: $full" -ForegroundColor Green
    exit 0
}
if ($ready.Count -gt 1) {
    Write-Host "Há mais de um aparelho conectado; instale manualmente com: `"$adb`" -s <serial> install -r `"$full`"" -ForegroundColor Yellow
    exit 0
}

$serial = ($ready[0] -split "`t")[0]
$model = (& $adb -s $serial shell getprop ro.product.model 2>$null)
Write-Host "Conectado: $model ($serial)"

if (-not $Install) {
    $answer = Read-Host "Instalar por cima do app atual, mantendo login e configurações? (s/n)"
    if ($answer -notmatch '^[sSyY]') {
        Write-Host "Pronto. APK em: $full" -ForegroundColor Green
        exit 0
    }
}

Step "Instalando"
& $adb -s $serial install -r $full
if ($LASTEXITCODE -ne 0) {
    Fail "A instalação falhou. Se a mensagem fala de assinatura (INSTALL_FAILED_UPDATE_INCOMPATIBLE), o app no celular foi assinado com outra chave: NÃO desinstale sem pensar, isso apaga login e configurações."
}
Write-Host ""
Write-Host "Instalado em $model. APK em: $full" -ForegroundColor Green
