package core

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/config"
)

func testNode(t *testing.T) *Node {
	t.Helper()
	root := t.TempDir()
	return New(config.Config{Name: "test", AllowedRoots: []string{root}, ExecMode: "restricted", AllowedCommands: map[string]struct{}{"echo": {}}, CommandTimeout: 2 * time.Second, MaxReadBytes: 1024, MaxWriteBytes: 1024})
}

func TestPathPolicy(t *testing.T) {
	n := testNode(t)
	root := n.cfg.AllowedRoots[0]
	if _, err := n.ResolvePath(filepath.Join(root, "ok.txt")); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(filepath.Dir(root), "outside.txt")
	if _, err := n.ResolvePath(outside); err == nil {
		t.Fatal("expected outside path to be rejected")
	}
}
func TestReadWrite(t *testing.T) {
	n := testNode(t)
	p := filepath.Join(n.cfg.AllowedRoots[0], "a.txt")
	if err := n.WriteFile(p, "hello", false); err != nil {
		t.Fatal(err)
	}
	got, err := n.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	if got != "hello" {
		t.Fatalf("got %q", got)
	}
	_ = os.Remove(p)
}
