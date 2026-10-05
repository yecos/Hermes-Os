package core

import (
	"encoding/json"
	"fmt"
	"time"
)

type ToolCallResult struct {
	Text  string
	Image *Screenshot
}

func toolStringArg(a map[string]any, key string) string {
	v, _ := a[key].(string)
	return v
}

func toolBoolArg(a map[string]any, key string) bool {
	v, _ := a[key].(bool)
	return v
}

func toolIntArg(a map[string]any, key string, fallback int) int {
	switch v := a[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	case json.Number:
		if n, err := v.Int64(); err == nil {
			return int(n)
		}
	}
	return fallback
}

func toolStringSliceArg(a map[string]any, key string) []string {
	out := []string{}
	switch raw := a[key].(type) {
	case []any:
		for _, v := range raw {
			out = append(out, fmt.Sprint(v))
		}
	case []string:
		out = append(out, raw...)
	}
	return out
}

func textToolResult(v any, err error) (ToolCallResult, error) {
	if err != nil {
		return ToolCallResult{}, err
	}
	switch x := v.(type) {
	case string:
		return ToolCallResult{Text: x}, nil
	default:
		return ToolCallResult{Text: JSON(v)}, nil
	}
}

// CallTool is the canonical Hermes Node tool dispatcher. Both MCP and the
// authenticated HTTP transport use this exact implementation so a remote node
// behaves the same as a local node.
func (n *Node) CallTool(name string, a map[string]any) (ToolCallResult, error) {
	if a == nil {
		a = map[string]any{}
	}

	switch name {
	case "system_status":
		return ToolCallResult{Text: JSON(n.Device())}, nil

	case "execute_command":
		return textToolResult(n.Execute(ExecRequest{
			Command: toolStringArg(a, "command"),
			Args:    toolStringSliceArg(a, "args"),
			Dir:     toolStringArg(a, "dir"),
		}))

	case "list_directory":
		return textToolResult(n.ListDirectory(toolStringArg(a, "path")))

	case "get_file_info":
		return textToolResult(n.GetFileInfo(toolStringArg(a, "path")))

	case "create_directory":
		if err := n.CreateDirectory(toolStringArg(a, "path")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "read_file":
		v, err := n.ReadFile(toolStringArg(a, "path"))
		return textToolResult(v, err)

	case "read_multiple_files":
		paths := toolStringSliceArg(a, "paths")
		out := map[string]any{}
		for _, path := range paths {
			value, err := n.ReadFile(path)
			if err != nil {
				out[path] = map[string]any{"error": err.Error()}
			} else {
				out[path] = map[string]any{"content": value}
			}
		}
		return ToolCallResult{Text: JSON(out)}, nil

	case "write_file":
		if err := n.WriteFile(
			toolStringArg(a, "path"),
			toolStringArg(a, "content"),
			toolBoolArg(a, "append"),
		); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "edit_block":
		return textToolResult(n.EditBlock(
			toolStringArg(a, "path"),
			toolStringArg(a, "old_text"),
			toolStringArg(a, "new_text"),
			toolIntArg(a, "expected_replacements", 1),
		))

	case "move_file":
		if err := n.MovePath(toolStringArg(a, "source"), toolStringArg(a, "destination")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "delete_path":
		if err := n.DeletePath(toolStringArg(a, "path"), toolBoolArg(a, "recursive")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "search_files":
		return textToolResult(n.Search(SearchRequest{
			Path:       toolStringArg(a, "path"),
			Pattern:    toolStringArg(a, "pattern"),
			SearchType: toolStringArg(a, "search_type"),
			MaxResults: toolIntArg(a, "max_results", 100),
			MaxDepth:   toolIntArg(a, "max_depth", 12),
		}))

	case "list_processes":
		return textToolResult(n.Processes())

	case "kill_process":
		if err := n.KillProcess(toolIntArg(a, "pid", 0)); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "start_process":
		return textToolResult(n.StartProcess(ExecRequest{
			Command: toolStringArg(a, "command"),
			Args:    toolStringSliceArg(a, "args"),
			Dir:     toolStringArg(a, "dir"),
		}))

	case "read_process_output":
		return textToolResult(n.ReadProcessOutput(toolStringArg(a, "session_id")))

	case "interact_with_process":
		return textToolResult(n.SendProcessInput(
			toolStringArg(a, "session_id"),
			toolStringArg(a, "input"),
			toolBoolArg(a, "newline"),
		))

	case "force_terminate":
		return textToolResult(n.StopProcess(toolStringArg(a, "session_id")))

	case "list_sessions":
		return ToolCallResult{Text: JSON(n.ListSessions())}, nil

	case "logs":
		return ToolCallResult{Text: JSON(n.Logs(toolIntArg(a, "limit", 50)))}, nil

	case "screenshot":
		shot, err := n.Screenshot(ScreenshotRequest{
			Display: toolIntArg(a, "display", -1),
			Format:  toolStringArg(a, "format"),
			Quality: toolIntArg(a, "quality", 82),
		})
		if err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Image: &shot}, nil

	case "screen_region":
		shot, err := n.ScreenRegion(ScreenRegionRequest{
			Left:    toolIntArg(a, "left", 0),
			Top:     toolIntArg(a, "top", 0),
			Width:   toolIntArg(a, "width", 0),
			Height:  toolIntArg(a, "height", 0),
			Format:  toolStringArg(a, "format"),
			Quality: toolIntArg(a, "quality", 82),
		})
		if err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Image: &shot}, nil

	case "window_screenshot":
		shot, err := n.WindowScreenshot(WindowScreenshotRequest{
			Window:  toolStringArg(a, "window"),
			Format:  toolStringArg(a, "format"),
			Quality: toolIntArg(a, "quality", 82),
		})
		if err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Image: &shot}, nil

	case "browser_screenshot":
		shot, err := n.BrowserScreenshot(
			toolStringArg(a, "tab_id"),
			toolStringArg(a, "format"),
			toolIntArg(a, "quality", 82),
		)
		if err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Image: &shot}, nil

	case "list_displays":
		return textToolResult(n.Displays())

	case "list_windows":
		return textToolResult(n.Windows())

	case "get_active_window":
		return textToolResult(n.ActiveWindow())

	case "open_application":
		if err := n.OpenApplication(OpenApplicationRequest{
			Target:     toolStringArg(a, "target"),
			Parameters: toolStringArg(a, "parameters"),
			Dir:        toolStringArg(a, "dir"),
		}); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "focus_window":
		return textToolResult(n.FocusWindow(toolStringArg(a, "window")))

	case "close_window":
		if err := n.CloseWindow(toolStringArg(a, "window")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "mouse_move":
		if err := n.MouseMove(toolIntArg(a, "x", 0), toolIntArg(a, "y", 0)); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "mouse_click":
		if err := n.MouseClick(toolStringArg(a, "button"), toolIntArg(a, "count", 1)); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "mouse_drag":
		if err := n.MouseDrag(MouseDragRequest{
			StartX: toolIntArg(a, "start_x", 0),
			StartY: toolIntArg(a, "start_y", 0),
			EndX:   toolIntArg(a, "end_x", 0),
			EndY:   toolIntArg(a, "end_y", 0),
			Button: toolStringArg(a, "button"),
			Steps:  toolIntArg(a, "steps", 12),
		}); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "mouse_scroll":
		if err := n.MouseScroll(toolIntArg(a, "delta", 0)); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "key_press":
		if err := n.KeyPress(toolStringArg(a, "key")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "hotkey":
		if err := n.Hotkey(toolStringSliceArg(a, "keys")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "type_text":
		if err := n.TypeText(toolStringArg(a, "text")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "clipboard_read":
		v, err := n.ClipboardRead()
		return textToolResult(v, err)

	case "clipboard_write":
		if err := n.ClipboardWrite(toolStringArg(a, "text")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "ui_snapshot":
		return textToolResult(n.UISnapshot(UIQuery{
			Window:     toolStringArg(a, "window"),
			MaxDepth:   toolIntArg(a, "max_depth", 8),
			MaxResults: toolIntArg(a, "max_results", 250),
		}))

	case "find_elements":
		return textToolResult(n.FindUIElements(UIQuery{
			Window:       toolStringArg(a, "window"),
			Name:         toolStringArg(a, "name"),
			AutomationID: toolStringArg(a, "automation_id"),
			ControlType:  toolStringArg(a, "control_type"),
			ClassName:    toolStringArg(a, "class_name"),
			Contains:     toolBoolArg(a, "contains"),
			MaxDepth:     toolIntArg(a, "max_depth", 16),
			MaxResults:   toolIntArg(a, "max_results", 20),
		}))

	case "wait_for_element":
		return textToolResult(n.WaitForUIElement(UIQuery{
			Window:       toolStringArg(a, "window"),
			Name:         toolStringArg(a, "name"),
			AutomationID: toolStringArg(a, "automation_id"),
			ControlType:  toolStringArg(a, "control_type"),
			ClassName:    toolStringArg(a, "class_name"),
			Contains:     toolBoolArg(a, "contains"),
			MaxDepth:     toolIntArg(a, "max_depth", 16),
			MaxResults:   1,
		}, time.Duration(toolIntArg(a, "timeout_seconds", 10))*time.Second))

	case "click_element":
		return textToolResult(n.ClickUIElement(toolStringArg(a, "element_id")))

	case "invoke_element":
		return textToolResult(n.InvokeUIElement(toolStringArg(a, "element_id")))

	case "set_element_text":
		return textToolResult(n.SetUIElementText(toolStringArg(a, "element_id"), toolStringArg(a, "text")))

	case "focus_element":
		return textToolResult(n.FocusUIElement(toolStringArg(a, "element_id")))

	case "scroll_element":
		return textToolResult(n.ScrollUIElement(toolStringArg(a, "element_id"), toolIntArg(a, "delta", 0)))

	case "browser_open_managed":
		if err := n.BrowserOpenManaged(toolStringArg(a, "url")); err != nil {
			return ToolCallResult{}, err
		}
		return ToolCallResult{Text: "ok"}, nil

	case "browser_tabs":
		return textToolResult(n.BrowserTabs())

	case "browser_open_tab":
		return textToolResult(n.BrowserOpenTab(toolStringArg(a, "url")))

	case "browser_snapshot":
		return textToolResult(n.BrowserSnapshot(
			toolStringArg(a, "tab_id"),
			toolIntArg(a, "max_results", 150),
		))

	case "browser_click":
		return textToolResult(n.BrowserClick(
			toolStringArg(a, "tab_id"),
			toolStringArg(a, "element_id"),
		))

	case "browser_set_text":
		return textToolResult(n.BrowserSetText(
			toolStringArg(a, "tab_id"),
			toolStringArg(a, "element_id"),
			toolStringArg(a, "text"),
		))

	case "browser_navigate":
		return textToolResult(n.BrowserNavigate(
			toolStringArg(a, "tab_id"),
			toolStringArg(a, "url"),
		))

	case "browser_page_text":
		return textToolResult(n.BrowserPageText(
			toolStringArg(a, "tab_id"),
			toolIntArg(a, "max_chars", 20000),
		))

	default:
		return ToolCallResult{}, fmt.Errorf("unknown or unsupported tool %q", name)
	}
}
