<#
.SYNOPSIS
Creates the two PixelPass App Services and wires their publish profiles into GitHub.

.DESCRIPTION
Implements phases 1 to 3 of DEPLOYMENT.md in one idempotent pass:

  1. resource group, two Linux App Service plans and two Web Apps;
  2. startup commands, HTTPS-only, and the application settings the code actually reads;
  3. publish profiles uploaded as GitHub secrets plus the two repository variables.

Re-running is safe: existing plans and Web Apps are updated instead of recreated. Nothing is
written inside the repository, and the downloaded publish profiles are shredded after upload.

The settings intentionally match the source, which is easy to get wrong by hand:
  - the API reads MONGO_URI, not MONGODB_URI (only scripts/seedUsers.js falls back to the latter);
  - ML_SERVICE_URL must be the base URL, because routes/scan.js appends /predict itself;
  - the ML App Service must run PYTHON|3.13, the interpreter the pinned wheels were verified on.

.PARAMETER MongoUri
MongoDB Atlas connection string. Required unless -DryRun is used.

.PARAMETER JwtSecret
Signing secret for admin sessions and visitor passes. Generated with a CSPRNG when omitted.

.EXAMPLE
az login
.\scripts\provision-azure.ps1 -DryRun
.\scripts\provision-azure.ps1 -MongoUri 'mongodb+srv://user:pass@cluster0.abc.mongodb.net/pixelpass'
#>

[CmdletBinding()]
param(
    [string]$ResourceGroup = 'pixelpass-rg',
    [string]$Location = 'westeurope',
    [string]$ApiAppName,
    [string]$MlAppName,
    [string]$MongoUri,
    [string]$JwtSecret,
    [string]$ApiSku = 'B1',
    [string]$MlSku = 'B2',
    [string]$ApiRuntime = 'NODE|22-lts',
    [string]$MlRuntime = 'PYTHON|3.13',
    [switch]$SkipGitHub,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$apiPlanName = 'pixelpass-api-plan'
$mlPlanName = 'pixelpass-ml-plan'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Note([string]$Message) {
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Invoke-Az {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $rendered = 'az ' + ($Arguments -join ' ')
    if ($DryRun) {
        Write-Note "[dry-run] $rendered"
        return
    }
    & az @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Azure CLI failed: $rendered"
    }
}

function Invoke-Gh {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    $rendered = 'gh ' + ($Arguments -join ' ')
    if ($DryRun) {
        Write-Note "[dry-run] $rendered"
        return
    }
    & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub CLI failed: $rendered"
    }
}

# Runs a native command that is allowed to fail (an existence check) without tripping
# $ErrorActionPreference='Stop', because PowerShell 5.1 turns redirected stderr into errors.
function Invoke-Probe {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$Command = 'az'
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $output = & $Command @Arguments 2>$null
        $ok = ($LASTEXITCODE -eq 0)
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return [pscustomobject]@{ Ok = $ok; Output = (($output | Out-String).Trim()) }
}

function Get-PlanSku([string]$PlanName) {
    if ($DryRun) { return $null }
    $probe = Invoke-Probe -Arguments @('appservice', 'plan', 'show', '--name', $PlanName,
        '--resource-group', $ResourceGroup, '--query', 'sku.name', '--output', 'tsv')
    if (-not $probe.Ok) { return $null }
    return $probe.Output
}

function Test-WebApp([string]$Name) {
    if ($DryRun) { return $false }
    $probe = Invoke-Probe -Arguments @('webapp', 'show', '--name', $Name,
        '--resource-group', $ResourceGroup, '--query', 'name', '--output', 'tsv')
    return $probe.Ok
}


function New-Secret([int]$ByteCount) {
    $bytes = New-Object 'byte[]' $ByteCount
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

# Overwrites the bytes before deleting, because a publish profile is a credential.
function Remove-CredentialFile([string]$Path) {
    if (-not (Test-Path $Path)) { return }
    $length = (Get-Item -LiteralPath $Path).Length
    if ($length -gt 0) {
        $noise = New-Object 'byte[]' $length
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($noise)
        [System.IO.File]::WriteAllBytes($Path, $noise)
    }
    Remove-Item -LiteralPath $Path -Force
}

# ---------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------
Write-Step 'Preflight'

if ($DryRun) {
    Write-Note 'Dry run: nothing is created, uploaded, or printed in clear text.'
}
else {
    if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
        throw ('Azure CLI not found. Install it with: winget install --exact --id Microsoft.AzureCLI' +
            ', open a new terminal, then run az login.')
    }
    $account = Invoke-Probe -Arguments @('account', 'show', '--query', 'name', '--output', 'tsv')
    if (-not $account.Ok) {
        throw 'Not signed in to Azure. Run az login in this terminal first.'
    }
    Write-Note "Subscription: $($account.Output)"
}

if (-not $SkipGitHub) {
    if (-not $DryRun) {
        if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
            throw 'GitHub CLI not found. Install it with: winget install --exact --id GitHub.cli'
        }
        if (-not (Invoke-Probe -Command 'gh' -Arguments @('auth', 'status')).Ok) {
            throw 'GitHub CLI is not authenticated. Run gh auth login.'
        }
        Push-Location $repoRoot
    }
    Write-Note 'Publish profiles go to repository secrets, app names to repository variables.'
}

if (-not $ApiAppName -or -not $MlAppName) {
    # App Service names are globally unique, so both apps share one short random suffix.
    $suffix = if ($DryRun) {
        'demo123'
    }
    else {
        -join (1..6 | ForEach-Object { '{0:x}' -f (Get-Random -Minimum 0 -Maximum 16) })
    }
    if (-not $ApiAppName) { $ApiAppName = "pixelpass-api-$suffix" }
    if (-not $MlAppName) { $MlAppName = "pixelpass-ml-$suffix" }
}

$apiHost = "$ApiAppName.azurewebsites.net"
$mlHost = "$MlAppName.azurewebsites.net"

if (-not $MongoUri) {
    if (-not $DryRun) {
        throw 'Pass -MongoUri ''mongodb+srv://...'' so the API can reach Atlas.'
    }
    $MongoUri = 'mongodb+srv://user:password@cluster0.example.mongodb.net/pixelpass'
}
if ($MongoUri -notmatch '^mongodb(\+srv)?://') {
    throw 'MongoUri must start with mongodb:// or mongodb+srv://.'
}
if (-not $JwtSecret) { $JwtSecret = New-Secret 48 }

Write-Note "Resource group : $ResourceGroup ($Location)"
Write-Note "API app        : $ApiAppName ($ApiRuntime, plan $apiPlanName/$ApiSku)"
Write-Note "ML app         : $MlAppName ($MlRuntime, plan $mlPlanName/$MlSku)"

# ---------------------------------------------------------------------------------------------
# 1. Resource group and plans
# ---------------------------------------------------------------------------------------------
Write-Step "Creating resource group $ResourceGroup"
Invoke-Az @('group', 'create', '--name', $ResourceGroup, '--location', $Location, '--output', 'none')

# Two plans, because a single B1 (1.75 GB) cannot hold Express and TensorFlow at the same time.
$plans = @(
    @{ Name = $apiPlanName; Sku = $ApiSku; Purpose = 'API and console' },
    @{ Name = $mlPlanName; Sku = $MlSku; Purpose = 'FastAPI, TensorFlow, DeepFace' }
)
foreach ($plan in $plans) {
    Write-Step "Plan $($plan.Name) ($($plan.Sku)) for $($plan.Purpose)"
    $currentSku = Get-PlanSku $plan.Name
    if ($currentSku) {
        Write-Note "Plan already exists with SKU $currentSku."
        if ($currentSku -ne $plan.Sku) {
            Invoke-Az @('appservice', 'plan', 'update', '--name', $plan.Name,
                '--resource-group', $ResourceGroup, '--sku', $plan.Sku, '--output', 'none')
        }
    }
    else {
        Invoke-Az @('appservice', 'plan', 'create', '--name', $plan.Name,
            '--resource-group', $ResourceGroup, '--sku', $plan.Sku, '--is-linux', '--output', 'none')
    }
}

# ---------------------------------------------------------------------------------------------
# 2. Web Apps, startup commands and application settings
# ---------------------------------------------------------------------------------------------
Write-Step "Web App $ApiAppName"
if (Test-WebApp $ApiAppName) {
    Write-Note 'Web App already exists; aligning runtime and startup command.'
}
else {
    Invoke-Az @('webapp', 'create', '--name', $ApiAppName, '--resource-group', $ResourceGroup,
        '--plan', $apiPlanName, '--runtime', $ApiRuntime, '--output', 'none')
}
# Oryx builds the package after the zip lands, then this command starts Express.
Invoke-Az @('webapp', 'config', 'set', '--name', $ApiAppName, '--resource-group', $ResourceGroup,
    '--linux-fx-version', $ApiRuntime, '--startup-file', 'npm start', '--output', 'none')

Write-Step "Web App $MlAppName"
if (Test-WebApp $MlAppName) {
    Write-Note 'Web App already exists; aligning runtime and startup command.'
}
else {
    # The name must be globally unique; if this fails, re-run with -MlAppName <other-name>.
    Invoke-Az @('webapp', 'create', '--name', $MlAppName, '--resource-group', $ResourceGroup,
        '--plan', $mlPlanName, '--runtime', $MlRuntime, '--output', 'none')
}
# A publish profile cannot set a startup command, so it is configured once here.
Invoke-Az @('webapp', 'config', 'set', '--name', $MlAppName, '--resource-group', $ResourceGroup,
    '--linux-fx-version', $MlRuntime, '--startup-file', 'bash /home/site/wwwroot/startup.sh',
    '--output', 'none')

foreach ($app in @($ApiAppName, $MlAppName)) {
    Invoke-Az @('webapp', 'update', '--name', $app, '--resource-group', $ResourceGroup,
        '--https-only', 'true', '--output', 'none')
    Invoke-Az @('webapp', 'config', 'set', '--name', $app, '--resource-group', $ResourceGroup,
        '--ftps-state', 'FtpsOnly', '--output', 'none')
}

Write-Step "Application settings for $ApiAppName"
$apiSettings = @(
    'NODE_ENV=production'
    "MONGO_URI=$MongoUri"
    "JWT_SECRET=$JwtSecret"
    'JWT_EXPIRES_IN=15m'
    "ML_SERVICE_URL=https://$mlHost"
    "INVITE_BASE_URL=https://$apiHost/invite"
    'SCM_DO_BUILD_DURING_DEPLOYMENT=true'
)
# The JWT secret and the Atlas URI never reach the console or a log file.
$apiSettingsShown = $apiSettings | ForEach-Object { $_ -replace '^(MONGO_URI|JWT_SECRET)=.*', '$1=***' }
if ($DryRun) {
    $apiSettingsShown | ForEach-Object { Write-Note "  $_" }
}
Invoke-Az (@('webapp', 'config', 'appsettings', 'set', '--name', $ApiAppName, '--resource-group', $ResourceGroup,
        '--output', 'none', '--settings') + $(if ($DryRun) { $apiSettingsShown } else { $apiSettings }))

Write-Step "Application settings for $MlAppName"
# WEBSITES_PORT tells the platform proxy which port startup.sh binds; 8000 is uvicorn's default.
$mlSettings = @(
    'SCM_DO_BUILD_DURING_DEPLOYMENT=true'
    'WEBSITES_PORT=8000'
    'PYTHONUNBUFFERED=1'
)
$mlSettings | ForEach-Object { Write-Note "  $_" }
Invoke-Az (@('webapp', 'config', 'appsettings', 'set', '--name', $MlAppName, '--resource-group', $ResourceGroup,
        '--output', 'none', '--settings') + $mlSettings)

Write-Step 'Restarting both Web Apps so the new settings apply'
foreach ($app in @($ApiAppName, $MlAppName)) {
    Invoke-Az @('webapp', 'restart', '--name', $app, '--resource-group', $ResourceGroup, '--output', 'none')
}

# ---------------------------------------------------------------------------------------------
# 3. Publish profiles, GitHub secrets and variables
# ---------------------------------------------------------------------------------------------
if (-not $SkipGitHub) {
    Write-Step 'Wiring the publish profiles into GitHub'
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ('pixelpass-profiles-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $targets = @(
        [pscustomobject]@{ App = $ApiAppName; Name = 'api'; Secret = 'AZURE_BACKEND_PUBLISH_PROFILE'; Variable = 'AZURE_BACKEND_APP_NAME' }
        [pscustomobject]@{ App = $MlAppName; Name = 'ml'; Secret = 'AZURE_ML_PUBLISH_PROFILE'; Variable = 'AZURE_ML_APP_NAME' }
    )
    if (-not $DryRun) { New-Item -ItemType Directory -Path $tempDir -Force | Out-Null }
    try {
        foreach ($target in $targets) {
            $profilePath = Join-Path $tempDir "$($target.Name)-profile.xml"
            if ($DryRun) {
                Write-Note "[dry-run] az webapp deployment list-publishing-profiles --name $($target.App) --resource-group $ResourceGroup --xml > $profilePath"
                Write-Note "[dry-run] gh secret set $($target.Secret) --body <$($target.Name) publish profile XML>"
                Write-Note "[dry-run] gh variable set $($target.Variable) --body $($target.App)"
                continue
            }
            $xml = & az webapp deployment list-publishing-profiles --name $target.App --resource-group $ResourceGroup --xml
            if ($LASTEXITCODE -ne 0) {
                throw "Could not read the publish profile for $($target.App)."
            }
            $xmlText = ($xml | Out-String)
            if ($xmlText -notmatch '<publishProfile') {
                throw "Unexpected publish profile payload for $($target.App)."
            }
            [System.IO.File]::WriteAllText($profilePath, $xmlText, (New-Object System.Text.UTF8Encoding($false)))
            Invoke-Gh @('secret', 'set', $target.Secret, '--body', $xmlText)
            Invoke-Gh @('variable', 'set', $target.Variable, '--body', $target.App)
            Write-Note "$($target.Secret) and $($target.Variable) are set."
        }
    }
    finally {
        # A publish profile is a deployment credential, so it is shredded even when an upload failed.
        foreach ($target in $targets) { Remove-CredentialFile (Join-Path $tempDir "$($target.Name)-profile.xml") }
        if (Test-Path $tempDir) { Remove-Item -LiteralPath $tempDir -Force -Recurse }
        if (-not $DryRun) { Pop-Location }
    }
}

# ---------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------
Write-Step 'Summary'
Write-Note "Console and API : https://$apiHost"
Write-Note "ML service      : https://$mlHost/docs"
Write-Note 'MONGO_URI and JWT_SECRET live only in the Azure app settings; nothing was written to the repository.'
Write-Host ''
if ($SkipGitHub) {
    Write-Host 'GitHub wiring was skipped. Re-run without -SkipGitHub to upload the publish profiles.' -ForegroundColor Yellow
}
Write-Host 'Next steps:' -ForegroundColor Cyan
Write-Host '  1. Merge the release PR develop -> main; both deploy workflows then run.'
Write-Host "  2. Smoke test https://$apiHost/health, /login and https://$mlHost/docs."
Write-Host '  3. Scan one staff or visitor pass from the mobile app against the deployed API.'
Write-Host '  4. Read DEPLOYMENT.md sections 4 to 7 for the release and troubleshooting steps.'
