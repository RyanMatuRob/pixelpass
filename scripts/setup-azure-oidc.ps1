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

.PARAMETER IdentityName
Name of the user-assigned managed identity used when an application cannot be registered.

.PARAMETER IdentityType
Auto (default) tries an Entra ID application and falls back to a managed identity when the account may
not register one. AppRegistration and ManagedIdentity force one flavour.

.PARAMETER IdentityLocation
Region for the managed identity. Defaults to the resource group's region, because subscriptions often
restrict managed identities to an allowed set of regions.

.PARAMETER Repo
GitHub repository in owner/name form. Part of the OIDC subject claim.

.PARAMETER Branch
Branch the release workflow runs on.

.PARAMETER Environment
GitHub environment declared by both deploy jobs.

.PARAMETER SubscriptionId
Subscription to grant the role in. Defaults to the subscription az is currently pointing at.

.PARAMETER TenantId
Tenant that owns the application. Defaults to the tenant az is currently pointing at, and only needs
to be passed when the az context cannot be read.

.PARAMETER DryRun
Prints what would be created and exits without calling Azure or GitHub.

.EXAMPLE
az login
.\scripts\setup-azure-oidc.ps1 -DryRun
.\scripts\setup-azure-oidc.ps1

.EXAMPLE
.\scripts\setup-azure-oidc.ps1 -ResourceGroup pixelpass-rg -SubscriptionId 00000000-0000-0000-0000-000000000000

.EXAMPLE
# When az cannot be found or its context is unusable, pass both ids instead.
.\scripts\setup-azure-oidc.ps1 -SubscriptionId 00000000-0000-0000-0000-000000000000 -TenantId 11111111-1111-1111-1111-111111111111
#>

[CmdletBinding()]
param(
    [string]$ResourceGroup = 'pixelpass-rg',
    [string]$AppName = 'pixelpass-github-deploy',
    [string]$IdentityName = 'pixelpass-github-deploy',
    [ValidateSet('Auto', 'AppRegistration', 'ManagedIdentity')]
    [string]$IdentityType = 'Auto',
    [string]$IdentityLocation,
    [string]$Repo = 'RyanMatuRob/pixelpass',
    [string]$Branch = 'main',
    [string]$Environment = 'production',
    [string]$SubscriptionId,
    [string]$TenantId,
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

# az is not always on PATH, for example when the CLI was installed for another user profile or the
# shell was opened before the installer refreshed PATH. PATH is preferred; the standard install
# locations are the fallback.
function Resolve-AzCommand {
    $onPath = Get-Command az -ErrorAction SilentlyContinue
    if ($onPath) { return 'az' }

    $roots = @(
        [Environment]::GetEnvironmentVariable('ProgramFiles'),
        [Environment]::GetEnvironmentVariable('ProgramFiles(x86)'),
        [Environment]::GetEnvironmentVariable('LOCALAPPDATA')
    )
    $suffixes = @(
        'Microsoft SDKs\Azure\CLI2\wbin\az.cmd',
        'Programs\Azure CLI\wbin\az.cmd'
    )
    foreach ($root in $roots) {
        if (-not $root) { continue }
        foreach ($suffix in $suffixes) {
            $candidate = Join-Path $root $suffix
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    return $null
}

# A multi-value --query is printed one value per line, and CLI warnings can be mixed into the same
# stream, so the first well-formed GUID is kept instead of splitting on a separator.
function Read-AzGuid {
    param(
        [Parameter(Mandatory = $true)][string]$Query,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $raw = [string](Invoke-CliValue -Command 'az' -Arguments @('account', 'show', '--query', $Query,
        '--output', 'tsv'))
    $match = [regex]::Match($raw, '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}')
    if (-not $match.Success) {
        throw ("Could not read the $Label from az. Run az login again, or pass -SubscriptionId and " +
            '-TenantId explicitly.')
    }
    return $match.Value
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
    if ($IdentityType -eq 'AppRegistration') {
        Write-Note "Would create or reuse the Entra ID application '$AppName' and its service principal."
    }
    elseif ($IdentityType -eq 'ManagedIdentity') {
        Write-Note "Would create or reuse the managed identity '$IdentityName' in $ResourceGroup."
    }
    else {
        Write-Note "Would create or reuse the Entra ID application '$AppName', or the managed identity"
        Write-Note "'$IdentityName' when this account may not register applications."
    }
    Write-Note 'Would add these federated credentials:'
    Write-Note "  github-branch-$Branch            -> repo:${Repo}:ref:refs/heads/${Branch}"
    Write-Note "  github-environment-$Environment -> repo:${Repo}:environment:${Environment}"
    Write-Note 'Would also add the -immutable variants when GitHub issues id-qualified subjects,'
    Write-Note 'which is decided at run time from the repository id.'
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
    $resolvedAz = Resolve-AzCommand
    if (-not $resolvedAz) {
        throw ('Azure CLI not found. Install it with: winget install --exact --id Microsoft.AzureCLI' +
            ', open a new terminal, then run az login.')
    }
    # An alias makes every 'az' invocation below resolve to the installed executable.
    Set-Alias -Name az -Value $resolvedAz -Scope Script -Force
    Write-Note "Azure CLI      : $resolvedAz (not on PATH)"
}
if (-not (Test-Cli -Command 'az' -Arguments @('account', 'show'))) {
    throw 'Not signed in to Azure. Run az login in this terminal first.'
}

# Both ids are read separately: a multi-value --query prints one value per line, which a single
# combined read cannot be split reliably. Passing them explicitly skips the reads entirely.
if (-not $SubscriptionId) { $SubscriptionId = Read-AzGuid -Query 'id' -Label 'subscription id' }
if (-not $TenantId) { $TenantId = Read-AzGuid -Query 'tenantId' -Label 'tenant id' }
$scope = "/subscriptions/$SubscriptionId/resourceGroups/$ResourceGroup"

if (Test-Cli -Command 'az' -Arguments @('group', 'show', '--name', $ResourceGroup)) {
    $Location = Invoke-CliValue -Command 'az' -Arguments @('group', 'show', '--name', $ResourceGroup,
        '--query', 'location', '--output', 'tsv')
    Write-Note "Resource group : $ResourceGroup ($Location)"
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

# GitHub can issue immutable subject claims that carry the numeric owner and repository ids, for
# example repo:Owner@168424652/Repository@1393706386:environment:production. Registering both
# spellings keeps login working whichever form the token uses.
$immutableRepo = $null
$ownerId = Invoke-CliValue -Command 'gh' -Arguments @('api', "repos/$Repo", '--jq', '.owner.id')
$repoId = Invoke-CliValue -Command 'gh' -Arguments @('api', "repos/$Repo", '--jq', '.id')
$nameParts = @($Repo -split '/')
if ($ownerId -and $repoId -and $nameParts.Count -eq 2) {
    $immutableRepo = "$($nameParts[0])@$ownerId/$($nameParts[1])@$repoId"
}

# ---------------------------------------------------------------------------------------------
# 1. Deploy identity
# ---------------------------------------------------------------------------------------------
# A managed identity is preferred where an application may not be registered: it is created through
# Azure Resource Manager, needs no directory rights, and azure/login accepts its client id with the
# same federated credentials. Student and locked-down tenants routinely withhold app registration.
$issuer = 'https://token.actions.githubusercontent.com'
$identity = $null

if ($IdentityType -ne 'ManagedIdentity') {
    Write-Step "Entra ID application $AppName"
    $candidateAppId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'app', 'list',
        '--display-name', $AppName, '--query', '[0].appId', '--output', 'tsv')
    if ($candidateAppId -notmatch '^[0-9a-fA-F-]{36}$') {
        $candidateAppId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'app', 'create',
            '--display-name', $AppName, '--query', 'appId', '--output', 'tsv')
    }
    $candidateSpId = $null
    if ($candidateAppId -match '^[0-9a-fA-F-]{36}$') {
        $candidateSpId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'sp', 'list',
            '--filter', "appId eq '$candidateAppId'", '--query', '[0].id', '--output', 'tsv')
        if ($candidateSpId -notmatch '^[0-9a-fA-F-]{36}$') {
            $candidateSpId = Invoke-CliValue -Command 'az' -Arguments @('ad', 'sp', 'create',
                '--id', $candidateAppId, '--query', 'id', '--output', 'tsv')
        }
    }
    if ($candidateSpId -match '^[0-9a-fA-F-]{36}$') {
        $identity = [pscustomobject]@{
            Kind        = 'AppRegistration'
            ClientId    = $candidateAppId
            PrincipalId = $candidateSpId
        }
        Write-Note "Application (client) ID : $($identity.ClientId)"
        Write-Note "Service principal ID    : $($identity.PrincipalId)"
    }
    elseif ($IdentityType -eq 'AppRegistration') {
        throw "Could not create the Entra ID application $AppName."
    }
    else {
        Write-Note 'This account may not register applications (directory permission is withheld).'
        Write-Note 'Falling back to a managed identity; ask a tenant administrator for the'
        Write-Note 'Application Developer role if an application is preferred.'
    }
}

if (-not $identity) {
    Write-Step "Managed identity $IdentityName"
    $identityRegion = $Location
    if ($IdentityLocation) { $identityRegion = $IdentityLocation }
    $payload = Invoke-CliValue -Command 'az' -Arguments @('identity', 'show', '--name', $IdentityName,
        '--resource-group', $ResourceGroup,
        '--query', '{clientId:clientId,principalId:principalId}', '--output', 'json')
    $verb = 'Reusing'
    if (-not $payload) {
        # Managed identities are refused outside the subscription's allowed regions, so the resource
        # group's own region is the only safe default.
        $payload = Invoke-CliValue -Command 'az' -Arguments @('identity', 'create', '--name', $IdentityName,
            '--resource-group', $ResourceGroup, '--location', $identityRegion,
            '--query', '{clientId:clientId,principalId:principalId}', '--output', 'json')
        $verb = 'Created'
    }
    $parsed = $null
    if ($payload) { $parsed = $payload | ConvertFrom-Json }
    if (-not $parsed -or -not $parsed.clientId) {
        throw "Could not create the managed identity $IdentityName in $ResourceGroup."
    }
    $identity = [pscustomobject]@{
        Kind        = 'ManagedIdentity'
        ClientId    = $parsed.clientId
        PrincipalId = $parsed.principalId
    }
    Write-Note "$verb managed identity (client) ID : $($identity.ClientId)"
    Write-Note "Principal ID                       : $($identity.PrincipalId)"
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
if ($immutableRepo) {
    $subjects["github-branch-$Branch-immutable"] = "repo:${immutableRepo}:ref:refs/heads/${Branch}"
    $subjects["github-environment-$Environment-immutable"] = "repo:${immutableRepo}:environment:${Environment}"
}
if ($identity.Kind -eq 'ManagedIdentity') {
    $existing = Invoke-CliValue -Command 'az' -Arguments @('identity', 'federated-credential', 'list',
        '--identity-name', $IdentityName, '--resource-group', $ResourceGroup,
        '--query', '[].name', '--output', 'tsv')
}
else {
    $existing = Invoke-CliValue -Command 'az' -Arguments @('ad', 'app', 'federated-credential', 'list',
        '--id', $identity.ClientId, '--query', '[].name', '--output', 'tsv')
}
$existingNames = @()
if ($existing) { $existingNames = @($existing -split "`r?`n") }

foreach ($name in $subjects.Keys) {
    if ($existingNames -contains $name) {
        Write-Note "$name already exists."
        continue
    }
    if ($identity.Kind -eq 'ManagedIdentity') {
        Invoke-Az @('identity', 'federated-credential', 'create', '--name', $name,
            '--identity-name', $IdentityName, '--resource-group', $ResourceGroup,
            '--issuer', $issuer, '--subject', $subjects[$name],
            '--audiences', 'api://AzureADTokenExchange', '--output', 'none')
    }
    else {
        # -Compress keeps the JSON on one line, so it survives as a single argv entry.
        $parameters = [ordered]@{
            name      = $name
            issuer    = $issuer
            subject   = $subjects[$name]
            audiences = @('api://AzureADTokenExchange')
        } | ConvertTo-Json -Depth 5 -Compress
        Invoke-Az @('ad', 'app', 'federated-credential', 'create', '--id', $identity.ClientId,
            '--parameters', $parameters, '--output', 'none')
    }
    Write-Note "Added $name -> $($subjects[$name])."
}

# ---------------------------------------------------------------------------------------------
# 3. Role assignment, scoped to the resource group
# ---------------------------------------------------------------------------------------------
Write-Step "Contributor on $ResourceGroup"

# JSON is counted here rather than with --query 'length(@)': the parentheses do not survive the
# az.cmd shim, which made the check fail and re-grant on every run.
$assignmentJson = Invoke-CliValue -Command 'az' -Arguments @('role', 'assignment', 'list',
    '--assignee-object-id', $identity.PrincipalId, '--scope', $scope, '--role', 'Contributor',
    '--output', 'json')
$assignmentCount = 0
if ($assignmentJson) {
    $assignmentCount = @($assignmentJson | ConvertFrom-Json |
            Where-Object { $_ -and $_.roleDefinitionName }).Count
}
if ($assignmentCount -eq 0) {
    # Contributor on the resource group is enough for webapps-deploy, and avoids a subscription
    # wide grant. ActiveDirectoryServicePrincipal skips the graph lookup that intermittent
    # tenants fail on.
    Invoke-Az @('role', 'assignment', 'create', '--assignee-object-id', $identity.PrincipalId,
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
        AZURE_CLIENT_ID       = $identity.ClientId
        AZURE_TENANT_ID       = $TenantId
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
Write-Note "Identity               : $($identity.Kind) (client) $($identity.ClientId)"
Write-Note "Tenant ID               : $TenantId"
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
