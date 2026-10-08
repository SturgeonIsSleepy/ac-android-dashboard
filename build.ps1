param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Gradle = '',
    [string]$GradleUserHome = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path -LiteralPath $SdkRoot)) { throw 'Android SDK not found; set ANDROID_HOME or pass -SdkRoot' }
if (-not $GradleUserHome) { $GradleUserHome = Join-Path $env:USERPROFILE '.gradle' }
if (-not $Gradle) {
    $command = Get-Command gradle -ErrorAction SilentlyContinue
    if ($command) { $Gradle = $command.Source }
    else {
        $cached = Get-ChildItem -LiteralPath (Join-Path $GradleUserHome 'wrapper\dists\gradle-8.11.1-bin') -Filter gradle.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cached) { $Gradle = $cached.FullName }
    }
}
if (-not $Gradle -or -not (Test-Path -LiteralPath $Gradle)) { throw 'Gradle 8.11.1 not found; install it or pass -Gradle' }
$env:ANDROID_HOME = $SdkRoot
$env:GRADLE_USER_HOME = $GradleUserHome
New-Item -ItemType Directory -Force -Path "$projectRoot\dist" | Out-Null
& $gradle --no-daemon -p $projectRoot assembleDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
Copy-Item "$projectRoot\app\build\outputs\apk\debug\app-debug.apk" "$projectRoot\dist\ac-flip-0.8.1.apk"
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /optimize+ /target:exe "/out:$projectRoot\dist\ACFlipBridge.exe" "$projectRoot\bridge\Bridge.cs" "$projectRoot\bridge\RallyGuide.cs" "$projectRoot\bridge\ConsoleDashboard.cs" "$projectRoot\bridge\DisplayTelemetry.cs" "$projectRoot\bridge\AcdReader.cs" "$projectRoot\bridge\RaceContext.cs" "$projectRoot\vendor\assettocorsasharedmemory\Physics.cs" "$projectRoot\vendor\assettocorsasharedmemory\Graphics.cs" "$projectRoot\vendor\assettocorsasharedmemory\StaticInfo.cs"
if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed' }
Write-Host 'Created dist/ac-flip-0.8.1.apk and dist/ACFlipBridge.exe'
