[CmdletBinding()]
param([switch]$Delete)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\', '/')
$plan = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'cleanup_local_targets.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$backupRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot $plan.backupRelativePath))

function Get-ProjectPath([string]$RelativePath) {
    $absolute = [IO.Path]::GetFullPath((Join-Path $projectRoot $RelativePath))
    if (-not $absolute.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Cleanup target escapes this project: $RelativePath"
    }
    $cursor = $absolute
    while ($cursor -ne $projectRoot) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Cleanup does not follow links or junctions: $cursor"
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $absolute
}

function Get-LongPath([string]$AbsolutePath) {
    if ($AbsolutePath.StartsWith('\\?\')) { return $AbsolutePath }
    if ($AbsolutePath.StartsWith('\\')) { return '\\?\UNC\' + $AbsolutePath.Substring(2) }
    return '\\?\' + $AbsolutePath
}

function Assert-Hash([string]$Path, [string]$Expected) {
    $extended = Get-LongPath ([IO.Path]::GetFullPath($Path))
    if (-not [IO.File]::Exists($extended)) {
        throw "Required preserved file is missing: $Path"
    }
    $stream = [IO.File]::OpenRead($extended)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        $actual = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
        if ($actual -ne $Expected) {
            throw "File differs from the cleanup snapshot; review it before deleting: $Path"
        }
    } finally {
        $stream.Dispose()
        $hasher.Dispose()
    }
}

function Get-CleanupEntries([string]$DirectoryPath) {
    $extendedRoot = (Get-LongPath $DirectoryPath).TrimEnd('\')
    if (-not [IO.Directory]::Exists($extendedRoot)) { return }
    $pending = New-Object 'System.Collections.Generic.Stack[string]'
    $pending.Push($extendedRoot)
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        foreach ($entry in [IO.Directory]::EnumerateFileSystemEntries($directory)) {
            if (-not $entry.StartsWith($extendedRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
                throw "Cleanup entry escapes the checked directory: $entry"
            }
            $attributes = [IO.File]::GetAttributes($entry)
            if ($attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Cleanup does not follow links or junctions: $entry"
            }
            $isDirectory = [bool]($attributes -band [IO.FileAttributes]::Directory)
            [pscustomobject]@{
                Path = $entry
                RelativePath = $entry.Substring($extendedRoot.Length + 1).Replace('\', '/')
                IsDirectory = $isDirectory
            }
            if ($isDirectory) { $pending.Push($entry) }
        }
    }
}

function Get-OldWorktreeRecord([string]$OldPath) {
    $lines = @(& git -C $projectRoot worktree list --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Could not read registered Git worktrees.' }
    $records = ($lines -join [Environment]::NewLine) -split '(?:\r?\n){2,}'
    foreach ($record in $records) {
        if ($record -match '(?m)^worktree (.+)\r?$') {
            $path = [IO.Path]::GetFullPath($Matches[1].TrimEnd([char]13))
            if ([string]::Equals($path, $OldPath, [StringComparison]::OrdinalIgnoreCase)) {
                if ($record -notmatch '(?m)^HEAD ([0-9a-f]+)\r?$') {
                    throw 'Old worktree has no verifiable Git revision.'
                }
                return [pscustomobject]@{ Head = $Matches[1] }
            }
        }
    }
    return $null
}

function Remove-CleanupDirectory([string]$RelativePath, [object[]]$Entries) {
    # Delete only the enumerated, validated entries. New files make directory removal stop.
    $absolute = Get-ProjectPath $RelativePath
    $extendedRoot = Get-LongPath $absolute
    if (-not [IO.Directory]::Exists($extendedRoot)) { return }
    foreach ($entry in $Entries) {
        if (-not $entry.Path.StartsWith($extendedRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Deletion entry escapes the checked target: $($entry.Path)"
        }
    }
    try {
        foreach ($entry in $Entries | Where-Object { -not $_.IsDirectory }) {
            if ([IO.File]::Exists($entry.Path)) {
                $attributes = [IO.File]::GetAttributes($entry.Path)
                if ($attributes -band [IO.FileAttributes]::ReadOnly) {
                    [IO.File]::SetAttributes($entry.Path, $attributes -band (-bnot [IO.FileAttributes]::ReadOnly))
                }
                [IO.File]::Delete($entry.Path)
            }
        }
        foreach ($entry in $Entries | Where-Object IsDirectory | Sort-Object { $_.Path.Length } -Descending) {
            if ([IO.Directory]::Exists($entry.Path)) { [IO.Directory]::Delete($entry.Path, $false) }
        }
        [IO.Directory]::Delete($extendedRoot, $false)
    } catch {
        throw "Could not finish deleting $absolute. Close programs using this folder and rerun the same command. Details: $($_.Exception.Message)"
    }
}

# Dot-sourcing loads the bounded path helpers for isolated fixture verification.
if ($MyInvocation.InvocationName -eq '.') { return }

$logDirectory = Join-Path $projectRoot 'build/cleanup'
[IO.Directory]::CreateDirectory($logDirectory) | Out-Null
$logPath = Join-Path $logDirectory ('cleanup-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '.log')
$transcribing = $false
try {
    Start-Transcript -LiteralPath $logPath -Force | Out-Null
    $transcribing = $true

    if (@($plan.targets).Count -ne 2 -or
        @($plan.targets) -cnotcontains '.worktrees/beta2-improvements' -or
        @($plan.targets) -cnotcontains '_delete_after_review') {
        throw 'Unexpected cleanup target list.'
    }
    $oldWorktree = Get-ProjectPath '.worktrees/beta2-improvements'
    $deleteQueue = Get-ProjectPath '_delete_after_review'
    $worktreeParent = Get-ProjectPath '.worktrees'
    $versionText = Get-Content -LiteralPath (Join-Path $projectRoot 'android/app/build.gradle') -Raw -Encoding UTF8
    if ($versionText -notmatch ("versionName\s+'" + [regex]::Escape($plan.expectedVersion) + "'") -or
        $versionText -notmatch ("versionCode\s+" + $plan.expectedVersionCode + '(?:\s|$)')) {
        throw 'Current app version changed; review the cleanup plan first.'
    }
    foreach ($entry in $plan.backupFiles) {
        Assert-Hash (Join-Path $backupRoot $entry.path) $entry.sha256
    }

    $registered = Get-OldWorktreeRecord $oldWorktree
    if ($null -ne $registered -and $registered.Head -ne $plan.expectedWorktreeHead) {
        throw 'Old worktree changed its Git revision; review it first.'
    }
    if ($null -eq $registered -and [IO.File]::Exists((Get-LongPath (Join-Path $oldWorktree '.git')))) {
        throw 'Unregistered folder still has Git control data; review it first.'
    }

    $oldEntries = @(Get-CleanupEntries $oldWorktree)
    $queueEntries = @(Get-CleanupEntries $deleteQueue)
    if ([IO.Directory]::Exists((Get-LongPath $oldWorktree))) {
        $snapshot = Get-Content -LiteralPath (Join-Path $backupRoot 'beta2-local/manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        $sourceHashes = @{}
        foreach ($entry in $snapshot.files.PSObject.Properties) { $sourceHashes[$entry.Name] = $entry.Value }
        $retainedHashes = @{}
        foreach ($entry in $plan.retainedArtifacts) {
            $retainedHashes[$entry.source] = $entry.sha256
            if ($entry.destinationRoot -eq 'backup') {
                $destination = Join-Path $backupRoot $entry.destination
            } elseif ($entry.destinationRoot -eq 'project') {
                $destination = Get-ProjectPath $entry.destination
            } else {
                throw 'Unexpected preservation destination.'
            }
            Assert-Hash $destination $entry.sha256
        }
        # Only known generated trees may contain files outside the source/artifact snapshots.
        $generatedPrefixes = @(
            'android/app/build/', 'android/build/', 'android/.gradle/', 'android/.kotlin/',
            'android/app/.cxx/', 'android/ocr-contracts/build/', 'build/dependencies/',
            'build/native-cmake-staging/', 'build/local-improvements/isolated-avd/', 'tools/__pycache__/'
        )
        $verifiedSources = 0
        foreach ($entry in $oldEntries | Where-Object { -not $_.IsDirectory }) {
            $name = $entry.RelativePath
            if ($sourceHashes.ContainsKey($name)) {
                Assert-Hash $entry.Path $sourceHashes[$name]
                $verifiedSources++
            } elseif ($retainedHashes.ContainsKey($name)) {
                Assert-Hash $entry.Path $retainedHashes[$name]
            } elseif ($name -eq '.git' -and $null -ne $registered) {
                continue
            } else {
                $generated = $false
                foreach ($prefix in $generatedPrefixes) {
                    if ($name.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { $generated = $true; break }
                }
                if (-not $generated) { throw "Unrecorded file in old folder; preserve it before deleting: $name" }
            }
        }
        $alreadyRemoved = $sourceHashes.Count - $verifiedSources
        Write-Output "Verified $verifiedSources remaining backed-up source files; $alreadyRemoved already removed."
        if ($null -eq $registered) {
            Write-Output 'Old folder is already unregistered; continuing from the interrupted removal.'
        }
    }

    Write-Output 'Backup, remaining source, local configuration and retained artifact checks passed.'
    foreach ($target in @($oldWorktree, $deleteQueue)) {
        if ([IO.Directory]::Exists((Get-LongPath $target))) { Write-Output "DELETE: $target" }
    }
    Write-Output "KEEP backup: $backupRoot"
    Write-Output "LOG: $logPath"
    if (-not $Delete) {
        Write-Output 'Preview only. To delete these folders yourself, run this script with -Delete.'
        return
    }

    # This block is for the user to execute explicitly with -Delete.
    if ($null -ne $registered) {
        & git -c core.longpaths=true -C $projectRoot worktree remove --force -- $oldWorktree
        if ($LASTEXITCODE -ne 0) {
            if ($null -ne (Get-OldWorktreeRecord $oldWorktree)) {
                throw 'Git worktree removal stopped. Close programs using this folder and rerun the same command.'
            }
            Write-Output 'Git registration removed; finishing any remaining files with extended Windows paths.'
        }
    }
    Remove-CleanupDirectory '.worktrees/beta2-improvements' $oldEntries
    Remove-CleanupDirectory '_delete_after_review' $queueEntries
    if ((Test-Path -LiteralPath $worktreeParent -PathType Container) -and
        @(Get-ChildItem -LiteralPath $worktreeParent -Force).Count -eq 0) {
        Remove-Item -LiteralPath $worktreeParent
    }
    Write-Output 'Manual cleanup completed. Current app sources and external backups are retained.'
} catch {
    Write-Output "Cleanup stopped: $($_.Exception.Message)"
    Write-Output "LOG: $logPath"
    throw
} finally {
    if ($transcribing) { Stop-Transcript | Out-Null }
}
