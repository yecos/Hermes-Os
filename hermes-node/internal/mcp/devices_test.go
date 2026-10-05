package mcp

import (
	"encoding/json"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/config"
	"github.com/yecos/Hermes-Os/hermes-node/internal/core"
	"github.com/yecos/Hermes-Os/hermes-node/internal/httpapi"
)

func testNodeConfig(name, token, root string) config.Config {
	return config.Config{
		Name:            name,
		Listen:          "127.0.0.1:0",
		Token:           token,
		AllowedRoots:    []string{root},
		ExecMode:        "restricted",
		AllowedCommands: map[string]struct{}{"echo": {}},
		CommandTimeout:  5 * time.Second,
		MaxReadBytes:    1024 * 1024,
		MaxWriteBytes:   1024 * 1024,
	}
}

func TestDeviceManagerRemoteRouting(t *testing.T) {
	root := t.TempDir()
	remoteNode := core.New(testNodeConfig("REMOTE-TEST", "secret-token", root))
	server := httptest.NewServer(httpapi.New(remoteNode).Handler())
	defer server.Close()

	localNode := core.New(testNodeConfig("LOCAL-TEST", "local-token", root))
	manager := NewDeviceManager(localNode)
	manager.registryPath = filepath.Join(t.TempDir(), "devices.json")

	added, err := manager.Add("remote-test", "Remote Test", server.URL, "secret-token")
	if err != nil {
		t.Fatalf("add remote: %v", err)
	}
	if !added.Online || added.ID != "remote-test" {
		t.Fatalf("unexpected add result: %+v", added)
	}

	if _, err := manager.Select("remote-test"); err != nil {
		t.Fatalf("select remote: %v", err)
	}
	result, target, err := manager.Call("system_status", map[string]any{})
	if err != nil {
		t.Fatalf("remote system_status: %v", err)
	}
	if target != "remote-test" {
		t.Fatalf("target=%q", target)
	}
	var status map[string]any
	if err := json.Unmarshal([]byte(result.Text), &status); err != nil {
		t.Fatalf("decode status: %v", err)
	}
	if status["name"] != "REMOTE-TEST" {
		t.Fatalf("remote status name=%v", status["name"])
	}

	items := manager.List()
	found := false
	for _, item := range items {
		if item.ID == "remote-test" {
			found = true
			if !item.Online {
				t.Fatalf("registered remote should be online: %+v", item)
			}
		}
	}
	if !found {
		t.Fatal("remote device missing from list")
	}
}

func TestDeviceManagerRejectsPublicEndpoint(t *testing.T) {
	root := t.TempDir()
	manager := NewDeviceManager(core.New(testNodeConfig("LOCAL", "token", root)))
	manager.registryPath = filepath.Join(t.TempDir(), "devices.json")

	_, err := manager.Add("public", "Public", "https://example.com:9090", "secret")
	if err == nil {
		t.Fatal("expected public URL rejection")
	}
	if !strings.Contains(err.Error(), "private LAN") && !strings.Contains(err.Error(), "Tailscale") {
		t.Fatalf("unexpected error: %v", err)
	}
}
