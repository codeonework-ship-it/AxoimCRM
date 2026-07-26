param(
  [string]$ApiBase = "http://localhost:8080/api/v1",
  [string]$Tenant = "meridian",
  [string]$Email = "superadmin@axiomcrm.com",
  [string]$Password = "axiom-demo",
  [int]$MinimumSourceRows = 100000,
  [int]$Iterations = 20,
  [int]$P95TargetMs = 3000,
  [switch]$AllowSmallDataset
)

$ErrorActionPreference = "Stop"
$login = Invoke-RestMethod -Method Post -Uri "$ApiBase/auth/login" -ContentType "application/json" -Body (@{
  tenantSlug = $Tenant
  email = $Email
  password = $Password
} | ConvertTo-Json)
if (-not $login.token) { throw "Authentication did not return a bearer token." }
$headers = @{ Authorization = "Bearer $($login.token)" }

$projectionResponse = Invoke-RestMethod -Uri "$ApiBase/analytics/projections" -Headers $headers
$projections = @($projectionResponse)
$sourceRows = ($projections | Measure-Object -Property sourceRowCount -Sum).Sum
if ($sourceRows -lt $MinimumSourceRows -and -not $AllowSmallDataset) {
  throw "Scale validation requires at least $MinimumSourceRows projected source rows; this environment has $sourceRows. Load production-shaped synthetic data or pass -AllowSmallDataset for a smoke run."
}

$reportResponse = Invoke-RestMethod -Uri "$ApiBase/analytics/reports" -Headers $headers
$reports = @($reportResponse)
if ($reports.Count -eq 0) { throw "No saved analytics reports are available for validation." }

$samples = [System.Collections.Generic.List[object]]::new()
for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
  foreach ($report in $reports) {
    $started = [System.Diagnostics.Stopwatch]::StartNew()
    try {
      $result = Invoke-RestMethod -Method Post -Uri "$ApiBase/analytics/reports/$($report.code)/run" -Headers $headers
      $started.Stop()
      $samples.Add([pscustomobject]@{
        report = $report.code
        iteration = $iteration
        clientMs = $started.ElapsedMilliseconds
        serverMs = $result.elapsedMs
        rows = $result.rowCount
        truncated = $result.truncated
        status = "OK"
      })
    } catch {
      $started.Stop()
      $samples.Add([pscustomobject]@{
        report = $report.code
        iteration = $iteration
        clientMs = $started.ElapsedMilliseconds
        serverMs = $null
        rows = 0
        truncated = $false
        status = "FAILED"
      })
    }
  }
}

$successful = @($samples | Where-Object status -eq "OK" | Sort-Object serverMs)
if ($successful.Count -eq 0) { throw "Every analytical query failed." }
$p95Index = [Math]::Min($successful.Count - 1, [Math]::Ceiling($successful.Count * 0.95) - 1)
$p95 = [long]$successful[$p95Index].serverMs
$summary = [pscustomobject]@{
  sourceRows = $sourceRows
  reports = $reports.Count
  executions = $samples.Count
  failures = @($samples | Where-Object status -eq "FAILED").Count
  truncated = @($samples | Where-Object truncated).Count
  p95ServerMs = $p95
  targetMs = $P95TargetMs
  passed = ($p95 -le $P95TargetMs -and @($samples | Where-Object status -eq "FAILED").Count -eq 0)
}
$summary | Format-List
$samples | Group-Object report | ForEach-Object {
  [pscustomobject]@{
    report = $_.Name
    executions = $_.Count
    averageServerMs = [Math]::Round(($_.Group | Measure-Object serverMs -Average).Average, 1)
    maximumServerMs = ($_.Group | Measure-Object serverMs -Maximum).Maximum
    failures = @($_.Group | Where-Object status -eq "FAILED").Count
  }
} | Format-Table -AutoSize

if (-not $summary.passed) { throw "Reporting scale validation failed its $P95TargetMs ms P95 target or recorded failures." }
