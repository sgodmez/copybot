param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][string]$AppDir,
    [string]$NewDir,
    [Parameter(Mandatory = $true)][string]$Exe,
    [Parameter(Mandatory = $true)][string]$LogDir,
    [string]$ConfigFile,
    [string]$PluginManifest,
    [string]$Installer,
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
# -NewDir or -Installer with -PluginManifest: the application and its plugins in one run (spec update-with-plugins):
# the application first (the portable image, or the installer run silently by this script, without its own relaunch),
# then, only if it succeeded, the plugins of the manifest, then one relaunch. The installer is a file of the folder of
# this script; NewDir, the installer and the manifest must all be in that same folder.
# -CopyOnly: the copy alone, run elevated when the application folder is not writable; the relaunch and the cleanup
# stay with the script that is not elevated. It never runs the installer, which asks for its own elevation.

$ErrorActionPreference = 'Continue'

# absolute paths without a final separator (a final \ would break the quotes given to robocopy)
function Get-FullPath([string]$path) {
    return [System.IO.Path]::GetFullPath($path).TrimEnd('\')
}

$AppDir = Get-FullPath $AppDir
$withPlugins = [bool]$PluginManifest
# the plugins alone, without an application update
$pluginMode = $withPlugins -and -not $NewDir -and -not $Installer
if ($NewDir -and $Installer) {
    exit 2
}
# the temporary folder of the update: the folder of every path given (the copy, the installer, the manifest and the
# sources of the plugins)
$given = @()
if ($NewDir) {
    $NewDir = Get-FullPath $NewDir
    $given += $NewDir
}
if ($Installer) {
    $Installer = Get-FullPath $Installer
    $given += $Installer
}
if ($withPlugins) {
    $PluginManifest = Get-FullPath $PluginManifest
    $given += $PluginManifest
}
if ($given.Count -eq 0) {
    exit 2
}
$TempDir = Get-FullPath (Split-Path -Parent $given[0])
$sameFolder = @($given | Where-Object {
    -not $TempDir.Equals((Get-FullPath (Split-Path -Parent $_)), [System.StringComparison]::OrdinalIgnoreCase)
}).Count -eq 0
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

# the installer, checked before anything is touched: a plain file of the folder of this script
function Test-Installer {
    $scriptDir = Get-FullPath (Split-Path -Parent $PSCommandPath)
    $parent = Get-FullPath (Split-Path -Parent $Installer)
    if (-not $scriptDir.Equals($parent, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $Installer -PathType Leaf) -or (Test-ReparsePoint $Installer)) {
        Write-Log "Installer refused: $Installer is not a file of the folder of this script"
        return $false
    }
    return $true
}

# runs the installer silently and waits for it (it asks for its own elevation when the installation is for all
# users); its own relaunch is left out, this script relaunches. True when it ended with code 0.
function Invoke-Installer {
    try {
        $setup = Start-Process -FilePath $Installer -ArgumentList '/SILENT', '/SUPPRESSMSGBOXES', '/NORESTART' `
            -Wait -PassThru -ErrorAction Stop
        Write-Log "Installer ended (code $($setup.ExitCode))"
        return $setup.ExitCode -eq 0
    } catch {
        Write-Log "Installer failed to start: $($_.Exception.Message)"
        return $false
    }
}

# the folder the plugin copy writes: plugins, or the application folder while plugins does not exist yet
function Get-PluginWriteDir {
    if (Test-Path -LiteralPath $PluginsDir) {
        return $PluginsDir
    }
    return $AppDir
}

# the application image then, if it was copied, the plugins of the manifest
function Copy-AppAndPlugins {
    $copied = Copy-AppImage
    if ($copied -and $withPlugins) {
        $copied = Copy-Plugins
    }
    return $copied
}

# runs a copy, elevated unless every folder of $writeDirs is writable (this script again with -CopyOnly and
# $modeArgs). True when everything was copied; false on a failure or when the elevation is declined (nothing was
# copied then).
function Invoke-Copy([string[]]$writeDirs, [string]$modeArgs, [scriptblock]$copy) {
    $locked = @($writeDirs | Where-Object { -not (Test-Writable $_) })
    if ($locked.Count -eq 0) {
        $done = & $copy
        if (-not $done) {
            Write-Log 'The copy failed'
        }
        return $done
    }
    Write-Log "$($locked -join ', ') not writable, the copy is run elevated"
    $argList = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}" -ProcessId {1} -AppDir "{2}" {3} -Exe "{4}" -LogDir "{5}" -CopyOnly' -f `
        $PSCommandPath, $ProcessId, $AppDir, $modeArgs, $Exe, $LogDir
    try {
        $elevated = Start-Process -FilePath (Join-Path $PSHOME 'powershell.exe') -ArgumentList $argList -Verb RunAs `
            -WindowStyle Hidden -Wait -PassThru -ErrorAction Stop
        Write-Log "Elevated copy ended (code $($elevated.ExitCode))"
        return $elevated.ExitCode -eq 0
    } catch {
        # UAC declined or failed: nothing was copied
        Write-Log "Elevation declined or failed: $($_.Exception.Message)"
        return $false
    }
}

if ($CopyOnly) {
    # the elevated run: the copy only, never the installer nor the relaunch (they would run as administrator)
    Write-Log 'Elevated copy started'
    if ($NewDir) {
        $copied = Copy-AppAndPlugins
    } elseif ($pluginMode) {
        $copied = Copy-Plugins
    } else {
        $copied = $false
    }
    Write-Log 'Elevated copy finished'
    if ($copied) {
        exit 0
    }
    exit 1
}

if ($Installer) {
    Write-Log "Update started (process $ProcessId, app $AppDir, installer $Installer, manifest $PluginManifest)"
} elseif ($NewDir) {
    Write-Log "Update started (process $ProcessId, app $AppDir, new $NewDir, manifest $PluginManifest)"
} else {
    Write-Log "Plugin update started (process $ProcessId, app $AppDir, manifest $PluginManifest)"
}
# refused before anything is touched: paths outside one folder, the manifest, the installer
$refused = $false
if (-not $sameFolder) {
    Write-Log 'Update refused: the copy, the installer and the manifest are not in one folder'
    $refused = $true
} elseif ($withPlugins -and -not (Read-Manifest)) {
    $refused = $true
} elseif ($Installer -and -not (Test-Installer)) {
    $refused = $true
}
if ($refused) {
    Start-App
    if ($sameFolder) {
        Remove-TempDir
    }
    Write-Log 'Update finished (refused)'
    exit 1
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

# 2. the changes: the application first, the plugins only once it is updated
$pluginArgs = '-PluginManifest "{0}"' -f $PluginManifest
if ($Installer) {
    $applied = Invoke-Installer
    if ($applied -and $withPlugins) {
        $applied = Invoke-Copy (Get-PluginWriteDir) $pluginArgs { Copy-Plugins }
    }
} elseif ($NewDir) {
    $modeArgs = '-NewDir "{0}"' -f $NewDir
    $writeDirs = @($AppDir)
    if ($withPlugins) {
        $modeArgs += ' ' + $pluginArgs
        # the plugins folder may be locked although the application folder is not: then both are copied elevated
        $writeDirs += Get-PluginWriteDir
    }
    $applied = Invoke-Copy $writeDirs $modeArgs { Copy-AppAndPlugins }
} else {
    $applied = Invoke-Copy (Get-PluginWriteDir) $pluginArgs { Copy-Plugins }
}

# 3. the relaunch (never elevated), then the cleanup
Start-App
Remove-TempDir
if ($applied) {
    Write-Log 'Update finished'
    exit 0
}
Write-Log 'Update finished (not applied)'
exit 1
