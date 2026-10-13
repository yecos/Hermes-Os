package mcp

import (
    "testing"

    "github.com/yecos/Hermes-Os/hermes-node/internal/core"
)

// A remote tunnel must reuse the EXISTING Hermes MCP server, not an alternate
// read-only surface. This checks all major capability families remain present.
func TestFullHermesToolSurfaceForChatTunnel(t *testing.T) {
    node := core.New(testNodeConfig("HERMES", "test-token", t.TempDir()))
    s := New(node)
    got := s.handle(request{JSONRPC: "2.0", ID: 1, Method: "tools/list"})
    payload, ok := got.Result.(map[string]any)
    if !ok {
        t.Fatalf("tools/list response has unexpected type: %T", got.Result)
    }
    tools, ok := payload["tools"].([]map[string]any)
    if !ok {
        t.Fatalf("tools/list tools has unexpected type: %T", payload["tools"])
    }
    present := make(map[string]bool, len(tools))
    for _, tool := range tools {
        if name, ok := tool["name"].(string); ok {
            present[name] = true
        }
    }
    for _, name := range []string{
        "system_status", "execute_command", "list_directory", "read_file",
        "write_file", "start_process", "list_processes", "screenshot",
        "list_windows", "mouse_click", "key_press", "ui_snapshot",
        "browser_tabs", "devices_list", "device_select",
    } {
        if !present[name] {
            t.Errorf("full Commander MCP tool %q missing", name)
        }
    }
}
