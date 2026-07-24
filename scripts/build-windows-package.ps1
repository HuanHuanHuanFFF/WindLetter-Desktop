[CmdletBinding()]
param(
    [Parameter()]
    [ValidateSet('app-image', 'exe', 'msi')]
    [string]$Type = 'app-image',

    [Parameter()]
    [string]$JdkHome = $env:JAVA_HOME,

    [Parameter()]
    [string]$CorePath = '',

    [Parameter()]
    [string]$WixBin = '',

    [Parameter()]
    [switch]$SkipCorePreparation
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$desktopRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($CorePath)) {
    $CorePath = Join-Path $desktopRoot '..\WindLetter'
}
$targetRoot = Join-Path $desktopRoot 'target'
$distRoot = Join-Path $desktopRoot 'dist'
$inputPath = Join-Path $targetRoot 'package-input'
$destinationPath = Join-Path $distRoot $Type
$packageTemp = Join-Path $targetRoot 'jpackage-temp'
$mainJar = Join-Path $targetRoot 'windletter-desktop.jar'
$packagedMainJar = Join-Path $inputPath 'windletter-desktop.jar'
$pomPath = Join-Path $desktopRoot 'pom.xml'
$baselinePath = Join-Path $desktopRoot 'core-baseline.properties'
$iconPath = Join-Path $desktopRoot 'assets\windletter.ico'
$maven = Join-Path $desktopRoot 'mvnw.cmd'
$description = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String(
        '6aKo56y6IMK3IFdpbmRMZXR0ZXIg5a6J5YWo5raI5oGv5qGM6Z2i5bqU55So'
    )
)

function Read-Properties([string]$Path) {
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            throw "Invalid property in ${Path}: $line"
        }
        $properties[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }
    return $properties
}

function Assert-SafeTarget([string]$Path) {
    $resolvedTarget = [IO.Path]::GetFullPath($targetRoot) +
        [IO.Path]::DirectorySeparatorChar
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    if (-not $resolvedPath.StartsWith(
        $resolvedTarget,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Refusing to modify a path outside target: $resolvedPath"
    }
}

function Assert-SafeDist([string]$Path) {
    $resolvedDist = [IO.Path]::GetFullPath($distRoot) +
        [IO.Path]::DirectorySeparatorChar
    $resolvedPath = [IO.Path]::GetFullPath($Path)
    if (-not $resolvedPath.StartsWith(
        $resolvedDist,
        [StringComparison]::OrdinalIgnoreCase
    )) {
        throw "Refusing to modify a path outside dist: $resolvedPath"
    }
}

function Invoke-Checked(
    [string]$Executable,
    [string[]]$Arguments,
    [string]$WorkingDirectory
) {
    Push-Location $WorkingDirectory
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Executable exited with code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

[xml]$pom = Get-Content -LiteralPath $pomPath -Encoding UTF8
$version = $pom.project.properties.'windletter.desktop.version'
$configuredCoreCommit = $pom.project.properties.'windletter.core.commit'
$baseline = Read-Properties $baselinePath
if ([string]::IsNullOrWhiteSpace($version)) {
    throw 'pom.xml does not define windletter.desktop.version.'
}
if ($configuredCoreCommit -ne $baseline['core.commit']) {
    throw 'Build metadata and core-baseline.properties disagree.'
}
if (-not (Test-Path -LiteralPath $iconPath -PathType Leaf)) {
    throw 'The WindLetter Windows icon is missing.'
}

$resolvedJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$java = Join-Path $resolvedJdk 'bin\java.exe'
$jpackage = Join-Path $resolvedJdk 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
    -not (Test-Path -LiteralPath $jpackage -PathType Leaf)) {
    throw 'JdkHome must contain Java 17 java.exe and jpackage.exe.'
}
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $javaOutput = & $java -version 2>&1
    $javaExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
$javaVersion = ($javaOutput | Select-Object -First 1).ToString()
if ($javaExitCode -ne 0 -or $javaVersion -notmatch 'version "17[\.]') {
    throw 'WindLetter Desktop packages must be built with Java 17.'
}

if (-not $SkipCorePreparation) {
    & (Join-Path $PSScriptRoot 'prepare-core.ps1') `
        -CorePath $CorePath `
        -JdkHome $resolvedJdk
    if ($LASTEXITCODE -ne 0) {
        throw 'Pinned core preparation failed.'
    }
}

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $resolvedJdk
    Invoke-Checked $maven @('-q', 'clean', 'verify') $desktopRoot
} finally {
    $env:JAVA_HOME = $previousJavaHome
}

if (-not (Test-Path -LiteralPath $mainJar -PathType Leaf)) {
    throw 'Maven did not create the desktop application JAR.'
}
if (-not (Test-Path -LiteralPath $inputPath -PathType Container)) {
    throw 'Maven did not collect the runtime dependencies.'
}
Copy-Item -LiteralPath $mainJar -Destination $packagedMainJar -Force

Assert-SafeDist $destinationPath
if (Test-Path -LiteralPath $destinationPath) {
    Remove-Item -LiteralPath $destinationPath -Recurse -Force
}
New-Item -ItemType Directory -Path $destinationPath | Out-Null

$arguments = @(
    '--type', $Type,
    '--input', $inputPath,
    '--dest', $destinationPath,
    '--name', 'WindLetter',
    '--app-version', $version,
    '--vendor', 'HuanHuanHuanFFF',
    '--description', $description,
    '--copyright', 'Copyright 2026 HuanHuanHuanFFF',
    '--icon', $iconPath,
    '--main-jar', 'windletter-desktop.jar',
    '--main-class', 'com.windletter.desktop.Launcher',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--verbose'
)
if ($Type -ne 'app-image') {
    $localWix = Join-Path $desktopRoot '.local-tools\wix314'
    if ([string]::IsNullOrWhiteSpace($WixBin) -and
        (Test-Path -LiteralPath $localWix -PathType Container)) {
        $WixBin = $localWix
    }
    if (-not [string]::IsNullOrWhiteSpace($WixBin)) {
        $resolvedWixBin = (Resolve-Path -LiteralPath $WixBin).Path
        foreach ($tool in @('candle.exe', 'light.exe')) {
            if (-not (Test-Path -LiteralPath `
                (Join-Path $resolvedWixBin $tool) -PathType Leaf)) {
                throw "WixBin does not contain $tool."
            }
        }
    } else {
        $candle = Get-Command 'candle.exe' -ErrorAction SilentlyContinue
        $light = Get-Command 'light.exe' -ErrorAction SilentlyContinue
        if ($null -eq $candle -or $null -eq $light) {
            throw (
                'WiX 3.x is required for Windows installers. ' +
                'Pass -WixBin or put candle.exe and light.exe on PATH.'
            )
        }
        $resolvedWixBin = Split-Path -Parent $candle.Source
    }
    $arguments += @(
        '--win-per-user-install',
        '--win-dir-chooser',
        '--win-menu',
        '--win-menu-group', 'WindLetter',
        '--win-shortcut-prompt',
        '--win-upgrade-uuid',
        'd6e3d392-826c-4a4f-b4db-26f2c832bc34'
    )
}
$previousPath = $env:PATH
$previousTemp = $env:TEMP
$previousTmp = $env:TMP
try {
    Assert-SafeTarget $packageTemp
    if (Test-Path -LiteralPath $packageTemp) {
        Remove-Item -LiteralPath $packageTemp -Recurse -Force
    }
    New-Item -ItemType Directory -Path $packageTemp | Out-Null
    $env:TEMP = $packageTemp
    $env:TMP = $packageTemp
    if ($Type -ne 'app-image') {
        $env:PATH = $resolvedWixBin + [IO.Path]::PathSeparator + $env:PATH
    }
    Invoke-Checked $jpackage $arguments $desktopRoot
} finally {
    $env:PATH = $previousPath
    $env:TEMP = $previousTemp
    $env:TMP = $previousTmp
}

Write-Host "Created WindLetter $version $Type in $destinationPath"
