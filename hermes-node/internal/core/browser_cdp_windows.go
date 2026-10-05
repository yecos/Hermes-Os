//go:build windows

package core

import (
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

//go:embed browser_cdp_windows.ps1
var browserCDPPowerShell string

func (n *Node) browserTab(tabID string) (BrowserTab, error) {
	tabs, err := n.BrowserTabs()
	if err != nil {
		return BrowserTab{}, err
	}
	if len(tabs) == 0 {
		return BrowserTab{}, errors.New("managed Chrome has no page tabs")
	}
	if strings.TrimSpace(tabID) == "" {
		return tabs[0], nil
	}
	for _, tab := range tabs {
		if tab.ID == tabID {
			return tab, nil
		}
	}
	return BrowserTab{}, fmt.Errorf("browser tab %q was not found", tabID)
}

func (n *Node) runBrowserCDP(action, tabID string, payload map[string]any, out any) error {
	tab, err := n.browserTab(tabID)
	if err != nil {
		return err
	}
	if strings.TrimSpace(tab.DevToolsURL) == "" {
		return errors.New("browser tab does not expose a DevTools websocket")
	}
	if payload == nil {
		payload = map[string]any{}
	}
	payload["websocket_url"] = tab.DevToolsURL
	payload["tab_id"] = tab.ID

	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	encodedPayload := base64.StdEncoding.EncodeToString(raw)
	scriptPayload := base64.StdEncoding.EncodeToString([]byte(browserCDPPowerShell))
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
		return fmt.Errorf("browser CDP %s failed: %s", action, msg)
	}
	text := strings.TrimSpace(stdout.String())
	if text == "" {
		return errors.New("browser CDP returned no data")
	}
	if err := json.Unmarshal([]byte(text), out); err != nil {
		return fmt.Errorf("invalid browser CDP response: %w: %s", err, text)
	}
	return nil
}

func (n *Node) BrowserSnapshot(tabID string, maxResults int) (BrowserSnapshot, error) {
	if maxResults <= 0 {
		maxResults = 150
	}
	if maxResults > 500 {
		maxResults = 500
	}
	var out BrowserSnapshot
	if err := n.runBrowserCDP("snapshot", tabID, map[string]any{"max_results": maxResults}, &out); err != nil {
		return BrowserSnapshot{}, err
	}
	tab, _ := n.browserTab(tabID)
	out.TabID = tab.ID
	n.record("browser_snapshot", map[string]any{"tab_id": out.TabID, "url": out.URL, "count": len(out.Elements)}, true, nil)
	return out, nil
}

func (n *Node) BrowserClick(tabID, elementID string) (BrowserActionResult, error) {
	if strings.TrimSpace(elementID) == "" {
		return BrowserActionResult{}, errors.New("element_id is required")
	}
	var out BrowserActionResult
	if err := n.runBrowserCDP("click", tabID, map[string]any{"element_id": elementID}, &out); err != nil {
		return BrowserActionResult{}, err
	}
	if !out.OK {
		if out.Error == "" {
			out.Error = "browser element click failed"
		}
		return out, errors.New(out.Error)
	}
	n.record("browser_click", map[string]any{"tab_id": tabID, "element_id": elementID, "url": out.URL}, true, nil)
	return out, nil
}

func (n *Node) BrowserSetText(tabID, elementID, text string) (BrowserActionResult, error) {
	if strings.TrimSpace(elementID) == "" {
		return BrowserActionResult{}, errors.New("element_id is required")
	}
	var out BrowserActionResult
	if err := n.runBrowserCDP("set_text", tabID, map[string]any{
		"element_id": elementID,
		"text":       text,
	}, &out); err != nil {
		return BrowserActionResult{}, err
	}
	if !out.OK {
		if out.Error == "" {
			out.Error = "browser element text update failed"
		}
		return out, errors.New(out.Error)
	}
	n.record("browser_set_text", map[string]any{"tab_id": tabID, "element_id": elementID, "characters": len([]rune(text))}, true, nil)
	return out, nil
}

func (n *Node) BrowserNavigate(tabID, targetURL string) (BrowserActionResult, error) {
	if strings.TrimSpace(targetURL) == "" {
		return BrowserActionResult{}, errors.New("url is required")
	}
	var out BrowserActionResult
	if err := n.runBrowserCDP("navigate", tabID, map[string]any{"url": targetURL}, &out); err != nil {
		return BrowserActionResult{}, err
	}
	n.record("browser_navigate", map[string]any{"tab_id": tabID, "url": targetURL}, true, nil)
	return out, nil
}

func (n *Node) BrowserPageText(tabID string, maxChars int) (BrowserPageText, error) {
	if maxChars <= 0 {
		maxChars = 20000
	}
	if maxChars > 100000 {
		maxChars = 100000
	}
	var out BrowserPageText
	if err := n.runBrowserCDP("page_text", tabID, map[string]any{"max_chars": maxChars}, &out); err != nil {
		return BrowserPageText{}, err
	}
	n.record("browser_page_text", map[string]any{"tab_id": tabID, "url": out.URL, "characters": len([]rune(out.Text)), "truncated": out.Truncated}, true, nil)
	return out, nil
}

func (n *Node) BrowserScreenshot(tabID, format string, quality int) (Screenshot, error) {
	var raw struct {
		Data     string `json:"data"`
		Format   string `json:"format"`
		MIMEType string `json:"mime_type"`
	}
	if err := n.runBrowserCDP("screenshot", tabID, map[string]any{
		"format":  format,
		"quality": quality,
	}, &raw); err != nil {
		return Screenshot{}, err
	}
	data, err := base64.StdEncoding.DecodeString(raw.Data)
	if err != nil {
		return Screenshot{}, fmt.Errorf("invalid browser screenshot data: %w", err)
	}
	shot := Screenshot{
		Data:       data,
		MIMEType:   raw.MIMEType,
		Format:     raw.Format,
		Display:    -4,
		CapturedAt: time.Now().UTC(),
	}
	n.record("browser_screenshot", map[string]any{"tab_id": tabID, "format": raw.Format, "bytes": len(data)}, true, nil)
	return shot, nil
}
