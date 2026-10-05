package core

import (
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/config"
)

func TestPersistentProcessSession(t *testing.T) {
	root := t.TempDir()
	n := New(config.Config{
		Name:           "session-test",
		AllowedRoots:   []string{root},
		ExecMode:       "admin",
		CommandTimeout: 2 * time.Second,
		MaxReadBytes:   1024,
		MaxWriteBytes:  1024,
	})

	command := "sh"
	args := []string{"-c", "echo hermes-session"}
	if runtime.GOOS == "windows" {
		command = "cmd.exe"
		args = []string{"/c", "echo", "hermes-session"}
	}

	session, err := n.StartProcess(ExecRequest{
		Command: command,
		Args:    args,
		Dir:     root,
	})
	if err != nil {
		t.Fatal(err)
	}
	if session.ID == "" || session.PID <= 0 {
		t.Fatalf("unexpected session: %+v", session)
	}

	deadline := time.Now().Add(3 * time.Second)
	for {
		out, err := n.ReadProcessOutput(session.ID)
		if err != nil {
			t.Fatal(err)
		}
		if out.Session.Done {
			if !strings.Contains(out.Output, "hermes-session") {
				t.Fatalf("missing process output: %q", out.Output)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("session did not complete")
		}
		time.Sleep(25 * time.Millisecond)
	}

	sessions := n.ListSessions()
	if len(sessions) != 1 || sessions[0].ID != session.ID {
		t.Fatalf("unexpected sessions: %+v", sessions)
	}
}
