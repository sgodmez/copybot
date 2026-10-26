param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$AppDir,
    [string]$NewDir,
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$ConfigFile,
    [string]$PluginManifest,
    [switch]$CopyOnly
)

# Updates a portable copy of Copybot: waits for every Copybot of the application folder to end, replaces only the
# application image (app and runtime mirrored, root files copied without deleting anything), relaunches Copybot, then
# deletes the temporary folder (the parent of NewDir, which must be the folder of this script). Plugins,
# configuration, preferences, logs and any user file are never touched. Log: LogDir\update.log.
# -ConfigFile: the --config-file Copybot was started with, given again to the relaunch.
# -PluginManifest: the plugin mode, instead of the application image (-NewDir is then not used): a JSON manifest
# { "install": [ { "source", "target" } ], "remove": [ path ] } written in the folder of this script. Only direct
# subfolders of AppDir\plugins are installed (mirrored from source) or removed; a manifest going beyond is refused,
# nothing is changed and Copybot is relaunched.
# -CopyOnly: the copy alone, run elevated when the application folder is not writable; the relaunch and the cleanup
# stay with the script that is not elevated.

$ErrorActionPreference = 'Continue'

# absolute paths without a final separator (a final \ would break the quotes given to robocopy)
function Get-FullPath([string]$path) {
    return [System.IO.Path]::GetFullPath($path).TrimEnd('\')
}

$AppDir = Get-FullPath $AppDir
$pluginMode = [bool]$PluginManifest
if ($pluginMode) {
    $PluginManifest = Get-FullPath $PluginManifest
    # the temporary folder of the update holds the manifest and the sources
    $TempDir = Get-FullPath (Split-Path -Parent $PluginManifest)
} elseif ($NewDir) {
    $NewDir = Get-FullPath $NewDir
    $TempDir = Get-FullPath (Split-Path -Parent $NewDir)
} else {
    exit 2
}
$PluginsDir = Join-Path $AppDir 'plugins'
$LogDir = Get-FullPath $LogDir

try {
    New-Item -ItemType Directory -Force -Path $LogDir -ErrorAction Stop | Out-Null
} catch {
    # no log possible: go on anyway
}
$log = Join-Path $LogDir 'update.log'

function Write-Log([string]$message) {
    $line = '{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $message
    try {
        Add-Content -LiteralPath $log -Value $line -Encoding UTF8 -ErrorAction Stop
    } catch {
        # the log is unavailable: nothing else to do
    }
}

# never elevated: the script itself is not
function Start-App {
    try {
        if ($ConfigFile) {
            $configArg = '"--config-file={0}"' -f $ConfigFile
            Start-Process -FilePath $Exe -ArgumentList $configArg -WorkingDirectory $AppDir -ErrorAction Stop
        } else {
            Start-Process -FilePath $Exe -WorkingDirectory $AppDir -ErrorAction Stop
        }
        Write-Log "Relaunched $Exe"
    } catch {
        Write-Log "Relaunch failed: $($_.Exception.Message)"
    }
}

# app and runtime mirrored, then the root files without deleting anything; robocopy codes 0-7 are a success, 8 and
# more a failure, which stops the copy (no runtime copied after a half-copied app). True when everything was copied.
function Copy-AppImage {
    $robocopy = Join-Path $env:SystemRoot 'System32\robocopy.exe'
    foreach ($sub in @('app', 'runtime')) {
        $src = Join-Path $NewDir $sub
        $dst = Join-Path $AppDir $sub
        if (-not (Test-Path -LiteralPath $src)) {
            Write-Log "robocopy of $sub skipped: $src missing"
            continue
        }
        & $robocopy $src $dst /MIR /XJ /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
        $code = $LASTEXITCODE
        if ($code -ge 8) {
            Write-Log "robocopy of $sub failed with code $code, the copy stops"
            return $false
        }
        Write-Log "robocopy of $sub done (code $code)"
    }
    & $robocopy $NewDir $AppDir /LEV:1 /XJ /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    $code = $LASTEXITCODE
    if ($code -ge 8) {
        Write-Log "robocopy of the root files failed with code $code"
        return $false
    }
    Write-Log "robocopy of the root files done (code $code)"
    return $true
}

function Test-ReparsePoint([string]$path) {
    $item = Get-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    return [bool]($item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint))
}

# deletes a folder tree without ever following a junction or a symbolic link: a link is deleted alone, never its target
function Remove-Tree([string]$path) {
    if (Test-ReparsePoint $path) {
        if (Test-Path -LiteralPath $path -PathType Container) {
            [System.IO.Directory]::Delete($path)
        } else {
            [System.IO.File]::Delete($path)
        }
        return
    }
    if (Test-Path -LiteralPath $path -PathType Container) {
        foreach ($child in @(Get-ChildItem -LiteralPath $path -Force)) {
            Remove-Tree $child.FullName
        }
        [System.IO.Directory]::Delete($path)
    } else {
        [System.IO.File]::SetAttributes($path, [System.IO.FileAttributes]::Normal)
        [System.IO.File]::Delete($path)
    }
}

# the manifest, checked: an object with Install (Source, Target) and Remove, or $null when it is unreadable or goes
# beyond the plugins folder (the reason is logged)
function Read-Manifest {
    try {
        $manifest = Get-Content -LiteralPath $PluginManifest -Raw -Encoding UTF8 -ErrorAction Stop | ConvertFrom-Json
    } catch {
        Write-Log "Manifest refused: unreadable ($($_.Exception.Message))"
        return $null
    }
    $installs = @()
    $removals = @()
    foreach ($item in @($manifest.install)) {
        $target = [string]$item.target
        $source = [string]$item.source
        # the rule of the catalogue codes (SAFE_NAME of PluginCatalog), without a final '.' that Windows trims:
        # "..." would otherwise name the plugins folder itself
        if (-not ($target -cmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') -or $target.EndsWith('.')) {
            Write-Log "Manifest refused: target '$target' is not a folder name"
            return $null
        }
        # the sources are unpacked next to the manifest, in the folder of this script, never elsewhere
        $scriptDir = Get-FullPath (Split-Path -Parent $PSCommandPath)
        $sourceFull = if ($source) { Get-FullPath $source } else { '' }
        $sourceParent = if ($sourceFull) { Split-Path -Parent $sourceFull } else { '' }
        if (-not $sourceParent -or -not $TempDir.Equals($scriptDir, [System.StringComparison]::OrdinalIgnoreCase) -or
            -not $TempDir.Equals($sourceParent, [System.StringComparison]::OrdinalIgnoreCase)) {
            Write-Log "Manifest refused: source '$source' is not a direct subfolder of the folder of this script"
            return $null
        }
        if (-not (Test-Path -LiteralPath $sourceFull -PathType Container) -or (Test-ReparsePoint $sourceFull)) {
            Write-Log "Manifest refused: source '$source' is not a plain folder"
            return $null
        }
        $installs += [pscustomobject]@{ Source = $sourceFull; Target = $target }
    }
    foreach ($item in @($manifest.remove)) {
        if (-not $item) {
            continue
        }
        $full = Get-FullPath ([string]$item)
        $parent = Split-Path -Parent $full
        if (-not $parent -or -not $PluginsDir.Equals($parent, [System.StringComparison]::OrdinalIgnoreCase)) {
            Write-Log "Manifest refused: '$item' is not a direct subfolder of $PluginsDir"
            return $null
        }
        $removals += $full
    }
    return [pscustomobject]@{ Install = $installs; Remove = $removals }
}

# the plugin mode copy: each folder mirrored first (robocopy codes 0-7 are a success, 8 and more stop it before any
# removal: a failed update never becomes an uninstall), then the removals, except a folder just mirrored onto. True
# when everything was done.
function Copy-Plugins {
    $manifest = Read-Manifest
    if (-not $manifest) {
        return $false
    }
    try {
        New-Item -ItemType Directory -Force -Path $PluginsDir -ErrorAction Stop | Out-Null
    } catch {
        Write-Log "The plugins folder cannot be created: $($_.Exception.Message)"
        return $false
    }
    $installed = @()
    $robocopy = Join-Path $env:SystemRoot 'System32\robocopy.exe'
    foreach ($item in $manifest.Install) {
        $dst = Join-Path $PluginsDir $item.Target
        $installed += $dst
        if ((Test-Path -LiteralPath $dst) -and (Test-ReparsePoint $dst)) {
            # never mirror through a link: the link goes, the folder is made afresh
            try {
                Remove-Tree $dst
            } catch {
                Write-Log "Removal of the link $dst failed: $($_.Exception.Message)"
                return $false
            }
        }
        & $robocopy $item.Source $dst /MIR /XJ /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
        $code = $LASTEXITCODE
        if ($code -ge 8) {
            Write-Log "robocopy of plugin $($item.Target) failed with code $code, the copy stops"
            return $false
        }
        Write-Log "robocopy of plugin $($item.Target) done (code $code)"
    }
    foreach ($folder in $manifest.Remove) {
        $replaced = @($installed | Where-Object { $_.Equals($folder, [System.StringComparison]::OrdinalIgnoreCase) })
        if ($replaced.Count -gt 0) {
            Write-Log "Kept $($folder), replaced by the new version"
            continue
        }
        if (Test-Path -LiteralPath $folder) {
            try {
                Remove-Tree $folder
            } catch {
                Write-Log "Removal of $folder failed: $($_.Exception.Message)"
                return $false
            }
            Write-Log "Removed $folder"
        }
    }
    return $true
}

function Test-Writable([string]$dir) {
    $probe = Join-Path $dir ('.update-probe-' + [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType File -Path $probe -ErrorAction Stop | Out-Null
        Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
        return $true
    } catch {
        return $false
    }
}

# true if the folder may be deleted: neither a drive root, nor the application folder, nor one of its parents
function Test-Deletable([string]$folder) {
    $full = Get-FullPath $folder
    $root = [System.IO.Path]::GetPathRoot($full).TrimEnd('\')
    if ($full -eq $root) {
        return $false
    }
    if ($AppDir.Equals($full, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    return -not $AppDir.StartsWith($full + '\', [System.StringComparison]::OrdinalIgnoreCase)
}

# cmd.exe of the system, by its full path
function Get-Cmd {
    if ($env:ComSpec) {
        return $env:ComSpec
    }
    return Join-Path $env:SystemRoot 'System32\cmd.exe'
}

function Remove-TempDir {
    $temp = $TempDir
    if (-not (Test-Deletable $temp)) {
        Write-Log "Refusing to delete $temp (drive root, the application folder or one of its parents)"
        return
    }
    # only the folder of this script (the temporary folder of the update) may be deleted
    $scriptDir = Get-FullPath (Split-Path -Parent $PSCommandPath)
    if (-not $scriptDir.Equals($temp, [System.StringComparison]::OrdinalIgnoreCase)) {
        Write-Log "Refusing to delete $temp (not the folder of this script, $scriptDir)"
        return
    }
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $temp) {
        # the script runs from this folder: what is left is deleted a little later, at best
        try {
            $cmd = '/c timeout /t 2 /nobreak >nul & rmdir /s /q "{0}"' -f $temp
            Start-Process -FilePath (Get-Cmd) -ArgumentList $cmd -WindowStyle Hidden -ErrorAction Stop
            Write-Log "Temporary update folder $temp partly removed, the rest is left to a delayed removal"
        } catch {
            Write-Log "Cleanup of $temp failed: $($_.Exception.Message)"
        }
    } else {
        Write-Log "Temporary update folder $temp removed"
    }
}

if ($CopyOnly) {
    # the elevated run: the copy only, never the relaunch (it would run as administrator)
    Write-Log 'Elevated copy started'
    if ($pluginMode) {
        $copied = Copy-Plugins
    } else {
        $copied = Copy-AppImage
    }
    Write-Log 'Elevated copy finished'
    if ($copied) {
        exit 0
    }
    exit 1
}

if ($pluginMode) {
    Write-Log "Plugin update started (process $ProcessId, app $AppDir, manifest $PluginManifest)"
    if (-not (Read-Manifest)) {
        # refused before anything is touched
        Start-App
        Remove-TempDir
        Write-Log 'Plugin update finished (refused)'
        exit 1
    }
} else {
    Write-Log "Update started (process $ProcessId, app $AppDir, new $NewDir)"
}

# 1. wait for Copybot and every process started from its folder (60 s in all); a process named like Copybot whose
# path cannot be read (an elevated one) counts as running
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$prefix = $AppDir + '\'
$appName = [System.IO.Path]::GetFileNameWithoutExtension($Exe)
$running = @()
do {
    $running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.Id -eq $ProcessId -or
        ($_.Path -and $_.Path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) -or
        (-not $_.Path -and $_.ProcessName -eq $appName)
    })
    if ($running.Count -eq 0) {
        break
    }
    Start-Sleep -Milliseconds 500
} while ($watch.Elapsed.TotalSeconds -lt 60)
if ($running.Count -gt 0) {
    Write-Log "Copybot still running after 60 s ($(($running | ForEach-Object { $_.Id }) -join ', ')), nothing was changed"
    Start-App
    Remove-TempDir
    Write-Log 'Update finished (not applied)'
    exit 1
}
Write-Log 'Copybot has exited'

# 2. the copy, elevated when the application folder is not writable
# the folder to write: plugins, or the application folder while plugins does not exist yet
$writeDir = $AppDir
if ($pluginMode -and (Test-Path -LiteralPath $PluginsDir)) {
    $writeDir = $PluginsDir
}
if (Test-Writable $writeDir) {
    if ($pluginMode) {
        $done = Copy-Plugins
    } else {
        $done = Copy-AppImage
    }
    if (-not $done) {
        Write-Log 'The copy failed: Copybot is relaunched as it is'
    }
} else {
    Write-Log "$writeDir is not writable, the copy is run elevated"
    $quoted = { param($value) '"{0}"' -f $value }
    if ($pluginMode) {
        $argList = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File {0} -ProcessId {1} -AppDir {2} -PluginManifest {3} -Exe {4} -LogDir {5} -CopyOnly' -f `
            (& $quoted $PSCommandPath), $ProcessId, (& $quoted $AppDir), (& $quoted $PluginManifest), (& $quoted $Exe), (& $quoted $LogDir)
    } else {
        $argList = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File {0} -ProcessId {1} -AppDir {2} -NewDir {3} -Exe {4} -LogDir {5} -CopyOnly' -f `
            (& $quoted $PSCommandPath), $ProcessId, (& $quoted $AppDir), (& $quoted $NewDir), (& $quoted $Exe), (& $quoted $LogDir)
    }
    try {
        $elevated = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $argList -Verb RunAs `
            -WindowStyle Hidden -Wait -PassThru -ErrorAction Stop
        Write-Log "Elevated copy ended (code $($elevated.ExitCode))"
    } catch {
        # UAC declined or failed: nothing was copied, the old version is relaunched as it is
        Write-Log "Elevation declined or failed: $($_.Exception.Message)"
        Start-App
        Remove-TempDir
        Write-Log 'Update finished (not applied)'
        exit 1
    }
}

# 3. the relaunch (never elevated), then the cleanup
Start-App
Remove-TempDir
Write-Log 'Update finished'
exit 0
