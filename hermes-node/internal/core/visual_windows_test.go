//go:build windows

package core

import (
	"testing"
	"unsafe"
)

func TestVisualWindowsInputLayout(t *testing.T) {
	if got := unsafe.Sizeof(input{}); got != 40 {
		t.Fatalf("unexpected INPUT size on amd64 Windows: got %d want 40", got)
	}
}

func TestKeyCodeAliases(t *testing.T) {
	tests := map[string]byte{
		"A":       0x41,
		"7":       0x37,
		"ENTER":   0x0D,
		"CTRL":    0x11,
		"CONTROL": 0x11,
		"ALT":     0x12,
		"F12":     0x7B,
	}
	for key, want := range tests {
		got, err := keyCode(key)
		if err != nil {
			t.Fatalf("keyCode(%q): %v", key, err)
		}
		if got != want {
			t.Fatalf("keyCode(%q)=%#x want %#x", key, got, want)
		}
	}
}
