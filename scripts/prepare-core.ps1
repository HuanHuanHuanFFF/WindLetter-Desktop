[CmdletBinding()]
param(
    [Parameter()]
    [string]$CorePath = (Join-Path $PSScriptRoot '..\..\WindLetter'),

    [Parameter()]
    [string]$JdkHome = $env:JAVA_HOME,

    [Parameter()]
    [string]$MavenCommand = 'mvn.cmd'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$desktopRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$baselinePath = Join-Path $desktopRoot 'core-baseline.properties'
$repositoryPath = Join-Path $desktopRoot '.mvn\repository'

function Read-Properties([string]$Path) {
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }
        $separator = $trimmed.IndexOf('=')
        if ($separator -le 0) {
            throw "Invalid baseline property: $line"
        }
        $properties[$trimmed.Substring(0, $separator).Trim()] =
            $trimmed.Substring($separator + 1).Trim()
    }
    return $properties
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory) {
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

$baseline = Read-Properties $baselinePath
$resolvedCore = (Resolve-Path -LiteralPath $CorePath).Path

$actualRemote = (& git -c "safe.directory=$resolvedCore" -C $resolvedCore remote get-url origin).Trim()
if ($LASTEXITCODE -ne 0 -or $actualRemote -ne $baseline['core.repository']) {
    throw "Core repository origin does not match the pinned baseline."
}

$actualCommit = (& git -c "safe.directory=$resolvedCore" -C $resolvedCore rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $baseline['core.commit']) {
    throw "Core repository HEAD does not match the pinned baseline."
}

$dirty = (& git -c "safe.directory=$resolvedCore" -C $resolvedCore status --porcelain)
if ($LASTEXITCODE -ne 0 -or $dirty) {
    throw "Core repository must be clean before preparing dependencies."
}

[xml]$rootPom = Get-Content -LiteralPath (Join-Path $resolvedCore 'pom.xml') -Encoding UTF8
$project = $rootPom.project
if ($project.groupId -ne $baseline['core.groupId'] -or
    $project.version -ne $baseline['core.version']) {
    throw "Core Maven coordinates do not match the pinned baseline."
}

[xml]$apiPom = Get-Content -LiteralPath (Join-Path $resolvedCore 'windletter-api\pom.xml') -Encoding UTF8
if ($apiPom.project.artifactId -ne $baseline['core.artifactId']) {
    throw "Core API artifact does not match the pinned baseline."
}

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw 'JdkHome or JAVA_HOME must identify a Java 17 JDK.'
}
$resolvedJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$javaExecutable = Join-Path $resolvedJdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw 'JdkHome does not contain bin\java.exe.'
}
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $javaOutput = & $javaExecutable -version 2>&1
    $javaExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorAction
}
if ($javaExitCode -ne 0) {
    throw 'Unable to execute Java from JdkHome.'
}
$javaVersion = ($javaOutput | Select-Object -First 1).ToString()
if ($javaVersion -notmatch 'version "17[\.]') {
    throw 'WindLetter Desktop phase 1 requires Java 17.'
}

New-Item -ItemType Directory -Path $repositoryPath -Force | Out-Null
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $resolvedJdk
    Invoke-Checked $MavenCommand @(
        '-q',
        "-Dmaven.repo.local=$repositoryPath",
        'clean',
        'install'
    ) $resolvedCore
} finally {
    $env:JAVA_HOME = $previousJavaHome
}

Write-Host "Prepared WindLetter core $actualCommit in the project-local Maven repository."
