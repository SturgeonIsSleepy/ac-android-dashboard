param([string]$GameDir='D:\Program Files\steam\steamapps\common\assettocorsa')
$ErrorActionPreference='Stop'
$gamePath=[IO.Path]::GetFullPath($GameDir)
if (-not (Test-Path -LiteralPath (Join-Path $gamePath 'acs.exe'))) { throw 'Assetto Corsa folder not found' }
$relayPath=[IO.Path]::GetFullPath((Join-Path $gamePath 'apps\lua\acflip_relay'))
if (-not $relayPath.StartsWith($gamePath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid relay destination' }
New-Item -ItemType Directory -Force -Path $relayPath | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot\relay\acflip_relay\manifest.ini" -Destination $relayPath -Force
Copy-Item -LiteralPath "$PSScriptRoot\relay\acflip_relay\acflip_relay.lua" -Destination $relayPath -Force
$oldEntry=Join-Path $relayPath 'script.lua'
if (Test-Path -LiteralPath $oldEntry) { Remove-Item -LiteralPath $oldEntry }
$statePath=Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Assetto Corsa\cfg\extension\state\imgui_settings.ini'
if (Test-Path -LiteralPath $statePath) {
    $state=[IO.File]::ReadAllText($statePath)
    $pattern='(?m)(^\[LUA_APPS\]\r?\n)ACTIVE=([^\r\n]*)'
    $match=[regex]::Match($state,$pattern)
    if ($match.Success -and ($match.Groups[2].Value.Split(',') -notcontains 'acflip_relay')) {
        if (-not (Test-Path -LiteralPath ($statePath+'.acflip-backup'))) { Copy-Item -LiteralPath $statePath -Destination ($statePath+'.acflip-backup') }
        $updated=$state.Substring(0,$match.Index)+$match.Value+',acflip_relay'+$state.Substring($match.Index+$match.Length)
        [IO.File]::WriteAllText($statePath,$updated,[Text.UTF8Encoding]::new($false))
    }
}
Write-Host 'Installed read-only CSP relay. A new driving session might be needed to load it.'
