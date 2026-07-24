[CmdletBinding()]
param(
    [Parameter()]
    [string]$Installer = '',

    [Parameter()]
    [int]$TimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$desktopRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($Installer)) {
    $Installer = Join-Path $desktopRoot `
        'dist\msi\WindLetter-0.1.0.msi'
}
$installerPath = (Resolve-Path -LiteralPath $Installer).Path
if ([IO.Path]::GetExtension($installerPath) -ne '.msi') {
    throw 'Installer smoke currently accepts MSI packages only.'
}

$targetRoot = Join-Path $desktopRoot 'target'
$logRoot = Join-Path $targetRoot 'installer-smoke'
$installLog = Join-Path $logRoot 'install.log'
$uninstallLog = Join-Path $logRoot 'uninstall.log'
$smokeSession = Join-Path $logRoot 'preserved-user-data'
$smokeVault = Join-Path $smokeSession 'appdata\WindLetter\vault.wlv'
$installRoot = Join-Path $env:LOCALAPPDATA 'WindLetter'
$executable = Join-Path $installRoot 'WindLetter.exe'
$productRegistry =
    'HKCU:\Software\HuanHuanHuanFFF\WindLetter\0.1.0'
$installedBySmoke = $false

function Invoke-Msi(
    [string]$Operation,
    [string]$LogPath
) {
    $process = Start-Process `
        -FilePath 'msiexec.exe' `
        -ArgumentList @(
            $Operation,
            ('"' + $installerPath + '"'),
            '/qn',
            '/norestart',
            '/l*v',
            ('"' + $LogPath + '"')
        ) `
        -Wait `
        -PassThru
    if ($process.ExitCode -ne 0) {
        throw "msiexec $Operation failed with code $($process.ExitCode)."
    }
}

if (Test-Path -LiteralPath $installRoot) {
    throw "Refusing to replace an existing install: $installRoot"
}
if (Test-Path -LiteralPath $productRegistry) {
    throw 'Refusing to replace an existing WindLetter product registration.'
}
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
if (Test-Path -LiteralPath $smokeSession) {
    Remove-Item -LiteralPath $smokeSession -Recurse -Force
}

try {
    try {
        Invoke-Msi '/i' $installLog
        $installedBySmoke = $true
        if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
            throw 'The installer did not create WindLetter.exe.'
        }

        & powershell `
            -NoProfile `
            -ExecutionPolicy Bypass `
            -File (Join-Path $PSScriptRoot 'smoke-packaged-app.ps1') `
            -AppImage $installRoot `
            -TimeoutSeconds $TimeoutSeconds `
            -SmokeDataRoot $smokeSession `
            -KeepSmokeData
        if ($LASTEXITCODE -ne 0) {
            throw 'The installed application smoke failed.'
        }
        if (-not (Test-Path -LiteralPath $smokeVault -PathType Leaf)) {
            throw 'The installed application did not persist its test vault.'
        }
    } finally {
        if ($installedBySmoke) {
            Invoke-Msi '/x' $uninstallLog
        }
    }

    if (Test-Path -LiteralPath $installRoot) {
        throw 'The uninstaller left the application directory behind.'
    }
    if (Test-Path -LiteralPath $productRegistry) {
        throw 'The uninstaller left product registration behind.'
    }
    if (-not (Test-Path -LiteralPath $smokeVault -PathType Leaf)) {
        throw 'Uninstall unexpectedly removed the encrypted user vault.'
    }
} finally {
    if (Test-Path -LiteralPath $smokeSession) {
        Remove-Item -LiteralPath $smokeSession -Recurse -Force
    }
}

Write-Host (
    'Installer smoke passed: install, real packaged flow, restart, ' +
    'unlock, uninstall, and encrypted vault preservation succeeded.'
)
