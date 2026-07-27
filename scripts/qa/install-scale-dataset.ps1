param(
    [string]$Database = "AxiomCrmdb_Dev",
    [string]$HostName = "localhost",
    [int]$Port = 5432,
    [string]$Username = "axiom_app",
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [int]$MinimumFreeGbForPhysicalSeed = 40,
    [switch]$AllowPhysicalBusinessSeed
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$fixture = Join-Path $PSScriptRoot "scale-dataset.sql"

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    throw "PostgreSQL client was not found at $PsqlPath. Pass -PsqlPath explicitly."
}
if (-not $env:PGPASSWORD) {
    throw "Set PGPASSWORD for the dedicated QA database role before running this script."
}

$databaseDrive = (Split-Path -Qualifier $env:ProgramFiles)
$freeGb = [math]::Round((Get-PSDrive $databaseDrive.TrimEnd(':')).Free / 1GB, 2)
if ($AllowPhysicalBusinessSeed -and $freeGb -lt $MinimumFreeGbForPhysicalSeed) {
    throw "Physical multi-million-row seeding refused: $databaseDrive has $freeGb GB free; at least $MinimumFreeGbForPhysicalSeed GB is required. Point PostgreSQL at a dedicated QA volume."
}

Write-Host "Installing compact logical-million QA dataset in $Database..."
& $PsqlPath -X -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $Username -d $Database -f $fixture
if ($LASTEXITCODE -ne 0) { throw "Scale fixture installation failed with exit code $LASTEXITCODE." }

$proof = @"
select count(*) as transactional_screens,
       min(target_rows) as minimum_rows_per_screen,
       max(target_rows) as maximum_rows_per_screen
from qa.screen_scale_target where data_class='TRANSACTION';
select count(*) as master_edge_cases from qa.master_edge_case_catalog;
"@
& $PsqlPath -X -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $Username -d $Database -c $proof
if ($LASTEXITCODE -ne 0) { throw "Scale fixture verification failed with exit code $LASTEXITCODE." }

Write-Host "Scale dataset ready. It represents 1,000,000 deterministic rows per transactional screen without duplicating business records."

