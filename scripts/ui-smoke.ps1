[CmdletBinding()]
param(
    [Parameter()]
    [int]$ExistingJavaProcessId = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$desktopRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$windowTitle = '風笺 · WindLetter'
$launchProcess = $null
$window = $null

function Wait-ForElement(
    [System.Windows.Automation.AutomationElement]$Root,
    [System.Windows.Automation.TreeScope]$Scope,
    [System.Windows.Automation.Condition]$Condition,
    [int]$TimeoutSeconds
) {
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $element = $Root.FindFirst($Scope, $Condition)
        if ($null -ne $element) {
            return $element
        }
        Start-Sleep -Milliseconds 200
    }
    return $null
}

try {
    if ($ExistingJavaProcessId -eq 0) {
        $launchProcess = Start-Process `
            -FilePath (Join-Path $desktopRoot 'mvnw.cmd') `
            -ArgumentList '-q', 'javafx:run' `
            -WorkingDirectory $desktopRoot `
            -PassThru
    }

    $nameCondition = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::NameProperty,
        $windowTitle
    )
    if ($ExistingJavaProcessId -ne 0) {
        $processCondition = [System.Windows.Automation.PropertyCondition]::new(
            [System.Windows.Automation.AutomationElement]::ProcessIdProperty,
            $ExistingJavaProcessId
        )
        $windowCondition = [System.Windows.Automation.AndCondition]::new(
            [System.Windows.Automation.Condition[]]@($nameCondition, $processCondition)
        )
    } else {
        $windowCondition = $nameCondition
    }

    $window = Wait-ForElement `
        ([System.Windows.Automation.AutomationElement]::RootElement) `
        ([System.Windows.Automation.TreeScope]::Children) `
        $windowCondition `
        30
    if ($null -eq $window) {
        throw 'WindLetter window did not appear within 30 seconds.'
    }

    $buttonName = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::NameProperty,
        '运行真实收发自检'
    )
    $buttonType = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Button
    )
    $buttonCondition = [System.Windows.Automation.AndCondition]::new(
        [System.Windows.Automation.Condition[]]@($buttonName, $buttonType)
    )
    $button = Wait-ForElement `
        $window `
        ([System.Windows.Automation.TreeScope]::Descendants) `
        $buttonCondition `
        10
    if ($null -eq $button) {
        throw 'Self-test button was not available.'
    }

    $invoke = [System.Windows.Automation.InvokePattern]$button.GetCurrentPattern(
        [System.Windows.Automation.InvokePattern]::Pattern
    )
    $invoke.Invoke()

    $successCondition = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::NameProperty,
        '真实收发成功'
    )
    $success = Wait-ForElement `
        $window `
        ([System.Windows.Automation.TreeScope]::Descendants) `
        $successCondition `
        30
    if ($null -eq $success) {
        throw 'The real send/receive self-test did not reach the success state.'
    }

    Write-Host 'UI smoke passed: window launched and real send/receive reached success.'
} finally {
    if ($null -ne $window) {
        try {
            $windowPattern = [System.Windows.Automation.WindowPattern]$window.GetCurrentPattern(
                [System.Windows.Automation.WindowPattern]::Pattern
            )
            $windowPattern.Close()
        } catch {
            # Closing is best-effort after the smoke assertion; Maven is checked below.
        }
    }
    if ($null -ne $launchProcess) {
        if (-not $launchProcess.WaitForExit(10000)) {
            Stop-Process -Id $launchProcess.Id -ErrorAction SilentlyContinue
        }
    }
}
