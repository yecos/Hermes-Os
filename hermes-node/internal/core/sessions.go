package core

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type processSession struct {
	ID        string
	Command   string
	Args      []string
	Dir       string
	StartedAt time.Time
	cmd       *exec.Cmd
	stdin     io.WriteCloser

	mu       sync.Mutex
	output   strings.Builder
	done     bool
	exitCode int
	lastErr  string
}

type sessionManager struct {
	mu       sync.Mutex
	sessions map[string]*processSession
}

var (
	sessionManagers sync.Map
	sessionCounter  atomic.Uint64
)

func sessionsFor(n *Node) *sessionManager {
	if existing, ok := sessionManagers.Load(n); ok {
		return existing.(*sessionManager)
	}
	mgr := &sessionManager{sessions: map[string]*processSession{}}
	actual, _ := sessionManagers.LoadOrStore(n, mgr)
	return actual.(*sessionManager)
}

type SessionInfo struct {
	ID        string    `json:"id"`
	Command   string    `json:"command"`
	Args      []string  `json:"args"`
	Dir       string    `json:"dir,omitempty"`
	PID       int       `json:"pid,omitempty"`
	StartedAt time.Time `json:"started_at"`
	Done      bool      `json:"done"`
	ExitCode  int       `json:"exit_code,omitempty"`
	Error     string    `json:"error,omitempty"`
}

type SessionOutput struct {
	Session SessionInfo `json:"session"`
	Output  string      `json:"output"`
}

type sessionWriter struct {
	s *processSession
}

func (w sessionWriter) Write(p []byte) (int, error) {
	w.s.mu.Lock()
	defer w.s.mu.Unlock()
	const max = 4 << 20
	if w.s.output.Len()+len(p) > max {
		remaining := max - w.s.output.Len()
		if remaining > 0 {
			_, _ = w.s.output.Write(p[:remaining])
		}
		return len(p), nil
	}
	return w.s.output.Write(p)
}

func (n *Node) StartProcess(req ExecRequest) (SessionInfo, error) {
	cmdName := strings.TrimSpace(req.Command)
	if cmdName == "" {
		return SessionInfo{}, errors.New("command is required")
	}
	if err := n.commandAllowed(cmdName); err != nil {
		return SessionInfo{}, err
	}

	dir := ""
	if strings.TrimSpace(req.Dir) != "" {
		resolved, err := n.ResolvePath(req.Dir)
		if err != nil {
			return SessionInfo{}, err
		}
		info, err := os.Stat(resolved)
		if err != nil {
			return SessionInfo{}, err
		}
		if !info.IsDir() {
			return SessionInfo{}, errors.New("dir is not a directory")
		}
		dir = resolved
	}

	cmd := exec.Command(cmdName, req.Args...)
	cmd.Dir = dir
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return SessionInfo{}, err
	}

	s := &processSession{
		ID:        fmt.Sprintf("session-%d-%d", time.Now().UnixMilli(), sessionCounter.Add(1)),
		Command:   cmdName,
		Args:      append([]string(nil), req.Args...),
		Dir:       dir,
		StartedAt: time.Now().UTC(),
		cmd:       cmd,
		stdin:     stdin,
		exitCode:  0,
	}
	writer := sessionWriter{s: s}
	cmd.Stdout = writer
	cmd.Stderr = writer

	if err := cmd.Start(); err != nil {
		return SessionInfo{}, err
	}

	mgr := sessionsFor(n)
	mgr.mu.Lock()
	mgr.sessions[s.ID] = s
	mgr.mu.Unlock()

	go func() {
		err := cmd.Wait()
		s.mu.Lock()
		s.done = true
		if err != nil {
			var exitErr *exec.ExitError
			if errors.As(err, &exitErr) {
				s.exitCode = exitErr.ExitCode()
			} else {
				s.exitCode = -1
			}
			s.lastErr = err.Error()
		}
		_ = s.stdin.Close()
		s.mu.Unlock()
	}()

	n.record("start_process", map[string]any{"session_id": s.ID, "command": cmdName, "args": req.Args, "pid": cmd.Process.Pid}, true, nil)
	return sessionInfo(s), nil
}

func sessionInfo(s *processSession) SessionInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	pid := 0
	if s.cmd != nil && s.cmd.Process != nil {
		pid = s.cmd.Process.Pid
	}
	return SessionInfo{
		ID:        s.ID,
		Command:   s.Command,
		Args:      append([]string(nil), s.Args...),
		Dir:       s.Dir,
		PID:       pid,
		StartedAt: s.StartedAt,
		Done:      s.done,
		ExitCode:  s.exitCode,
		Error:     s.lastErr,
	}
}

func (n *Node) ReadProcessOutput(id string) (SessionOutput, error) {
	s, err := n.getSession(id)
	if err != nil {
		return SessionOutput{}, err
	}
	info := sessionInfo(s)
	s.mu.Lock()
	output := s.output.String()
	s.mu.Unlock()
	return SessionOutput{Session: info, Output: output}, nil
}

func (n *Node) SendProcessInput(id, input string, newline bool) (SessionInfo, error) {
	s, err := n.getSession(id)
	if err != nil {
		return SessionInfo{}, err
	}
	s.mu.Lock()
	done := s.done
	s.mu.Unlock()
	if done {
		return SessionInfo{}, errors.New("session has already exited")
	}
	if newline {
		input += "\n"
	}
	if _, err := io.WriteString(s.stdin, input); err != nil {
		return SessionInfo{}, err
	}
	n.record("interact_with_process", map[string]any{"session_id": id, "bytes": len(input)}, true, nil)
	return sessionInfo(s), nil
}

func (n *Node) StopProcess(id string) (SessionInfo, error) {
	s, err := n.getSession(id)
	if err != nil {
		return SessionInfo{}, err
	}
	s.mu.Lock()
	done := s.done
	proc := s.cmd.Process
	s.mu.Unlock()
	if !done && proc != nil {
		if err := proc.Kill(); err != nil {
			return SessionInfo{}, err
		}
	}
	n.record("force_terminate", map[string]any{"session_id": id}, true, nil)
	return sessionInfo(s), nil
}

func (n *Node) ListSessions() []SessionInfo {
	mgr := sessionsFor(n)
	mgr.mu.Lock()
	items := make([]*processSession, 0, len(mgr.sessions))
	for _, s := range mgr.sessions {
		items = append(items, s)
	}
	mgr.mu.Unlock()

	out := make([]SessionInfo, 0, len(items))
	for _, s := range items {
		out = append(out, sessionInfo(s))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out
}

func (n *Node) getSession(id string) (*processSession, error) {
	mgr := sessionsFor(n)
	mgr.mu.Lock()
	s := mgr.sessions[id]
	mgr.mu.Unlock()
	if s == nil {
		return nil, fmt.Errorf("unknown session %q", id)
	}
	return s, nil
}

func (n *Node) KillProcess(pid int) error {
	if pid <= 0 {
		return errors.New("pid must be greater than zero")
	}
	p, err := os.FindProcess(pid)
	if err != nil {
		return err
	}
	err = p.Kill()
	n.record("kill_process", map[string]any{"pid": pid}, err == nil, err)
	return err
}
