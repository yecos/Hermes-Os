//go:build windows

package core

import (
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

//go:embed semantic_windows.ps1
var semanticPowerShell string

func semanticCapabilities() []string {
	return []string{
		"ui_snapshot", "find_elements", "wait_for_element",
		"click_element", "invoke_element", "set_element_text",
		"focus_element", "scroll_element",
		"screen_region", "window_screenshot",
		"browser_open_managed", "browser_tabs", "browser_open_tab",
	}
}

func (n *Node) runSemanticPowerShell(action string, payload any, out any) error {
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	encodedPayload := base64.StdEncoding.EncodeToString(raw)
	scriptPayload := base64.StdEncoding.EncodeToString([]byte(semanticPowerShell))
	wrapper := fmt.Sprintf(
		"$script=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s')); & ([ScriptBlock]::Create($script)) -Action '%s' -PayloadBase64 '%s'",
		scriptPayload, action, encodedPayload,
	)
	encodedCommand := base64.StdEncoding.EncodeToString(utf16LE(wrapper))
	cmd := exec.Command(
		"powershell.exe",
		"-NoLogo",
		"-NoProfile",
		"-NonInteractive",
		"-ExecutionPolicy", "Bypass",
		"-EncodedCommand", encodedCommand,
	)
	var stdout, stderr strings.Builder
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		msg := strings.TrimSpace(stderr.String())
		if msg == "" {
			msg = err.Error()
		}
		return fmt.Errorf("UI Automation %s failed: %s", action, msg)
	}
	text := strings.TrimSpace(stdout.String())
	if text == "" {
		return errors.New("UI Automation returned no data")
	}
	if err := json.Unmarshal([]byte(text), out); err != nil {
		return fmt.Errorf("invalid UI Automation response: %w: %s", err, text)
	}
	return nil
}

func utf16LE(s string) []byte {
	runes := []rune(s)
	encoded := make([]byte, 0, len(runes)*2)
	for _, r := range runes {
		if r <= 0xFFFF {
			encoded = append(encoded, byte(r), byte(r>>8))
			continue
		}
		r -= 0x10000
		hi := uint16(0xD800 + (r >> 10))
		lo := uint16(0xDC00 + (r & 0x3FF))
		encoded = append(encoded, byte(hi), byte(hi>>8), byte(lo), byte(lo>>8))
	}
	return encoded
}
func normalizeUIQuery(q UIQuery) UIQuery {
	if q.MaxDepth <= 0 {
		q.MaxDepth = 8
	}
	if q.MaxDepth > 32 {
		q.MaxDepth = 32
	}
	if q.MaxResults <= 0 {
		q.MaxResults = 50
	}
	if q.MaxResults > 1000 {
		q.MaxResults = 1000
	}
	return q
}

func (n *Node) UISnapshot(q UIQuery) (UISnapshot, error) {
	q = normalizeUIQuery(q)
	var raw struct {
		Window   string      `json:"window"`
		Elements []UIElement `json:"elements"`
	}
	if err := n.runSemanticPowerShell("snapshot", q, &raw); err != nil {
		return UISnapshot{}, err
	}
	out := UISnapshot{
		Window:       raw.Window,
		CapturedAt:   time.Now().UTC(),
		ElementCount: len(raw.Elements),
		Elements:     raw.Elements,
	}
	n.record("ui_snapshot", map[string]any{"window": q.Window, "count": len(raw.Elements)}, true, nil)
	return out, nil
}

func (n *Node) FindUIElements(q UIQuery) ([]UIElement, error) {
	q = normalizeUIQuery(q)
	var out []UIElement
	if err := n.runSemanticPowerShell("find", q, &out); err != nil {
		return nil, err
	}
	n.record("find_elements", map[string]any{
		"window": q.Window, "name": q.Name, "automation_id": q.AutomationID,
		"control_type": q.ControlType, "class_name": q.ClassName, "count": len(out),
	}, true, nil)
	return out, nil
}

func (n *Node) semanticElementAction(action, elementID string) (UIElement, error) {
	if strings.TrimSpace(elementID) == "" {
		return UIElement{}, errors.New("element_id is required")
	}
	var out UIElement
	if err := n.runSemanticPowerShell(action, map[string]any{"element_id": elementID}, &out); err != nil {
		return UIElement{}, err
	}
	return out, nil
}

func (n *Node) InvokeUIElement(elementID string) (UIActionResult, error) {
	el, err := n.semanticElementAction("invoke", elementID)
	if err != nil {
		return UIActionResult{}, err
	}
	n.record("invoke_element", map[string]any{"name": el.Name, "automation_id": el.AutomationID}, true, nil)
	return UIActionResult{Action: "invoke", Element: el, OK: true}, nil
}

func (n *Node) FocusUIElement(elementID string) (UIActionResult, error) {
	el, err := n.semanticElementAction("focus", elementID)
	if err != nil {
		return UIActionResult{}, err
	}
	n.record("focus_element", map[string]any{"name": el.Name, "automation_id": el.AutomationID}, true, nil)
	return UIActionResult{Action: "focus", Element: el, OK: true}, nil
}

func (n *Node) ClickUIElement(elementID string) (UIActionResult, error) {
	if strings.TrimSpace(elementID) == "" {
		return UIActionResult{}, errors.New("element_id is required")
	}
	var point struct {
		X       int       `json:"x"`
		Y       int       `json:"y"`
		Element UIElement `json:"element"`
	}
	if err := n.runSemanticPowerShell("click_point", map[string]any{"element_id": elementID}, &point); err != nil {
		return UIActionResult{}, err
	}
	if err := n.MouseMove(point.X, point.Y); err != nil {
		return UIActionResult{}, err
	}
	if err := n.MouseClick("left", 1); err != nil {
		return UIActionResult{}, err
	}
	n.record("click_element", map[string]any{"name": point.Element.Name, "x": point.X, "y": point.Y}, true, nil)
	return UIActionResult{Action: "click", Element: point.Element, OK: true, Fallback: "bounding_rect_center"}, nil
}

func (n *Node) SetUIElementText(elementID, text string) (UIActionResult, error) {
	if strings.TrimSpace(elementID) == "" {
		return UIActionResult{}, errors.New("element_id is required")
	}
	var out UIElement
	err := n.runSemanticPowerShell("set_value", map[string]any{"element_id": elementID, "text": text}, &out)
	if err == nil {
		n.record("set_element_text", map[string]any{"name": out.Name, "characters": len([]rune(text)), "method": "ValuePattern"}, true, nil)
		return UIActionResult{Action: "set_text", Element: out, OK: true}, nil
	}

	focus, focusErr := n.FocusUIElement(elementID)
	if focusErr != nil {
		return UIActionResult{}, fmt.Errorf("%v; focus fallback also failed: %w", err, focusErr)
	}
	if hotkeyErr := n.Hotkey([]string{"CTRL", "A"}); hotkeyErr != nil {
		return UIActionResult{}, hotkeyErr
	}
	if typeErr := n.TypeText(text); typeErr != nil {
		return UIActionResult{}, typeErr
	}
	n.record("set_element_text", map[string]any{"name": focus.Element.Name, "characters": len([]rune(text)), "method": "keyboard_fallback"}, true, nil)
	return UIActionResult{Action: "set_text", Element: focus.Element, OK: true, Fallback: "focus_ctrl_a_type_text"}, nil
}

func (n *Node) ScrollUIElement(elementID string, delta int) (UIActionResult, error) {
	el, err := n.semanticElementAction("scroll_into_view", elementID)
	if err == nil {
		if delta != 0 {
			_ = n.MouseMove(int(el.Left+el.Width/2), int(el.Top+el.Height/2))
			_ = n.MouseScroll(delta)
		}
		n.record("scroll_element", map[string]any{"name": el.Name, "delta": delta, "method": "ScrollItemPattern"}, true, nil)
		return UIActionResult{Action: "scroll", Element: el, OK: true}, nil
	}

	var point struct {
		X       int       `json:"x"`
		Y       int       `json:"y"`
		Element UIElement `json:"element"`
	}
	if pointErr := n.runSemanticPowerShell("click_point", map[string]any{"element_id": elementID}, &point); pointErr != nil {
		return UIActionResult{}, fmt.Errorf("%v; coordinate fallback failed: %w", err, pointErr)
	}
	if moveErr := n.MouseMove(point.X, point.Y); moveErr != nil {
		return UIActionResult{}, moveErr
	}
	if scrollErr := n.MouseScroll(delta); scrollErr != nil {
		return UIActionResult{}, scrollErr
	}
	return UIActionResult{Action: "scroll", Element: point.Element, OK: true, Fallback: "mouse_wheel_at_element"}, nil
}

func (n *Node) WaitForUIElement(q UIQuery, timeout time.Duration) (UIElement, error) {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	if timeout > 60*time.Second {
		timeout = 60 * time.Second
	}
	deadline := time.Now().Add(timeout)
	var lastErr error
	for {
		items, err := n.FindUIElements(q)
		if err == nil && len(items) > 0 {
			n.record("wait_for_element", map[string]any{"name": q.Name, "found": true, "duration_ms": timeout.Milliseconds() - time.Until(deadline).Milliseconds()}, true, nil)
			return items[0], nil
		}
		if err != nil {
			lastErr = err
		}
		if time.Now().After(deadline) {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if lastErr != nil {
		return UIElement{}, fmt.Errorf("element not found before timeout: %w", lastErr)
	}
	return UIElement{}, errors.New("element not found before timeout")
}

func captureRect(left, top, width, height int, format string, quality int) (Screenshot, error) {
	if width <= 0 || height <= 0 {
		return Screenshot{}, errors.New("width and height must be greater than zero")
	}
	return captureWindowsRect(left, top, width, height, format, quality)
}

func (n *Node) ScreenRegion(req ScreenRegionRequest) (Screenshot, error) {
	shot, err := captureRect(req.Left, req.Top, req.Width, req.Height, req.Format, req.Quality)
	if err != nil {
		return Screenshot{}, err
	}
	shot.Display = -2
	n.record("screen_region", map[string]any{"left": req.Left, "top": req.Top, "width": req.Width, "height": req.Height}, true, nil)
	return shot, nil
}

func (n *Node) WindowScreenshot(req WindowScreenshotRequest) (Screenshot, error) {
	hwnd, err := n.resolveWindow(req.Window)
	if err != nil {
		return Screenshot{}, err
	}
	active, _, _ := procGetForegroundWindow.Call()
	info, ok := windowInfo(hwnd, active)
	if !ok || info.Width <= 0 || info.Height <= 0 {
		return Screenshot{}, errors.New("window has no capturable bounds")
	}
	shot, err := captureRect(int(info.Left), int(info.Top), int(info.Width), int(info.Height), req.Format, req.Quality)
	if err != nil {
		return Screenshot{}, err
	}
	shot.Display = -3
	n.record("window_screenshot", map[string]any{"window": req.Window, "title": info.Title, "width": info.Width, "height": info.Height}, true, nil)
	return shot, nil
}

const managedChromePort = 9222

func (n *Node) BrowserOpenManaged(initialURL string) error {
	if strings.TrimSpace(initialURL) == "" {
		initialURL = "about:blank"
	}
	profile := filepath.Join(os.Getenv("LOCALAPPDATA"), "HermesCommander", "chrome-profile")
	if err := os.MkdirAll(profile, 0700); err != nil {
		return err
	}
	target := resolveApplicationTarget("chrome")
	if target == "chrome" {
		return errors.New("Chrome executable was not found")
	}
	args := []string{
		fmt.Sprintf("--remote-debugging-port=%d", managedChromePort),
		"--remote-debugging-address=127.0.0.1",
		"--no-first-run",
		"--no-default-browser-check",
		"--user-data-dir=" + profile,
		initialURL,
	}
	cmd := exec.Command(target, args...)
	if err := cmd.Start(); err != nil {
		return err
	}
	n.record("browser_open_managed", map[string]any{"url": initialURL, "pid": cmd.Process.Pid}, true, nil)
	return nil
}

func (n *Node) BrowserTabs() ([]BrowserTab, error) {
	client := http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/json/list", managedChromePort))
	if err != nil {
		return nil, fmt.Errorf("managed Chrome is not available: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("managed Chrome returned HTTP %d", resp.StatusCode)
	}
	var raw []struct {
		ID                   string `json:"id"`
		Type                 string `json:"type"`
		Title                string `json:"title"`
		URL                  string `json:"url"`
		WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return nil, err
	}
	out := make([]BrowserTab, 0, len(raw))
	for _, item := range raw {
		if item.Type != "page" {
			continue
		}
		out = append(out, BrowserTab{ID: item.ID, Type: item.Type, Title: item.Title, URL: item.URL, DevToolsURL: item.WebSocketDebuggerURL})
	}
	n.record("browser_tabs", map[string]any{"count": len(out)}, true, nil)
	return out, nil
}

func (n *Node) BrowserOpenTab(targetURL string) (BrowserTab, error) {
	if strings.TrimSpace(targetURL) == "" {
		return BrowserTab{}, errors.New("url is required")
	}
	if _, err := url.ParseRequestURI(targetURL); err != nil {
		return BrowserTab{}, fmt.Errorf("invalid URL: %w", err)
	}
	req, err := http.NewRequest(http.MethodPut, fmt.Sprintf("http://127.0.0.1:%d/json/new?%s", managedChromePort, url.QueryEscape(targetURL)), nil)
	if err != nil {
		return BrowserTab{}, err
	}
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return BrowserTab{}, fmt.Errorf("managed Chrome is not available: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return BrowserTab{}, fmt.Errorf("managed Chrome returned HTTP %d", resp.StatusCode)
	}
	var raw struct {
		ID                   string `json:"id"`
		Type                 string `json:"type"`
		Title                string `json:"title"`
		URL                  string `json:"url"`
		WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return BrowserTab{}, err
	}
	tab := BrowserTab{ID: raw.ID, Type: raw.Type, Title: raw.Title, URL: raw.URL, DevToolsURL: raw.WebSocketDebuggerURL}
	n.record("browser_open_tab", map[string]any{"url": targetURL, "tab_id": raw.ID}, true, nil)
	return tab, nil
}
