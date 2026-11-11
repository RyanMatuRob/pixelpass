<#
.SYNOPSIS
Reports Azure for Students credit consumption for the PixelPass subscription and fails once the
accrued cost crosses the alert threshold.

.DESCRIPTION
An Azure budget (Microsoft.Consumption/budgets) cannot be written on this subscription. The billing
account sits in a different tenant from the subscription, so every write is rejected with
401 / RBACAccessDenied even though the signed-in user holds Owner at subscription scope:

    az rest --method put \
      '.../subscriptions/<id>/providers/Microsoft.Consumption/budgets/<name>'   -> RBACAccessDenied
    az rest --method put \
      '.../subscriptions/<id>/resourceGroups/<rg>/providers/...'                -> RBACAccessDenied

Those were tried against api-versions 2019-05-01, 2019-10-01, 2021-10-01, 2023-05-01, 2023-11-01 and
2024-08-01, with and without an explicit ARM audience. GET on the same route answers normally, and
Microsoft.Consumption is Registered, so the failure is authorization, not configuration.

This script therefore keeps the score itself against the Usage Details API, which the same identity
can read. The credit window comes from the subscription's free-tier promotion: the API reports the
promotion end date, and a students credit cycle is one year long, so the current cycle started one
year earlier. That keeps the checkpoint aligned when the credit renews.

Exit codes:
    0  accrued cost is still below the threshold
    1  accrued cost has reached the threshold
    2  Azure could not be queried

.PARAMETER Threshold
Dollar amount that raises the alert. Default 50, which is half of the students credit.

.PARAMETER CreditAmount
Credit granted for the cycle, used to report what is left. Default 100.

.PARAMETER StartDate
Overrides the first day of the credit cycle.

.PARAMETER EndDate
Overrides the end of the reporting window. Defaults to now, in UTC.

.PARAMETER TailDays
Number of trailing days used for the run-rate projection. Default 7.

.PARAMETER Detailed
Adds a per-service breakdown of the accrued cost.

.PARAMETER SubscriptionId
Subscription to read. Defaults to the subscription az is currently signed in to.

.EXAMPLE
.\scripts\check-azure-budget.ps1

.EXAMPLE
# Report a breakdown and use a tighter checkpoint while the plans are consolidated.
.\scripts\check-azure-budget.ps1 -Threshold 40 -Detailed

.EXAMPLE
# Ignore the promotion metadata and check a specific window.
.\scripts\check-azure-budget.ps1 -StartDate 2026-10-01 -EndDate 2026-11-30
#>

[CmdletBinding()]
param(
    [double]$Threshold = 50,
    [double]$CreditAmount = 100,
    [datetime]$StartDate,
    [datetime]$EndDate,
    [int]$TailDays = 7,
    [switch]$Detailed,
    [string]$SubscriptionId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 still negotiates TLS 1.0 by default, and ARM rejects that handshake.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$script:ArmEndpoint = 'https://management.azure.com'

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Note([string]$Message) {
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Write-Value([string]$Label, [string]$Value) {
    Write-Host ("    {0,-28} {1}" -f $Label, $Value)
}

# Reads an optional property without tripping Set-StrictMode when the API omits it.
function Get-Property {
    param(
        [Parameter(Mandatory = $true)]$InputObject,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($null -eq $InputObject) { return $null }
    $property = $InputObject.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-ArmToken {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $token = & az account get-access-token --resource $script:ArmEndpoint --query accessToken -o tsv 2>$null
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0 -or [string]::IsNullOrWhiteSpace($token)) {
        throw 'Could not read an ARM token. Run "az login" first.'
    }
    return $token.Trim()
}

function Get-CurrentSubscriptionId {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    try {
        $id = & az account show --query id -o tsv 2>$null
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($code -ne 0 -or [string]::IsNullOrWhiteSpace($id)) {
        throw 'No subscription is selected. Run "az login" first.'
    }
    return $id.Trim()
}

# Follows the nextLink chain, because one page of usage details covers a single billing period at
# most and a credit cycle spans twelve of them.
function Invoke-ArmGet {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Token
    )
    $headers = @{ Authorization = "Bearer $Token" }
    $items = New-Object System.Collections.Generic.List[object]
    $next = $Uri
    while (-not [string]::IsNullOrWhiteSpace($next)) {
        # Student subscriptions are throttled aggressively on the consumption endpoints, and a
        # historical window regularly answers 504 before it answers 200, so pages are retried.
        $attempts = 4
        $page = $null
        for ($attempt = 1; $attempt -le $attempts; $attempt++) {
            try {
                $page = Invoke-RestMethod -Uri $next -Headers $headers -Method Get
                break
            }
            catch {
                $status = 0
                $response = $_.Exception.PSObject.Properties['Response']
                if ($null -ne $response -and $null -ne $response.Value) {
                    $status = [int]$response.Value.StatusCode
                }
                $retryable = ($status -eq 429 -or $status -eq 503 -or $status -eq 504)
                if ($attempt -ge $attempts -or -not $retryable) { throw }
                $wait = [int][math]::Pow(2, $attempt)
                Write-Note "Usage Details answered HTTP $status, retrying in $wait s (attempt $attempt of $attempts)."
                Start-Sleep -Seconds $wait
            }
        }
        foreach ($item in $page.value) { $items.Add($item) }
        $next = Get-Property -InputObject $page -Name 'nextLink'
    }
    return $items
}

# The subscription reports the free-tier promotion end date. A students cycle is one year long, so
# the current cycle started a year before that, and the value rolls forward on renewal.
function Get-CreditWindow {
    param(
        [Parameter(Mandatory = $true)][string]$SubscriptionId,
        [Parameter(Mandatory = $true)][string]$Token
    )
    $uri = "$script:ArmEndpoint/subscriptions/$SubscriptionId`?api-version=2022-12-01"
    $subscription = Invoke-RestMethod -Uri $uri -Headers @{ Authorization = "Bearer $Token" } -Method Get

    $start = $null
    $end = $null
    # A one-element JSON array arrives as a single object, and Windows PowerShell 5.1 does not give
    # scalars a Count property, so the promotion list is forced into an array first.
    $promotions = @(Get-Property -InputObject $subscription -Name 'promotions' | Where-Object { $null -ne $_ })
    if ($promotions.Count -gt 0) {
        $promotionEnd = Get-Property -InputObject $promotions[0] -Name 'endDateTime'
        if (-not [string]::IsNullOrWhiteSpace($promotionEnd)) {
            $end = ([datetime]$promotionEnd).ToUniversalTime()
            $start = $end.AddYears(-1)
        }
    }
    return [pscustomobject]@{
        DisplayName = (Get-Property -InputObject $subscription -Name 'displayName')
        QuotaId     = (Get-Property -InputObject (Get-Property -InputObject $subscription -Name 'subscriptionPolicies') -Name 'quotaId')
        Start       = $start
        End         = $end
    }
}

function Get-UsageRecord {
    param(
        [Parameter(Mandatory = $true)][string]$SubscriptionId,
        [Parameter(Mandatory = $true)][datetime]$From,
        [Parameter(Mandatory = $true)][datetime]$To,
        [Parameter(Mandatory = $true)][string]$Token
    )
    $uri = '{0}/subscriptions/{1}/providers/Microsoft.Consumption/usageDetails?api-version=2023-11-01&startDate={2:yyyy-MM-dd}&endDate={3:yyyy-MM-dd}' -f `
        $script:ArmEndpoint, $SubscriptionId, $From, $To
    $records = Invoke-ArmGet -Uri $uri -Token $Token

    $total = 0.0
    foreach ($record in $records) {
        $cost = Get-Property -InputObject (Get-Property -InputObject $record -Name 'properties') -Name 'costInUSD'
        if ($null -ne $cost -and -not [string]::IsNullOrWhiteSpace([string]$cost)) {
            $total += [double]$cost
        }
    }
    return [pscustomobject]@{ Total = $total; Records = $records }
}

# --- Report -------------------------------------------------------------------------------------

try {
    Write-Step 'Resolving the subscription'
    $token = Get-ArmToken
    if ([string]::IsNullOrWhiteSpace($SubscriptionId)) {
        $resolvedId = Get-CurrentSubscriptionId
    }
    elseif ($SubscriptionId -match '/subscriptions/([^/]+)') {
        $resolvedId = $Matches[1]
    }
    else {
        $resolvedId = $SubscriptionId.Trim()
    }

    $window = Get-CreditWindow -SubscriptionId $resolvedId -Token $token
    Write-Value 'Subscription' $window.DisplayName
    Write-Value 'Subscription id' $resolvedId
    Write-Value 'Offer' $window.QuotaId

    $now = [datetime]::UtcNow
    # The API takes calendar dates, so an explicit date is used exactly as typed; converting a South
    # African (UTC+2) midnight to UTC would move the window back a day.
    if ($PSBoundParameters.ContainsKey('StartDate')) {
        $from = $StartDate.Date
    }
    elseif ($null -ne $window.Start) {
        $from = $window.Start
    }
    else {
        $from = Get-Date -Year $now.Year -Month $now.Month -Day 1 -Hour 0 -Minute 0 -Second 0
        Write-Note 'No free-tier promotion on the subscription, so the window starts this month.'
    }

    if ($PSBoundParameters.ContainsKey('EndDate')) {
        $to = $EndDate.Date
    }
    else {
        $to = $now
    }
    if ($to -gt $now) { $to = $now }
    if ($from -ge $to) { throw "The reporting window is empty: $($from.ToString('yyyy-MM-dd')) to $($to.ToString('yyyy-MM-dd'))." }

    Write-Value 'Credit cycle starts' $from.ToString('yyyy-MM-dd')
    if ($null -ne $window.End) {
        Write-Value 'Promotion ends' $window.End.ToString('yyyy-MM-dd')
    }

    Write-Step 'Summing rated usage'
    $cycle = Get-UsageRecord -SubscriptionId $resolvedId -From $from -To $to -Token $token
    Write-Note "$($cycle.Records.Count) line items read for the window."

    # The run rate is measured over the trailing days only, so a burst of setup cost weeks ago does
    # not make the projection look healthier than the plan really is.
    $tailFrom = $to.AddDays(-1 * $TailDays)
    if ($tailFrom -lt $from) { $tailFrom = $from }
    $tailDays = [math]::Max(1.0, ($to - $tailFrom).TotalDays)
    $tailUsage = Get-UsageRecord -SubscriptionId $resolvedId -From $tailFrom -To $to -Token $token
    $tailRate = $tailUsage.Total / $tailDays

    $accrued = $cycle.Total
    $remaining = $CreditAmount - $accrued

    Write-Step 'Result'
    Write-Value 'Accrued this cycle' ('$' + [math]::Round($accrued, 2))
    Write-Value 'Credit granted' ('$' + [math]::Round($CreditAmount, 2))
    Write-Value 'Credit left' ('$' + [math]::Round($remaining, 2))
    Write-Value 'Alert threshold' ('$' + [math]::Round($Threshold, 2))
    Write-Value 'Trailing run rate' ('$' + [math]::Round($tailUsage.Total, 2) + " over $([math]::Round($tailDays, 1)) days = `$" + [math]::Round($tailRate, 2) + '/day')

    if ($tailRate -gt 0) {
        Write-Value 'Burn per month' ('$' + [math]::Round($tailRate * 30.44, 2))
        if ($accrued -ge $Threshold) {
            Write-Value 'Threshold crossed' ('already, ' + [math]::Round(($accrued - $Threshold) / $tailRate, 1) + ' days ago')
        }
        else {
            $daysToThreshold = ($Threshold - $accrued) / $tailRate
            Write-Value 'Threshold reached in' ("$([math]::Round($daysToThreshold, 1)) days, around " + $to.AddDays($daysToThreshold).ToString('yyyy-MM-dd'))
        }
        if ($remaining -gt 0) {
            $daysToZero = $remaining / $tailRate
            Write-Value 'Credit exhausted in' ("$([math]::Round($daysToZero, 1)) days, around " + $to.AddDays($daysToZero).ToString('yyyy-MM-dd'))
        }
        else {
            Write-Value 'Credit exhausted' 'already'
        }
    }
    else {
        Write-Note 'No spend in the trailing window, so no projection was made.'
    }

    if ($Detailed) {
        Write-Step 'Breakdown by service'
        $breakdown = $cycle.Records | ForEach-Object {
            $properties = Get-Property -InputObject $_ -Name 'properties'
            [pscustomobject]@{
                Service = [string](Get-Property -InputObject $properties -Name 'consumedService')
                Cost    = [double](Get-Property -InputObject $properties -Name 'costInUSD')
            }
        } | Group-Object -Property Service | ForEach-Object {
            [pscustomobject]@{
                Service = $_.Name
                Cost    = [math]::Round((($_.Group | Measure-Object -Property Cost -Sum).Sum), 2)
            }
        } | Sort-Object Cost -Descending
        $breakdown | Format-Table -AutoSize | Out-String -Width 120 | Write-Host
    }

    if ($accrued -ge $Threshold) {
        Write-Host ''
        Write-Host "ALERT: accrued cost is `$$([math]::Round($accrued, 2)), at or above the `$$([math]::Round($Threshold, 2)) checkpoint." -ForegroundColor Yellow
        exit 1
    }

    Write-Host ''
    Write-Host "OK: `$$([math]::Round($accrued, 2)) accrued, still below the `$$([math]::Round($Threshold, 2)) checkpoint." -ForegroundColor Green
    exit 0
}
catch {
    Write-Host ''
    Write-Host "Failed to read Azure cost: $($_.Exception.Message)" -ForegroundColor Red
    if (-not [string]::IsNullOrWhiteSpace($_.ScriptStackTrace)) {
        Write-Host $_.ScriptStackTrace -ForegroundColor DarkGray
    }
    exit 2
}
