# Portable build helper. Downloads only build tools; never changes system PATH or registry.
# The generated APK is a development APK, not a Play Store release.
param([string]$SdkDir = '')
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root '.tools'
New-Item -ItemType Directory -Force -Path $Tools | Out-Null
Set-Location $Root
function Download([string]$Url, [string]$Destination, [string]$Checksum='', [string]$Algorithm='SHA256') {
    if (!(Test-Path $Destination)) {
        $Part = "$Destination.part"
        for ($Attempt=1; $Attempt -le 3; $Attempt++) {
            try { Invoke-WebRequest -Uri $Url -OutFile $Part -UseBasicParsing; Move-Item -Force $Part $Destination; break }
            catch { Remove-Item $Part -Force -ErrorAction SilentlyContinue; if ($Attempt -eq 3) { throw }; Start-Sleep -Seconds 3 }
        }
    }
    if ($Checksum) {
        $Actual = (Get-FileHash -Algorithm $Algorithm -LiteralPath $Destination).Hash
        if ($Actual -ne $Checksum.Trim()) { Remove-Item -Force $Destination; throw "Somme de controle incorrecte : $Destination" }
    }
}
try {
    Write-Host 'AutoDash - construction de l APK Android' -ForegroundColor Cyan
    Write-Host 'Les outils sont conserves dans le dossier .tools. Internet est necessaire.'
    $JdkFolder = Join-Path $Tools 'jdk'
    if (!(Test-Path (Join-Path $JdkFolder 'bin\java.exe'))) {
        Write-Host 'Telechargement du JDK 21 officiel Eclipse Temurin...'
        $Assets = Invoke-RestMethod -Uri 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'
        $Package = $Assets[0].binary.package
        if (!$Package.link -or !$Package.checksum) { throw 'Reponse Adoptium incomplete.' }
        $JdkZip = Join-Path $Tools 'jdk21.zip'
        Download $Package.link $JdkZip $Package.checksum
        $Extract = Join-Path $Tools 'jdk-extract'
        New-Item -ItemType Directory -Force -Path $Extract | Out-Null
        Expand-Archive -LiteralPath $JdkZip -DestinationPath $Extract -Force
        $Found = Get-ChildItem $Extract -Directory | Where-Object {Test-Path (Join-Path $_.FullName 'bin\java.exe')} | Select-Object -First 1
        if (!$Found) { throw 'JDK non trouve apres extraction.' }
        if (Test-Path $JdkFolder) { Remove-Item -Recurse -Force $JdkFolder }
        Move-Item $Found.FullName $JdkFolder
    }
    $env:JAVA_HOME = $JdkFolder
    $env:Path = "$JdkFolder\bin;$env:Path"
    if (!$SdkDir) { $SdkDir = Join-Path $Tools 'android-sdk' }
    New-Item -ItemType Directory -Force -Path $SdkDir | Out-Null
    $env:ANDROID_HOME = $SdkDir
    $env:ANDROID_SDK_ROOT = $SdkDir
    $SdkManager = Join-Path $SdkDir 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (!(Test-Path $SdkManager)) {
        Write-Host 'Telechargement des outils Android officiels Google...'
        $ArchiveName = 'commandlinetools-win-15859902_latest.zip'
        # SHA-256 published by developer.android.com/studio, checked 2026-11-16.
        $SdkHash = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
        $SdkZip = Join-Path $Tools $ArchiveName
        Download "https://dl.google.com/android/repository/$ArchiveName" $SdkZip $SdkHash
        $Extract = Join-Path $Tools 'sdk-extract'
        New-Item -ItemType Directory -Force -Path $Extract | Out-Null
        Expand-Archive -LiteralPath $SdkZip -DestinationPath $Extract -Force
        $Target = Join-Path $SdkDir 'cmdline-tools\latest'
        New-Item -ItemType Directory -Force -Path (Split-Path $Target) | Out-Null
        if (Test-Path $Target) { Remove-Item -Recurse -Force $Target }
        Move-Item (Join-Path $Extract 'cmdline-tools') $Target
    }
    Write-Host 'Lisez et acceptez les licences Android demandees pour continuer.' -ForegroundColor Yellow
    & $SdkManager "--sdk_root=$SdkDir" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Acceptation des licences Android interrompue.' }
    & $SdkManager "--sdk_root=$SdkDir" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
    if ($LASTEXITCODE -ne 0) { throw 'Installation du SDK Android impossible.' }
    $GradleVersion = '8.11.1'
    $Gradle = Join-Path $Tools "gradle-$GradleVersion\bin\gradle.bat"
    if (!(Test-Path $Gradle)) {
        $GradleZip = Join-Path $Tools "gradle-$GradleVersion-bin.zip"
        $Hash = (Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip.sha256" -UseBasicParsing).Content.Trim()
        Download "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" $GradleZip $Hash
        Expand-Archive -LiteralPath $GradleZip -DestinationPath $Tools -Force
    }
    $SdkProperty = $SdkDir.Replace('\','/').Replace(':','\:')
    [IO.File]::WriteAllText((Join-Path $Root 'local.properties'), "sdk.dir=$SdkProperty`n", (New-Object Text.UTF8Encoding($false)))
    & $Gradle --no-daemon --console=plain assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Gradle a signale une erreur de compilation. Aucun APK valide ne peut etre annonce.' }
    $Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
    if (!(Test-Path $Apk)) { throw 'Compilation terminee sans APK a l emplacement attendu.' }
    Copy-Item -Force $Apk (Join-Path $Root 'AutoDash-debug.apk')
    Write-Host "APK cree : $Root\AutoDash-debug.apk" -ForegroundColor Green
    Write-Host 'Installez cet APK sur votre autoradio Android. Les integrations restent a tester sur cet appareil.'
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host 'Alternative : ouvrir ce dossier dans Android Studio, puis Build > Build APK(s).'
    exit 1
}
