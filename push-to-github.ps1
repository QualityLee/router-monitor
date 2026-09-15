#Requires -Version 5.1
# Push android-router-monitor to GitHub (QualityLee/router-monitor)
# Usage: right-click -> Run with PowerShell,  OR  powershell -ExecutionPolicy Bypass -File push-to-github.ps1

$ErrorActionPreference = 'Continue'
Set-Location -Path $PSScriptRoot

$owner  = 'QualityLee'
$repo   = 'router-monitor'
$remote = "https://github.com/$owner/$repo.git"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " Push helper for $owner/$repo" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host ""

# --- Step 1: sanity checks -----------------------------------------------
Write-Host "[1/5] Checking local repository..." -ForegroundColor Yellow
if (-not (Test-Path '.git')) {
    Write-Host "  ERROR: .git not found. Run this script from the project folder." -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}

$log = git log --oneline -n 3 2>&1
Write-Host "  Local commits:"
$log | ForEach-Object { Write-Host "    $_" }

$branch = (git rev-parse --abbrev-ref HEAD 2>&1).Trim()
Write-Host "  Current branch: $branch"
Write-Host ""

# --- Step 2: remote ------------------------------------------------------
Write-Host "[2/5] Checking remote 'origin'..." -ForegroundColor Yellow
$existing = (git remote get-url origin 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $existing) {
    Write-Host "  Adding origin -> $remote"
    git remote add origin $remote
} elseif ($existing.Trim() -ne $remote) {
    Write-Host "  Updating origin -> $remote  (was $existing)"
    git remote set-url origin $remote
} else {
    Write-Host "  origin already correct"
}
Write-Host ""

# --- Step 3: token -------------------------------------------------------
Write-Host "[3/5] GitHub Personal Access Token" -ForegroundColor Yellow
Write-Host "  Create one at: https://github.com/settings/tokens/new"
Write-Host "  Scope needed: [x] repo    (classic token, ghp_...)"
Write-Host ""
$sec = Read-Host -Prompt "  Paste your PAT"
$token = $sec.Trim()

if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Host "  ERROR: empty token, aborting." -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}
if ($token -match '\s') {
    Write-Host "  WARNING: token contains whitespace - trimming done, but double check it." -ForegroundColor Yellow
}
Write-Host ""

# --- Step 4: verify token against the API --------------------------------
Write-Host "[4/5] Verifying token with GitHub API..." -ForegroundColor Yellow
$headers = @{
    Authorization = "token $token"
    'User-Agent'  = 'workbuddy-push'
    Accept        = 'application/vnd.github+json'
}
try {
    $me = Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers -Method Get -TimeoutSec 30
    Write-Host "  Authenticated as: $($me.login)" -ForegroundColor Green
} catch {
    Write-Host "  ERROR: token rejected by GitHub." -ForegroundColor Red
    Write-Host "  -> $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "  Common causes: token expired / wrong scope / copy missed characters." -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}
Write-Host ""

# --- Step 5: push --------------------------------------------------------
Write-Host "[5/5] Pushing to $remote ..." -ForegroundColor Yellow
$pushUrl = "https://${owner}:${token}@github.com/$owner/$repo.git"

# disable any credential helper so the token in the URL is used directly
git -c credential.helper= push --verbose $pushUrl "${branch}:${branch}" 2>&1 | ForEach-Object {
    Write-Host "  $_"
}

Write-Host ""
if ($LASTEXITCODE -eq 0) {
    Write-Host "==================================================" -ForegroundColor Green
    Write-Host " SUCCESS - code pushed." -ForegroundColor Green
    Write-Host "==================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host " Next: open https://github.com/$owner/$repo/actions"
    Write-Host "  - workflow 'Build Debug APK' should start automatically"
    Write-Host "  - if not, click it in the left sidebar -> 'Run workflow'"
    Write-Host "  - wait 3-5 min, download 'app-debug.zip' from Artifacts"
    Write-Host ""
    Write-Host " Reminder: delete this token when you are done:"
    Write-Host "   https://github.com/settings/tokens"
} else {
    Write-Host "==================================================" -ForegroundColor Red
    Write-Host " FAILED - exit code $LASTEXITCODE" -ForegroundColor Red
    Write-Host "==================================================" -ForegroundColor Red
    Write-Host " Copy EVERYTHING above and send it back for diagnosis."
}

Write-Host ""
Read-Host "Press Enter to exit"