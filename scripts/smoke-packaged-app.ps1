[CmdletBinding()]
param(
    [Parameter()]
    [string]$AppImage = '',

    [Parameter()]
    [int]$TimeoutSeconds = 60,

    [Parameter()]
    [switch]$KeepSmokeData
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$desktopRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($AppImage)) {
    $AppImage = Join-Path $desktopRoot 'target\dist\WindLetter'
}
$targetRoot = Join-Path $desktopRoot 'target'
$smokeRoot = Join-Path $targetRoot 'packaged-smoke'
$sessionRoot = Join-Path $smokeRoot ([guid]::NewGuid().ToString('N'))
$appData = Join-Path $sessionRoot 'appdata'
$executable = Join-Path (Resolve-Path -LiteralPath $AppImage).Path `
    'WindLetter.exe'
$process = $null
$window = $null
$previousAppData = $env:APPDATA
$windowTitle = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String('6aKo56y6IMK3IFdpbmRMZXR0ZXI=')
)
$firstRunHeading = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String(
        '5Yib5bu65L2g55qE6aKo56y65L+d6Zmp5bqT'
    )
)
$createAndUnlock = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String('5Yib5bu65bm26Kej6ZSB')
)
$selfTestTab = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String('5Y2P6K6u6Ieq5qOA')
)
$runSelfTest = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String('6L+Q6KGM55yf5a6e5pS25Y+R6Ieq5qOA')
)
$selfTestSuccess = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String(
        '55yf5a6e5Y2P6K6u6Ieq5qOA6YCa6L+H44CC'
    )
)
$showPassword = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String('5pi+56S65a+G56CB')
)

function Wait-ForElement(
    [System.Windows.Automation.AutomationElement]$Root,
    [System.Windows.Automation.TreeScope]$Scope,
    [System.Windows.Automation.Condition]$Condition,
    [int]$Seconds
) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $element = $Root.FindFirst($Scope, $Condition)
        if ($null -ne $element) {
            return $element
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    return $null
}

function Named-Condition(
    [string]$Name,
    [System.Windows.Automation.ControlType]$ControlType
) {
    $nameCondition =
        [System.Windows.Automation.PropertyCondition]::new(
            [System.Windows.Automation.AutomationElement]::NameProperty,
            $Name
        )
    $typeCondition =
        [System.Windows.Automation.PropertyCondition]::new(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            $ControlType
        )
    return [System.Windows.Automation.AndCondition]::new(
        [System.Windows.Automation.Condition[]]@(
            $nameCondition,
            $typeCondition
        )
    )
}

function Invoke-Button(
    [System.Windows.Automation.AutomationElement]$Root,
    [string]$Name
) {
    $button = Wait-ForElement `
        $Root `
        ([System.Windows.Automation.TreeScope]::Descendants) `
        (Named-Condition $Name ([System.Windows.Automation.ControlType]::Button)) `
        $TimeoutSeconds
    if ($null -eq $button) {
        throw "Button was not available: $Name"
    }
    $invoke = [System.Windows.Automation.InvokePattern]$button.GetCurrentPattern(
        [System.Windows.Automation.InvokePattern]::Pattern
    )
    $invoke.Invoke()
}

function Wait-ForSelectionItem(
    [System.Windows.Automation.AutomationElement]$Root,
    [string]$Name,
    [int]$Seconds
) {
    $condition =
        [System.Windows.Automation.PropertyCondition]::new(
            [System.Windows.Automation.AutomationElement]::NameProperty,
            $Name
        )
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $candidates = $Root.FindAll(
            [System.Windows.Automation.TreeScope]::Descendants,
            $condition
        )
        for ($index = 0; $index -lt $candidates.Count; $index++) {
            $candidate = $candidates.Item($index)
            $patternObject = $null
            if ($candidate.TryGetCurrentPattern(
                [System.Windows.Automation.SelectionItemPattern]::Pattern,
                [ref]$patternObject
            )) {
                return $patternObject
            }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    return $null
}

try {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw 'The packaged WindLetter executable is missing.'
    }
    New-Item -ItemType Directory -Path $appData -Force | Out-Null
    $env:APPDATA = $appData
    $process = Start-Process -FilePath $executable -PassThru

    $window = Wait-ForElement `
        ([System.Windows.Automation.AutomationElement]::RootElement) `
        ([System.Windows.Automation.TreeScope]::Children) `
        (Named-Condition `
            $windowTitle `
            ([System.Windows.Automation.ControlType]::Window)) `
        $TimeoutSeconds
    if ($null -eq $window) {
        throw 'The packaged WindLetter window did not appear.'
    }
    $heading = Wait-ForElement `
        $window `
        ([System.Windows.Automation.TreeScope]::Descendants) `
        (Named-Condition `
            $firstRunHeading `
            ([System.Windows.Automation.ControlType]::Text)) `
        10
    if ($null -eq $heading) {
        throw 'The isolated first-run page was not shown.'
    }

    $showButtons = $window.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        (Named-Condition `
            $showPassword `
            ([System.Windows.Automation.ControlType]::Button))
    )
    if ($showButtons.Count -lt 2) {
        throw 'The two password reveal buttons were not available.'
    }
    for ($index = 0; $index -lt 2; $index++) {
        $reveal = $showButtons.Item($index)
        $toggle =
            [System.Windows.Automation.TogglePattern]$reveal.GetCurrentPattern(
                [System.Windows.Automation.TogglePattern]::Pattern
            )
        $toggle.Toggle()
    }
    Start-Sleep -Milliseconds 500

    $editCondition =
        [System.Windows.Automation.PropertyCondition]::new(
            [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
            [System.Windows.Automation.ControlType]::Edit
        )
    $allEdits = $window.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        $editCondition
    )
    $edits = @()
    for ($index = 0; $index -lt $allEdits.Count; $index++) {
        $candidate = $allEdits.Item($index)
        if (-not $candidate.Current.IsPassword -and
            -not $candidate.Current.IsOffscreen) {
            $edits += $candidate
        }
    }
    if ($edits.Count -lt 2) {
        throw 'The two new-vault password fields were not available.'
    }
    $testPassword = 'windlettersmoke2026'
    for ($index = 0; $index -lt 2; $index++) {
        $edit = $edits[$index]
        $patternObject = $null
        if (-not $edit.TryGetCurrentPattern(
            [System.Windows.Automation.ValuePattern]::Pattern,
            [ref]$patternObject
        )) {
            throw "Visible password field $index is not writable."
        }
        $value = [System.Windows.Automation.ValuePattern]$patternObject
        $value.SetValue($testPassword)
        if ($value.Current.Value -ne $testPassword) {
            throw "Password field $index did not retain the smoke value."
        }
    }
    Invoke-Button $window $createAndUnlock

    $selection = Wait-ForSelectionItem `
        $window `
        $selfTestTab `
        $TimeoutSeconds
    if ($null -eq $selection) {
        throw 'The workspace self-test tab was not selectable.'
    }
    $selection.Select()
    Invoke-Button $window $runSelfTest

    $success = Wait-ForElement `
        $window `
        ([System.Windows.Automation.TreeScope]::Descendants) `
        (Named-Condition `
            $selfTestSuccess `
            ([System.Windows.Automation.ControlType]::Text)) `
        $TimeoutSeconds
    if ($null -eq $success) {
        throw 'The packaged real send/receive self-test did not pass.'
    }
    $vaultPath = Join-Path $appData 'WindLetter\vault.wlv'
    if (-not (Test-Path -LiteralPath $vaultPath -PathType Leaf)) {
        throw 'The isolated encrypted vault was not persisted.'
    }

    Write-Host (
        'Packaged UI smoke passed: isolated vault creation, ' +
        'real protocol send/receive, and persistence succeeded.'
    )
} finally {
    if ($null -ne $window) {
        try {
            $windowPattern =
                [System.Windows.Automation.WindowPattern]$window
                    .GetCurrentPattern(
                        [System.Windows.Automation.WindowPattern]::Pattern
                    )
            $windowPattern.Close()
        } catch {
            # Process cleanup below is the final fallback.
        }
    }
    if ($null -ne $process -and -not $process.HasExited) {
        if (-not $process.WaitForExit(10000)) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
    }
    $env:APPDATA = $previousAppData
    if (-not $KeepSmokeData -and (Test-Path -LiteralPath $sessionRoot)) {
        $resolvedTarget = [IO.Path]::GetFullPath($targetRoot) +
            [IO.Path]::DirectorySeparatorChar
        $resolvedSession = [IO.Path]::GetFullPath($sessionRoot)
        if (-not $resolvedSession.StartsWith(
            $resolvedTarget,
            [StringComparison]::OrdinalIgnoreCase
        )) {
            throw "Refusing to remove smoke data outside target."
        }
        Remove-Item -LiteralPath $sessionRoot -Recurse -Force
    }
}
