package mcp

import (
	"encoding/base64"
	"testing"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/core"
)

func TestScreenshotToolResultContainsImageContent(t *testing.T) {
	shot := core.Screenshot{
		Data:       []byte{1, 2, 3, 4},
		MIMEType:   "image/jpeg",
		Format:     "jpeg",
		Display:    -1,
		Width:      1920,
		Height:     1080,
		CapturedAt: time.Unix(123, 0).UTC(),
	}

	result := screenshotToolResult(shot)
	content, ok := result["content"].([]map[string]any)
	if !ok {
		t.Fatalf("unexpected content type: %T", result["content"])
	}
	if len(content) != 2 {
		t.Fatalf("expected text + image content, got %d blocks", len(content))
	}

	image := content[1]
	if image["type"] != "image" {
		t.Fatalf("expected image block, got %#v", image)
	}
	if image["mimeType"] != "image/jpeg" {
		t.Fatalf("unexpected mime type: %v", image["mimeType"])
	}
	if image["data"] != base64.StdEncoding.EncodeToString(shot.Data) {
		t.Fatalf("unexpected base64 payload")
	}
}

func TestVisualToolsAreAdvertised(t *testing.T) {
	names := map[string]bool{}
	for _, tool := range tools() {
		name, _ := tool["name"].(string)
		names[name] = true
	}
	for _, required := range []string{
		"screenshot", "list_displays", "list_windows", "get_active_window",
		"open_application", "focus_window", "close_window",
		"mouse_move", "mouse_click", "mouse_drag", "mouse_scroll",
		"key_press", "hotkey", "type_text", "clipboard_read", "clipboard_write",
	} {
		if !names[required] {
			t.Fatalf("visual tool %q is missing", required)
		}
	}
}
