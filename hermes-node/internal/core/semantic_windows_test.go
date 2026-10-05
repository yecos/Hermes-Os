//go:build windows

package core

import (
	"encoding/base64"
	"strings"
	"testing"
)

func TestUTF16LEEncodingForPowerShell(t *testing.T) {
	got := utf16LE("AB")
	want := []byte{0x41, 0x00, 0x42, 0x00}
	if len(got) != len(want) {
		t.Fatalf("len=%d want=%d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("byte %d=%#x want %#x", i, got[i], want[i])
		}
	}
}

func TestSemanticPowerShellEmbedded(t *testing.T) {
	if !strings.Contains(semanticPowerShell, "UIAutomationClient") {
		t.Fatal("embedded UI Automation PowerShell helper is missing expected assembly")
	}
	if !strings.Contains(semanticPowerShell, "Resolve-Element") {
		t.Fatal("embedded UI Automation PowerShell helper is missing element resolver")
	}

	encoded := base64.StdEncoding.EncodeToString(utf16LE("$x='ok'"))
	if encoded == "" {
		t.Fatal("encoded PowerShell command is empty")
	}
}
