param(
    [Parameter(Mandatory=$true)][string]$Action,
    [Parameter(Mandatory=$true)][string]$PayloadBase64
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName WindowsBase

$payloadJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($PayloadBase64))
$payload = $payloadJson | ConvertFrom-Json

function To-ControlTypeName($element) {
    try {
        $name = $element.Current.ControlType.ProgrammaticName
        if ($name -and $name.StartsWith("ControlType.")) {
            return $name.Substring("ControlType.".Length)
        }
        return $name
    } catch { return "" }
}

function Get-Patterns($element) {
    $names = New-Object System.Collections.Generic.List[string]
    $checks = @(
        @("Invoke", [System.Windows.Automation.InvokePattern]::Pattern),
        @("Value", [System.Windows.Automation.ValuePattern]::Pattern),
        @("Text", [System.Windows.Automation.TextPattern]::Pattern),
        @("SelectionItem", [System.Windows.Automation.SelectionItemPattern]::Pattern),
        @("Toggle", [System.Windows.Automation.TogglePattern]::Pattern),
        @("ExpandCollapse", [System.Windows.Automation.ExpandCollapsePattern]::Pattern),
        @("Scroll", [System.Windows.Automation.ScrollPattern]::Pattern),
        @("ScrollItem", [System.Windows.Automation.ScrollItemPattern]::Pattern)
    )
    foreach ($check in $checks) {
        $pattern = $null
        try {
            if ($element.TryGetCurrentPattern($check[1], [ref]$pattern)) {
                $names.Add($check[0])
            }
        } catch {}
    }
    return @($names)
}

function Get-TopWindow($windowRef) {
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $children = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )

    if ($windowRef) {
        $needle = [string]$windowRef
        $handle = 0
        if ($needle.StartsWith("0x", [StringComparison]::OrdinalIgnoreCase)) {
            try { $handle = [Convert]::ToInt32($needle.Substring(2), 16) } catch {}
        }
        foreach ($child in $children) {
            try {
                if ($handle -ne 0 -and $child.Current.NativeWindowHandle -eq $handle) {
                    return $child
                }
                if ($child.Current.Name -and $child.Current.Name.IndexOf($needle, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    return $child
                }
            } catch {}
        }
        throw "Window matching '$windowRef' was not found in UI Automation."
    }

    $focused = [System.Windows.Automation.AutomationElement]::FocusedElement
    if ($null -eq $focused) { return $root }

    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $current = $focused
    while ($true) {
        $parent = $walker.GetParent($current)
        if ($null -eq $parent -or $parent -eq $root) { return $current }
        $current = $parent
    }
}

function New-ElementToken($element, $rootRef, $occurrence) {
    $token = [ordered]@{
        window = [string]$rootRef
        automation_id = [string]$element.Current.AutomationId
        name = [string]$element.Current.Name
        control_type = (To-ControlTypeName $element)
        class_name = [string]$element.Current.ClassName
        native_handle = [int]$element.Current.NativeWindowHandle
        occurrence = [int]$occurrence
    }
    $json = $token | ConvertTo-Json -Compress -Depth 4
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($json))
}

function To-ElementObject($element, $rootRef, $depth, $occurrence) {
    $rect = $element.Current.BoundingRectangle
    [ordered]@{
        element_id = (New-ElementToken $element $rootRef $occurrence)
        name = [string]$element.Current.Name
        automation_id = [string]$element.Current.AutomationId
        control_type = (To-ControlTypeName $element)
        class_name = [string]$element.Current.ClassName
        framework_id = [string]$element.Current.FrameworkId
        process_id = [int]$element.Current.ProcessId
        native_handle = [int]$element.Current.NativeWindowHandle
        left = [double]$rect.Left
        top = [double]$rect.Top
        width = [double]$rect.Width
        height = [double]$rect.Height
        enabled = [bool]$element.Current.IsEnabled
        offscreen = [bool]$element.Current.IsOffscreen
        focusable = [bool]$element.Current.IsKeyboardFocusable
        has_keyboard_focus = [bool]$element.Current.HasKeyboardFocus
        depth = [int]$depth
        patterns = @(Get-Patterns $element)
    }
}

function Matches-Query($element, $query) {
    try {
        if ($query.automation_id) {
            if ([string]$element.Current.AutomationId -ne [string]$query.automation_id) { return $false }
        }
        if ($query.control_type) {
            if ((To-ControlTypeName $element) -ne [string]$query.control_type) { return $false }
        }
        if ($query.class_name) {
            if ([string]$element.Current.ClassName -ne [string]$query.class_name) { return $false }
        }
        if ($query.name) {
            $actual = [string]$element.Current.Name
            if ($query.contains) {
                if ($actual.IndexOf([string]$query.name, [StringComparison]::OrdinalIgnoreCase) -lt 0) { return $false }
            } else {
                if (-not [string]::Equals($actual, [string]$query.name, [StringComparison]::OrdinalIgnoreCase)) { return $false }
            }
        }
        return $true
    } catch { return $false }
}

function Walk-Elements($root, $rootRef, $maxDepth, $maxResults, $query) {
    $results = New-Object System.Collections.Generic.List[object]
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $occurrences = @{}

    function Visit($node, $depth) {
        if ($results.Count -ge $maxResults) { return }
        if ($depth -gt $maxDepth) { return }

        if ($depth -gt 0 -and (Matches-Query $node $query)) {
            $key = "{0}|{1}|{2}|{3}" -f $node.Current.AutomationId, $node.Current.Name, (To-ControlTypeName $node), $node.Current.ClassName
            if (-not $occurrences.ContainsKey($key)) { $occurrences[$key] = 0 }
            $occ = [int]$occurrences[$key]
            $occurrences[$key] = $occ + 1
            $results.Add((To-ElementObject $node $rootRef $depth $occ))
            if ($results.Count -ge $maxResults) { return }
        }

        if ($depth -ge $maxDepth) { return }
        $child = $walker.GetFirstChild($node)
        while ($null -ne $child) {
            Visit $child ($depth + 1)
            if ($results.Count -ge $maxResults) { return }
            $child = $walker.GetNextSibling($child)
        }
    }

    Visit $root 0
    return @($results)
}

function Decode-ElementToken($token) {
    $json = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string]$token))
    return $json | ConvertFrom-Json
}

function Resolve-Element($token) {
    $selector = Decode-ElementToken $token
    $root = Get-TopWindow $selector.window
    $query = [pscustomobject]@{
        automation_id = $selector.automation_id
        name = $selector.name
        control_type = $selector.control_type
        class_name = $selector.class_name
        contains = $false
    }

    if ($selector.native_handle -and [int]$selector.native_handle -ne 0) {
        $condition = New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NativeWindowHandleProperty,
            [int]$selector.native_handle
        )
        try {
            $byHandle = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
            if ($null -ne $byHandle) { return $byHandle }
        } catch {}
    }

    $matches = Walk-Elements $root $selector.window 64 512 $query
    $want = [int]$selector.occurrence
    if ($want -lt 0) { $want = 0 }
    if ($matches.Count -le $want) { throw "UI element is no longer available." }

    $targetToken = $matches[$want].element_id
    $targetSelector = Decode-ElementToken $targetToken

    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $found = New-Object System.Collections.Generic.List[object]
    function FindActual($node) {
        if ($found.Count -gt $want) { return }
        if (Matches-Query $node $query) { $found.Add($node) }
        $child = $walker.GetFirstChild($node)
        while ($null -ne $child) {
            FindActual $child
            if ($found.Count -gt $want) { return }
            $child = $walker.GetNextSibling($child)
        }
    }
    FindActual $root
    if ($found.Count -le $want) { throw "UI element is no longer available." }
    return $found[$want]
}

function Emit($value) {
    $value | ConvertTo-Json -Compress -Depth 8
}

switch ($Action) {
    "snapshot" {
        $root = Get-TopWindow $payload.window
        $maxDepth = if ($payload.max_depth) { [Math]::Min([Math]::Max([int]$payload.max_depth, 1), 20) } else { 8 }
        $maxResults = if ($payload.max_results) { [Math]::Min([Math]::Max([int]$payload.max_results, 1), 1000) } else { 250 }
        $query = [pscustomobject]@{}
        $elements = Walk-Elements $root $payload.window $maxDepth $maxResults $query
        Emit ([ordered]@{
            window = [string]$payload.window
            elements = @($elements)
        })
    }

    "find" {
        $root = Get-TopWindow $payload.window
        $maxDepth = if ($payload.max_depth) { [Math]::Min([Math]::Max([int]$payload.max_depth, 1), 32) } else { 16 }
        $maxResults = if ($payload.max_results) { [Math]::Min([Math]::Max([int]$payload.max_results, 1), 200) } else { 20 }
        $elements = Walk-Elements $root $payload.window $maxDepth $maxResults $payload
        Emit @($elements)
    }

    "invoke" {
        $element = Resolve-Element $payload.element_id
        $pattern = $null
        if (-not $element.TryGetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern, [ref]$pattern)) {
            throw "Element does not support InvokePattern."
        }
        $pattern.Invoke()
        Emit (To-ElementObject $element "" 0 0)
    }

    "focus" {
        $element = Resolve-Element $payload.element_id
        $element.SetFocus()
        Emit (To-ElementObject $element "" 0 0)
    }

    "set_value" {
        $element = Resolve-Element $payload.element_id
        $pattern = $null
        if (-not $element.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$pattern)) {
            throw "Element does not support ValuePattern."
        }
        if ($pattern.Current.IsReadOnly) { throw "Element is read-only." }
        $pattern.SetValue([string]$payload.text)
        Emit (To-ElementObject $element "" 0 0)
    }

    "click_point" {
        $element = Resolve-Element $payload.element_id
        $rect = $element.Current.BoundingRectangle
        if ($rect.Width -le 0 -or $rect.Height -le 0) { throw "Element has no clickable bounds." }
        Emit ([ordered]@{
            x = [int][Math]::Round($rect.Left + ($rect.Width / 2))
            y = [int][Math]::Round($rect.Top + ($rect.Height / 2))
            element = (To-ElementObject $element "" 0 0)
        })
    }

    "scroll_into_view" {
        $element = Resolve-Element $payload.element_id
        $pattern = $null
        if ($element.TryGetCurrentPattern([System.Windows.Automation.ScrollItemPattern]::Pattern, [ref]$pattern)) {
            $pattern.ScrollIntoView()
            Emit (To-ElementObject $element "" 0 0)
        } else {
            throw "Element does not support ScrollItemPattern."
        }
    }

    default {
        throw "Unknown semantic UI action: $Action"
    }
}
