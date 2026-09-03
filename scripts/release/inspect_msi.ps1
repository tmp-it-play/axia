param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $MsiPath).Path, 0)
$summary = $database.SummaryInformation(0)
$template = $summary.Property(7)
if (-not $template.StartsWith('x64;')) {
    throw 'The MSI package does not declare the required x64 architecture.'
}
$view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductVersion'")
$view.Execute()
$record = $view.Fetch()
if ($null -eq $record -or $record.StringData(1) -ne $Version) {
    throw 'The MSI ProductVersion does not match the requested release.'
}
$view.Close()
Write-Output 'MSI architecture and product version verified.'
