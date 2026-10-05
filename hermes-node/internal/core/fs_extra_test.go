package core

import (
	"path/filepath"
	"testing"
)

func TestFileOperationsAndSearch(t *testing.T) {
	n := testNode(t)
	root := n.cfg.AllowedRoots[0]

	dir := filepath.Join(root, "workspace", "nested")
	if err := n.CreateDirectory(dir); err != nil {
		t.Fatal(err)
	}

	original := filepath.Join(dir, "hello.txt")
	if err := n.WriteFile(original, "alpha\nbeta needle\ngamma\n", false); err != nil {
		t.Fatal(err)
	}

	info, err := n.GetFileInfo(original)
	if err != nil {
		t.Fatal(err)
	}
	if info.IsDir || info.Size == 0 {
		t.Fatalf("unexpected file info: %+v", info)
	}

	byName, err := n.Search(SearchRequest{
		Path:       root,
		Pattern:    "hello",
		SearchType: "files",
		MaxResults: 10,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(byName) != 1 {
		t.Fatalf("expected one name result, got %d", len(byName))
	}

	byContent, err := n.Search(SearchRequest{
		Path:       root,
		Pattern:    "needle",
		SearchType: "content",
		MaxResults: 10,
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(byContent) != 1 || byContent[0].Line != 2 {
		t.Fatalf("unexpected content results: %+v", byContent)
	}

	moved := filepath.Join(root, "workspace", "moved.txt")
	if err := n.MovePath(original, moved); err != nil {
		t.Fatal(err)
	}

	got, err := n.ReadFile(moved)
	if err != nil {
		t.Fatal(err)
	}
	if got != "alpha\nbeta needle\ngamma\n" {
		t.Fatalf("unexpected moved content: %q", got)
	}

	if err := n.DeletePath(moved, false); err != nil {
		t.Fatal(err)
	}

	if _, err := n.GetFileInfo(moved); err == nil {
		t.Fatal("expected deleted file to be missing")
	}
}
