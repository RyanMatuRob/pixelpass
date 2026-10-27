<#
.SYNOPSIS
Creates the Entra ID identity that the GitHub Actions deploy workflows sign in with (OIDC).

.DESCRIPTION
Publish-profile deployments authenticate over SCM basic authentication. When that policy is off, or
when a stored profile goes stale, azure/webapps-deploy stops at

    Publish profile is invalid for app-name and slot-name provided

and Kudu answers 401 Unauthorized. This script removes the long-lived credential instead of
rotating it: GitHub Actions presents a short-lived OIDC token, so no deployment password is ever
stored in the repository.

Everything is idempotent. It creates or reuses:

  1. an Entra ID application and its service principal;
  2. two federated credentials, for refs/heads/main and for the production environment, because a
     job that declares an environment authenticates with the environment subject;
  3. a Contributor role assignment scoped to the resource group only;
  4. the repository variables both workflows read.

.PARAMETER ResourceGroup
Resource group that holds the two Web Apps. The role assignment is scoped to it.

.PARAMETER AppName
Display name of the Entra ID application. One application serves both workflows.

.PARAMETER Repo
GitHub repository in owner/name form. Part of the OIDC subject claim.

.PARAMETER Branch
Branch the release workflow runs on.

.PARAMETER Environment
GitHub environment declared by both deploy jobs.

.PARAMETER SubscriptionId
Subscription to grant the role in. Defaults to the subscription az is currently pointing at.

.PARAMETER DryRun
Prints what would be created and exits without calling Azure or GitHub.

.EXAMPLE
az login
.\scripts\setup-azure-oidc.ps1 -DryRun
.\scripts\setup-azure-oidc.ps1

.EXAMPLE
.\scripts\setup-azure-oidc.ps1 -ResourceGroup pixelpass-rg -SubscriptionId 00000000-0000-0000-0000-000000000000
#>

[CmdletBinding()]
param(
    [string]$ResourceGroup = 'pixelpass-rg',
    [string]$AppName = 'pixelpass-github-deploy',
    [string]$Repo = 'RyanMatuRob/pixelpass',
    [string]$Branch = 'main',
    [string]$Environment = 'production',
    [string]$SubscriptionId,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Note([string]$Message) {
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Invoke-Az {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & az @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Azure CLI failed: az $($Arguments -join ' ')"
    }
}

function Invoke-Gh {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)
    & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub CLI failed: gh $($Arguments -join ' ')"
    }
}

# Captures stdout of a native command. PowerShell 5.1 turns redirected stderr into terminating
# errors, so the preference is relaxed around the call and a non-zero exit returns $null.
function Invoke-CliValue {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $output = & $Command @Arguments 2>$null
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0) { return $null }
    return (($output | Out-String).Trim())
}

function Test-Cli {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$Arguments = @()
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        & $Command @Arguments 2>$null | Out-Null
        $ok = ($LASTEXITCODE -eq 0)
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return $ok
}

# ---------------------------------------------------------------------------------------------
# Dry run
# ---------------------------------------------------------------------------------------------
if (-not $Branch -or -not $Repo -or -not $Environment) {
    throw 'Repo, Branch and Environment are required; they form the OIDC subject claim.'
}

if ($DryRun) {
    Write-Step 'Dry run'
    Write-Note 'Nothing is created, granted, or written to GitHub.'
    Write-Note "Would create or reuse the Entra ID application '$AppName' and its service principal."
    Write-Note 'Would add these federated credentials:'
    Write-Note "  github-branch-$Branch            -> repo:${Repo}:ref:refs/heads/${Branch}"
    Write-Note "  github-environment-$Environment -> repo:${Repo}:environment:${Environment}"
    Write-Note "Would grant Contributor on /subscriptions/<subscription-id>/resourceGroups/$ResourceGroup."
    Write-Note 'Would set repository variables AZURE_CLIENT_ID, AZURE_TENANT_ID,'
    Write-Note 'AZURE_SUBSCRIPTION_ID and AZURE_RESOURCE_GROUP.'
    Write-Note 'Re-run without -DryRun to apply.'
    return
}

# ---------------------------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------------------------
Write-Step 'Preflight'

if (-not (Get-Command az -ErrorAction SilentlyContinue)) {
    throw ('Azure CLI not found. Install it with: winget install --exact --id Microsoft.AzureCLI' +
        ', open a new terminal, then run az login.')
}
if (-not (Test-Cli -Command 'az' -Arguments @('account', 'show'))) {
    throw 'Not signed in to Azure. Run az login in this terminal first.'
}

# One call returns both ids, tab separated, so the tenant survives a subscription switch.
$context = Invoke-CliValue -Command 'az' -Arguments @('account', 'show', '--query', '[id,tenantId]', '--output', 'tsv')
$contextParts = @($context -split "`t")
if ($contextParts.Count -lt 2) {
    throw 'Could not read the current subscription and tenant. Run az login again.'
}
$currentSubscriptionId = $contextParts[0]
$tenantId = $contextParts[1]
if (-not $SubscriptionId) { $SubscriptionId = $currentSubscriptionId }
$scope = "/subscriptions/$SubscriptionId/resourceGroups/$ResourceGroup"

if (Test-Cli -Command 'az' -Arguments @('group', 'show', '--name', $ResourceGroup)) {
    Write-Note "Resource group : $ResourceGroup"
}
else {
    throw "Resource group $ResourceGroup does not exist. Run scripts/provision-azure.ps1 first."
}
if (Test-Cli -Command 'az' -Arguments @('account', 'set', '--subscription', $SubscriptionId)) {
    Write-Note "Subscription   : $SubscriptionId"
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw 'GitHub CLI not found. Install it with: winget install --exact --id GitHub.cli'
}
if (-not (Test-Cli -Command 'gh' -Arguments @('auth', 'status'))) {
    throw 'GitHub CLI is not authenticated. Run gh auth login.'
}
Write-Note "Repository     : $Repo ($Branch, environment $Environment)"

# ---------------------------------------------------------------------------------------------
# 1. Entra ID application and service principal
# ---------------------------------------------------------------------------------------------
Write-Step "Entra ID application $AppName"

$appId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'app', 'list', '--display-name', $AppName,
    '--query', '[0].appId', '--output', 'tsv')
if ($appId -notmatch '^[0-9a-fA-F-]{36}$') {
    $appId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'app', 'create', '--display-name', $AppName,
        '--query', 'appId', '--output', 'tsv')
    if ($appId -notmatch '^[0-9a-fA-F-]{36}$') {
        throw "Could not create the Entra ID application $AppName."
    }
    Write-Note "Created application $appId."
}
else {
    Write-Note "Reusing application $appId."
}

$spId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'sp', 'list', '--filter', "appId eq '$appId'",
    '--query', '[0].id', '--output', 'tsv')
if ($spId -notmatch '^[0-9a-fA-F-]{36}$') {
    $spId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'sp', 'create', '--id', $appId,
        '--query', 'id', '--output', 'tsv')
    if ($spId -notmatch '^[0-9a-fA-F-]{36}$') {
        throw "Could not create the service principal for $appId."
    }
    Write-Note "Created service principal $spId."
}
else {
    Write-Note "Reusing service principal $spId."
}

# ---------------------------------------------------------------------------------------------
# 2. Federated credentials
# ---------------------------------------------------------------------------------------------
Write-Step 'Federated credentials'

# The subject must match the claim GitHub sends exactly, or login fails with AADSTS70021.
$subjects = [ordered]@{
    "github-branch-$Branch"           = "repo:${Repo}:ref:refs/heads/${Branch}"
    "github-environment-$Environment" = "repo:${Repo}:environment:${Environment}"
}
$existing = Invoke-CliValue -Command 'az' -Arguments @('ad', 'app', 'federated-credential', 'list',
    '--id', $appId, '--query', '[].name', '--output', 'tsv')
$existingNames = @()
if ($existing) { $existingNames = @($existing -split "`r?`n") }

foreach ($name in $subjects.Keys) {
    if ($existingNames -contains $name) {
        Write-Note "$name already exists."
        continue
    }
    # -Compress keeps the JSON on one line, so it survives as a single argv entry.
    $parameters = [ordered]@{
        name      = $name
        issuer    = 'https://token.actions.githubusercontent.com'
        subject   = $subjects[$name]
        audiences = @('api://AzureADTokenExchange')
    } | ConvertTo-Json -Depth 5 -Compress
    Invoke-Az @('ad', 'app', 'federated-credential', 'create', '--id', $appId,
        '--parameters', $parameters, '--output', 'none')
    Write-Note "Added $name -> $($subjects[$name])."
}

# ---------------------------------------------------------------------------------------------
# 3. Role assignment, scoped to the resource group
# ---------------------------------------------------------------------------------------------
Write-Step "Contributor on $ResourceGroup"

$assignments = Invoke-CliValue -Command 'az' -Arguments @('role', 'assignment', 'list',
    '--assignee-object-id', $spId, '--scope', $scope, '--role', 'Contributor',
    '--query', 'length(@)', '--output', 'tsv')
if ($assignments -eq '0' -or -not $assignments) {
    # Contributor on the resource group is enough for webapps-deploy, and avoids a subscription
    # wide grant. ActiveDirectoryServicePrincipal skips the graph lookup that intermittent
    # tenants fail on.
    Invoke-Az @('role', 'assignment', 'create', '--assignee-object-id', $spId,
        '--assignee-principal-type', 'ServicePrincipal', '--role', 'Contributor',
        '--scope', $scope, '--output', 'none')
    Write-Note "Granted Contributor on $scope."
}
else {
    Write-Note "Contributor already assigned on $scope."
}

# ---------------------------------------------------------------------------------------------
# 4. Repository variables
# ---------------------------------------------------------------------------------------------
Write-Step "Repository variables for $Repo"

Push-Location $repoRoot
try {
    $variables = [ordered]@{
        AZURE_CLIENT_ID       = $appId
        AZURE_TENANT_ID       = $tenantId
        AZURE_SUBSCRIPTION_ID = $SubscriptionId
        AZURE_RESOURCE_GROUP  = $ResourceGroup
    }
    foreach ($name in $variables.Keys) {
        Invoke-Gh @('variable', 'set', $name, '--body', $variables[$name])
        Write-Note "$name = $($variables[$name])"
    }
}
finally {
    Pop-Location
}

# ---------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------
Write-Step 'Summary'
Write-Note "Application (client) ID : $appId"
Write-Note "Tenant ID               : $tenantId"
Write-Note "Subscription ID         : $SubscriptionId"
Write-Note "Resource group          : $ResourceGroup"
Write-Note 'The four AZURE_* variables are set, so both deploy workflows can sign in now.'

Write-Host ''
Write-Host 'Next steps:' -ForegroundColor Cyan
Write-Host '  1. Merge the fix branch; the workflows log in with OIDC and ignore publish profiles.'
Write-Host '  2. Re-run the failed runs, or push to main, and watch the deploy step.'
Write-Host '  3. Once a run is green, delete the unused secrets:'
Write-Host '       gh secret delete AZURE_BACKEND_PUBLISH_PROFILE'
Write-Host '       gh secret delete AZURE_ML_PUBLISH_PROFILE'
Write-Host '  4. Optional hardening, now that nothing reads publish profiles:'
Write-Host '       az resource update --resource-group <rg> --namespace Microsoft.Web'
Write-Host '         --resource-type basicPublishingCredentialsPolicies --name scm --parent <app>'
Write-Host '         --set properties.allow=false'
