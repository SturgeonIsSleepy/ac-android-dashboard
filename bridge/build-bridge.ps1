$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $compiler /nologo /optimize+ /target:exe "/out:$sourceRoot\ACFlipBridge.exe" "$PSScriptRoot\Bridge.cs" "$PSScriptRoot\RallyGuide.cs" "$PSScriptRoot\ConsoleDashboard.cs" "$PSScriptRoot\DisplayTelemetry.cs" "$PSScriptRoot\AcdReader.cs" "$PSScriptRoot\RaceContext.cs" "$PSScriptRoot\TcpRelay.cs" "$PSScriptRoot\UsbLink.cs" "$sourceRoot\vendor\assettocorsasharedmemory\Physics.cs" "$sourceRoot\vendor\assettocorsasharedmemory\Graphics.cs" "$sourceRoot\vendor\assettocorsasharedmemory\StaticInfo.cs"
if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed' }
