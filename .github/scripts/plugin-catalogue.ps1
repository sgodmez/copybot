<#
.SYNOPSIS
    Writes the catalogue.json of a Copybot release and gathers the plugin archives it publishes.

.DESCRIPTION
    Every release carries the complete catalogue of the official plugins (copybot-plugin/*/catalog.json). The
    previous catalogue is the one of the latest PUBLISHED release (a draft is ignored; none, or one without a
    catalogue.json, gives an empty catalogue). A plugin whose pom version did not change keeps its previous entry
    and its archive stays on the release that hosts it. A new or changed version gets an entry for the current tag
    and its zip (built by `mvn package`) is copied into the output folder.

    Fails when a plugin keeps its version although its module changed since the tag of its entry (version not
    raised), and when a kept entry was built for a plugin API that this one is not compatible with any more.

.PARAMETER Tag
    Tag of the release being made, e.g. v1.2.0.

.PARAMETER ApiVersion
    Version of the plugin API, X.Y: the property copybot.plugin.api.version of the root pom, which is the module
    version of com.copybot.engine (not the application version). It is the `engine` of the new entries.

.PARAMETER OutDir
    Folder that receives catalogue.json and the new plugin zips.

.PARAMETER PreviousCatalogue
    Offline mode: a catalogue.json file used as the previous one instead of downloading it with gh.

.PARAMETER SkipGit
    Offline mode: no git comparison of a kept plugin with the tag of its entry.

.PARAMETER Offline
    Passes -o to Maven when it reads the plugin versions.
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$ApiVersion,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$PreviousCatalogue,
    [switch]$SkipGit,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'

if ($ApiVersion -notmatch '^(\d+)\.(\d+)$') { throw "The plugin API version ($ApiVersion) is not X.Y" }
$apiMajor = [int]$Matches[1]
$apiMinor = [int]$Matches[2]
$apiLine = "$apiMajor.$apiMinor"

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# runs gh with its standard output and its error output apart: only the standard output is JSON. Windows PowerShell
# turns each line of the error output into an error record, which must not stop this script ('Stop' above).
function Invoke-Gh([string[]]$GhArgs) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $all = @(& gh @GhArgs 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    $errorRecords = @($all | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] })
    $outputLines = @($all | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] })
    return [pscustomobject]@{
        Code = $code
        Out  = ($outputLines | Out-String)
        Err  = (($errorRecords | ForEach-Object { $_.ToString() }) -join "`n")
    }
}

# --- the previous catalogue: the latest published release, never a draft ---
function Get-PreviousEntries {
    if ($PreviousCatalogue) {
        $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $PreviousCatalogue
    } else {
        # `gh release view` without a tag is the latest release, drafts and pre-releases excluded
        $view = Invoke-Gh @('release', 'view', '--json', 'tagName')
        if ($view.Code -ne 0) {
            # a repository without a published release: "release not found", or the 404 of the API
            if ($view.Err -match 'release not found' -or $view.Err -match 'HTTP 404') {
                Write-Host 'No published release yet: the catalogue starts empty.'
                return @()
            }
            throw "gh release view failed: $($view.Err)"
        }
        $latest = ($view.Out | ConvertFrom-Json).tagName
        $assetsView = Invoke-Gh @('release', 'view', $latest, '--json', 'assets')
        if ($assetsView.Code -ne 0) { throw "gh release view $latest failed: $($assetsView.Err)" }
        $assets = ($assetsView.Out | ConvertFrom-Json).assets
        if (-not ($assets | Where-Object { $_.name -eq 'catalogue.json' })) {
            Write-Host "The published release $latest has no catalogue.json: the catalogue starts empty."
            return @()
        }
        $tmp = Join-Path ([IO.Path]::GetTempPath()) ("catalogue-" + [guid]::NewGuid())
        New-Item -ItemType Directory -Path $tmp | Out-Null
        # its output is not part of what this function returns
        $download = Invoke-Gh @('release', 'download', $latest, '--pattern', 'catalogue.json', '--dir', $tmp)
        if ($download.Code -ne 0) { throw "Could not download catalogue.json of ${latest}: $($download.Err)" }
        $text = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $tmp 'catalogue.json')
        Remove-Item -Recurse -Force $tmp
    }
    $catalogue = $text | ConvertFrom-Json
    if ($catalogue.format -ne 1) { throw "The previous catalogue has format $($catalogue.format), expected 1" }
    return @($catalogue.plugins)
}

# the pom of a module with its parent version blanked: the parent follows the application version, which
# changes at every release without the plugin changing
function Get-PomWithoutParentVersion([string]$text) {
    # the first match only: the static [regex]::Replace has no count (its 4th argument would be the options)
    return [regex]::new('(?s)(<parent>.*?<version>).*?(</version>)').Replace($text, '$1$2', 1)
}

# true when the module changed since the tag (git diff --quiet: 0 same, 1 changed, else an error)
function Test-ModuleChanged([string]$tag, [string]$moduleDir) {
    $rel = "copybot-plugin/$moduleDir"
    & git -C $root diff --quiet $tag HEAD -- $rel ":(exclude)$rel/pom.xml"
    if ($LASTEXITCODE -eq 1) { return $true }
    if ($LASTEXITCODE -ne 0) { throw "git diff against $tag failed (is the tag fetched?)" }
    $old = (& git -C $root show "${tag}:$rel/pom.xml" | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "git show ${tag}:$rel/pom.xml failed" }
    $new = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root "$rel/pom.xml")
    # line endings and the final newline do not count
    $oldText = (Get-PomWithoutParentVersion $old).Replace("`r`n", "`n").Trim()
    $newText = (Get-PomWithoutParentVersion $new).Replace("`r`n", "`n").Trim()
    return $oldText -ne $newText
}

$previous = Get-PreviousEntries
$entries = @()
$newZips = @()

foreach ($catalogFile in (Get-ChildItem -Path (Join-Path $root 'copybot-plugin') -Filter catalog.json -Recurse -Depth 1 | Sort-Object FullName)) {
    $moduleDir = $catalogFile.Directory.Name
    $moduleRoot = $catalogFile.Directory.FullName
    $source = Get-Content -Raw -Encoding UTF8 -LiteralPath $catalogFile.FullName | ConvertFrom-Json
    $code = $source.code

    $mvnArgs = @('-B', '-q')
    if ($Offline) { $mvnArgs += '-o' }
    $mvnArgs += @('help:evaluate', '-Dexpression=project.version', '-DforceStdout')
    Push-Location $moduleRoot
    try { $version = (& mvn @mvnArgs | Out-String).Trim() } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "Could not read the version of $moduleDir" }
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "The version of $moduleDir ($version) is not X.Y.Z" }

    $old = $previous | Where-Object { $_.code -eq $code } | Select-Object -First 1
    if ($old -and $old.version -eq $version) {
        # kept: same version, so the module must be the one of the tag of its entry
        if (-not $SkipGit -and (Test-ModuleChanged $old.tag $moduleDir)) {
            throw "Plugin $code is still at version $version but copybot-plugin/$moduleDir changed since $($old.tag): raise its version in its pom.xml"
        }
        if ($old.engine -notmatch '^(\d+)\.(\d+)$') { throw "The kept entry of $code has a bad engine ($($old.engine))" }
        if ([int]$Matches[1] -ne $apiMajor -or [int]$Matches[2] -gt $apiMinor) {
            throw "Plugin $code $version was built for the plugin API $($old.engine), not compatible with the plugin API ${ApiVersion}: give it a new version"
        }
        Write-Host "Kept: $code $version ($($old.tag))"
        $entries += $old
        continue
    }

    $asset = "copybot-plugin-$code-$version.zip"
    $zip = Join-Path $moduleRoot "target/$asset"
    if (-not (Test-Path -LiteralPath $zip)) { throw "Archive $zip not found: run mvn package first" }
    Copy-Item -LiteralPath $zip -Destination (Join-Path $OutDir $asset) -Force
    $newZips += $asset

    $entry = [ordered]@{
        module      = $source.module
        code        = $code
        version     = $version
        engine      = $apiLine
        tag         = $Tag
        asset       = $asset
        description = $source.description
    }
    if ($source.license) { $entry.license = $source.license }
    $reason = if ($old) { "was $($old.version)" } else { 'new' }
    Write-Host "Published: $code $version on $Tag ($reason)"
    $entries += [pscustomobject]$entry
}

$catalogue = [ordered]@{ format = 1; plugins = @($entries) }
$json = $catalogue | ConvertTo-Json -Depth 10
[IO.File]::WriteAllText((Join-Path (Resolve-Path $OutDir).Path 'catalogue.json'), $json + "`n", (New-Object Text.UTF8Encoding($false)))
Write-Host "catalogue.json written with $($entries.Count) plugin(s), $($newZips.Count) new archive(s)."
# the exit code of the last native command (git, mvn) must not become the one of the script
exit 0
