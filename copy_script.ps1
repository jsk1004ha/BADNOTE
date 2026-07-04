$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Split-Path -Parent $MyInvocation.MyCommand.Path)
$webDir = Join-Path $repoRoot "web"
$assetsDir = Join-Path $repoRoot "android\app\src\main\assets\public"

if (-not (Test-Path -LiteralPath $webDir)) {
    throw "web directory not found: $webDir"
}

New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null

$resolvedRoot = (Resolve-Path -LiteralPath $repoRoot).Path
$resolvedAssets = (Resolve-Path -LiteralPath $assetsDir).Path
if (-not $resolvedAssets.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "refusing to sync outside repository: $resolvedAssets"
}

Get-ChildItem -LiteralPath $assetsDir -Force | Remove-Item -Recurse -Force
Copy-Item -Path (Join-Path $webDir "*") -Destination $assetsDir -Recurse -Force

Write-Host "Synced web assets to $assetsDir"
