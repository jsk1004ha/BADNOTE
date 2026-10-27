$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'cleanup_local.ps1')

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$identifier = [Guid]::NewGuid().ToString('N')
$fixtureRelative = 'build/cleanup-tests/long-' + $identifier
$fixture = Get-ProjectPath $fixtureRelative
$deep = $fixture
foreach ($index in 1..4) { $deep = Join-Path $deep (('segment' + $index + '-') + ('x' * 55)) }
$longFile = Join-Path $deep 'paper [1] $draft.bin'
$outsideRelative = 'build/cleanup-tests/control-' + $identifier + '.txt'
$outside = Get-ProjectPath $outsideRelative
$text = 'Isolated cleanup test fixture.'
[IO.Directory]::CreateDirectory((Get-LongPath $deep)) | Out-Null
[IO.File]::WriteAllText((Get-LongPath $longFile), $text)
[IO.File]::WriteAllText((Get-LongPath $outside), $text)
[IO.File]::SetAttributes((Get-LongPath $longFile), [IO.FileAttributes]::ReadOnly)
Assert-True ($longFile.Length -gt 300) 'Fixture must exceed the legacy Windows path limit.'
$stream = [IO.File]::OpenRead((Get-LongPath $longFile))
$hasher = [Security.Cryptography.SHA256]::Create()
try {
    $expected = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
} finally {
    $stream.Dispose()
    $hasher.Dispose()
}
Assert-Hash $longFile $expected
$stopped = $false
try { Assert-Hash $longFile ('0' * 64) } catch { $stopped = $true }
Assert-True $stopped 'A changed file hash was accepted.'
$entries = @(Get-CleanupEntries $fixture)
Assert-True (@($entries | Where-Object { -not $_.IsDirectory }).Count -eq 1) 'Long file must be enumerated exactly once.'
Remove-CleanupDirectory $fixtureRelative $entries
Assert-True (-not [IO.Directory]::Exists((Get-LongPath $fixture))) 'Long, read-only fixture was not removed.'
Assert-True ([IO.File]::Exists((Get-LongPath $outside))) 'Cleanup touched a file outside its target.'
Write-Output 'PASS: long paths, literal filenames, hash mismatch rejection, read-only files, and bounded deletion.'

$raceRelative = 'build/cleanup-tests/new-file-' + $identifier
$race = Get-ProjectPath $raceRelative
[IO.Directory]::CreateDirectory($race) | Out-Null
$known = Join-Path $race 'known.txt'
$added = Join-Path $race 'added-after-enumeration.txt'
[IO.File]::WriteAllText($known, $text)
$before = @(Get-CleanupEntries $race)
[IO.File]::WriteAllText($added, $text)
$stopped = $false
try { Remove-CleanupDirectory $raceRelative $before } catch { $stopped = $true }
Assert-True $stopped 'A new file must prevent deleting the target directory.'
Assert-True ([IO.File]::Exists($added)) 'A file added after validation was deleted.'
Remove-CleanupDirectory $raceRelative @(Get-CleanupEntries $race)
Write-Output 'PASS: new files survive an interrupted attempt; a checked retry completes.'

$stopped = $false
try { Get-ProjectPath '../outside-this-project' | Out-Null } catch { $stopped = $true }
Assert-True $stopped 'An escaping target was accepted.'
$currentRecord = Get-OldWorktreeRecord $projectRoot
Assert-True ($null -ne $currentRecord) 'Registered worktree metadata was not parsed.'
Write-Output 'PASS: escaping targets rejected and registered worktree metadata parsed.'

# Only this test-created, checked control file is removed here.
Assert-True ($outside -eq (Get-ProjectPath $outsideRelative)) 'Control path changed unexpectedly.'
[IO.File]::Delete((Get-LongPath $outside))
Write-Output ('Cleanup helper tests passed on PowerShell ' + $PSVersionTable.PSVersion.ToString())
