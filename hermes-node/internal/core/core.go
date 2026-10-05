package core

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/config"
)

type AuditEntry struct {
	Time   time.Time      `json:"time"`
	Action string         `json:"action"`
	Detail map[string]any `json:"detail,omitempty"`
	OK     bool           `json:"ok"`
	Error  string         `json:"error,omitempty"`
}

type Node struct {
	cfg     config.Config
	started time.Time
	mu      sync.Mutex
	audit   []AuditEntry
}

func New(cfg config.Config) *Node {
	return &Node{cfg: cfg, started: time.Now(), audit: make([]AuditEntry, 0, 200)}
}
func (n *Node) Config() config.Config { return n.cfg }

func (n *Node) Device() map[string]any {
	host, _ := os.Hostname()
	wd, _ := os.Getwd()
	capabilities := []string{
		"system_status", "execute_command", "list_directory", "get_file_info", "create_directory",
		"read_file", "read_multiple_files", "write_file", "edit_block", "move_file", "delete_path", "search_files",
		"list_processes", "kill_process", "start_process", "read_process_output", "interact_with_process",
		"force_terminate", "list_sessions", "logs",
	}
	capabilities = append(capabilities, visualCapabilities()...)
	capabilities = append(capabilities, semanticCapabilities()...)
	return map[string]any{
		"name":              n.cfg.Name,
		"hostname":          host,
		"os":                runtime.GOOS,
		"arch":              runtime.GOARCH,
		"pid":               os.Getpid(),
		"working_directory": wd,
		"started_at":        n.started.UTC(),
		"uptime_seconds":    int64(time.Since(n.started).Seconds()),
		"version":           "0.5.0",
		"exec_mode":         n.cfg.ExecMode,
		"allowed_roots":     n.cfg.AllowedRoots,
		"capabilities":      capabilities,
	}
}

type ExecRequest struct {
	Command string   `json:"command"`
	Args    []string `json:"args,omitempty"`
	Dir     string   `json:"dir,omitempty"`
}

type ExecResult struct {
	Command    string   `json:"command"`
	Args       []string `json:"args"`
	ExitCode   int      `json:"exit_code"`
	Stdout     string   `json:"stdout"`
	Stderr     string   `json:"stderr"`
	DurationMS int64    `json:"duration_ms"`
}

func (n *Node) Execute(req ExecRequest) (ExecResult, error) {
	started := time.Now()
	cmdName := strings.TrimSpace(req.Command)
	if cmdName == "" {
		return ExecResult{}, errors.New("command is required")
	}
	if err := n.commandAllowed(cmdName); err != nil {
		n.record("execute_command", map[string]any{"command": cmdName, "args": req.Args}, false, err)
		return ExecResult{}, err
	}

	dir := ""
	if strings.TrimSpace(req.Dir) != "" {
		resolved, err := n.ResolvePath(req.Dir)
		if err != nil {
			return ExecResult{}, err
		}
		info, err := os.Stat(resolved)
		if err != nil {
			return ExecResult{}, err
		}
		if !info.IsDir() {
			return ExecResult{}, errors.New("dir is not a directory")
		}
		dir = resolved
	}

	ctx, cancel := context.WithTimeout(context.Background(), n.cfg.CommandTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, cmdName, req.Args...)
	cmd.Dir = dir
	var out, stderr strings.Builder
	cmd.Stdout = &out
	cmd.Stderr = &stderr
	err := cmd.Run()
	exit := 0
	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			err = fmt.Errorf("command timed out after %s", n.cfg.CommandTimeout)
			exit = -1
		} else if ee := new(exec.ExitError); errors.As(err, &ee) {
			exit = ee.ExitCode()
		} else {
			exit = -1
		}
	}
	result := ExecResult{Command: cmdName, Args: req.Args, ExitCode: exit, Stdout: out.String(), Stderr: stderr.String(), DurationMS: time.Since(started).Milliseconds()}
	n.record("execute_command", map[string]any{"command": cmdName, "args": req.Args, "exit_code": exit}, err == nil, err)
	return result, err
}

func (n *Node) commandAllowed(command string) error {
	switch n.cfg.ExecMode {
	case "disabled":
		return errors.New("command execution is disabled")
	case "admin":
		return nil
	case "restricted":
		base := strings.ToLower(filepath.Base(command))
		base = strings.TrimSuffix(base, ".exe")
		if _, ok := n.cfg.AllowedCommands[base]; ok {
			return nil
		}
		return fmt.Errorf("command %q is not in HERMES_NODE_ALLOWED_COMMANDS", base)
	default:
		return errors.New("invalid execution mode")
	}
}

func (n *Node) ResolvePath(input string) (string, error) {
	if strings.TrimSpace(input) == "" {
		return "", errors.New("path is required")
	}
	expanded := input
	if input == "~" || strings.HasPrefix(input, "~/") || strings.HasPrefix(input, `~\`) {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if input == "~" {
			expanded = home
		} else {
			expanded = filepath.Join(home, input[2:])
		}
	}
	abs, err := filepath.Abs(expanded)
	if err != nil {
		return "", err
	}
	abs = filepath.Clean(abs)
	for _, root := range n.cfg.AllowedRoots {
		rel, err := filepath.Rel(root, abs)
		if err != nil {
			continue
		}
		if rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(os.PathSeparator))) {
			return abs, nil
		}
	}
	return "", fmt.Errorf("path %q is outside allowed roots", abs)
}

type FileEntry struct {
	Name     string    `json:"name"`
	Path     string    `json:"path"`
	IsDir    bool      `json:"is_dir"`
	Size     int64     `json:"size"`
	Mode     string    `json:"mode"`
	Modified time.Time `json:"modified"`
}

func (n *Node) ListDirectory(path string) ([]FileEntry, error) {
	resolved, err := n.ResolvePath(path)
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(resolved)
	if err != nil {
		return nil, err
	}
	out := make([]FileEntry, 0, len(entries))
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, FileEntry{Name: e.Name(), Path: filepath.Join(resolved, e.Name()), IsDir: e.IsDir(), Size: info.Size(), Mode: info.Mode().String(), Modified: info.ModTime()})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	n.record("list_directory", map[string]any{"path": resolved, "count": len(out)}, true, nil)
	return out, nil
}

func (n *Node) ReadFile(path string) (string, error) {
	resolved, err := n.ResolvePath(path)
	if err != nil {
		return "", err
	}
	f, err := os.Open(resolved)
	if err != nil {
		return "", err
	}
	defer f.Close()
	lr := io.LimitReader(f, n.cfg.MaxReadBytes+1)
	b, err := io.ReadAll(lr)
	if err != nil {
		return "", err
	}
	if int64(len(b)) > n.cfg.MaxReadBytes {
		return "", fmt.Errorf("file exceeds max read size of %d bytes", n.cfg.MaxReadBytes)
	}
	if !isText(b) {
		return "", errors.New("binary files are not supported in v0.2")
	}
	n.record("read_file", map[string]any{"path": resolved, "bytes": len(b)}, true, nil)
	return string(b), nil
}

func (n *Node) WriteFile(path, content string, appendMode bool) error {
	if int64(len(content)) > n.cfg.MaxWriteBytes {
		return fmt.Errorf("content exceeds max write size of %d bytes", n.cfg.MaxWriteBytes)
	}
	resolved, err := n.ResolvePath(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(resolved), 0755); err != nil {
		return err
	}
	flags := os.O_CREATE | os.O_WRONLY | os.O_TRUNC
	if appendMode {
		flags = os.O_CREATE | os.O_WRONLY | os.O_APPEND
	}
	f, err := os.OpenFile(resolved, flags, 0644)
	if err != nil {
		return err
	}
	_, writeErr := io.WriteString(f, content)
	closeErr := f.Close()
	if writeErr != nil {
		err = writeErr
	} else {
		err = closeErr
	}
	n.record("write_file", map[string]any{"path": resolved, "bytes": len(content), "append": appendMode}, err == nil, err)
	return err
}

func isText(b []byte) bool {
	for _, c := range b {
		if c == 0 {
			return false
		}
	}
	return true
}

func (n *Node) Processes() (any, error) {
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("tasklist", "/FO", "CSV", "/NH")
	} else {
		cmd = exec.Command("ps", "-eo", "pid=,ppid=,comm=,%cpu=,%mem=")
	}
	b, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	if runtime.GOOS == "windows" {
		lines := strings.Split(strings.TrimSpace(string(b)), "\n")
		n.record("list_processes", map[string]any{"count": len(lines)}, true, nil)
		return map[string]any{"format": "tasklist_csv", "data": strings.TrimSpace(string(b))}, nil
	}
	type proc struct {
		PID, PPID int
		Command   string
		CPU, Mem  string
	}
	var out []proc
	scanner := bufio.NewScanner(strings.NewReader(string(b)))
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 5 {
			continue
		}
		pid, _ := strconv.Atoi(fields[0])
		ppid, _ := strconv.Atoi(fields[1])
		out = append(out, proc{PID: pid, PPID: ppid, Command: fields[2], CPU: fields[3], Mem: fields[4]})
	}
	n.record("list_processes", map[string]any{"count": len(out)}, true, nil)
	return out, scanner.Err()
}

func (n *Node) Logs(limit int) []AuditEntry {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if limit > len(n.audit) {
		limit = len(n.audit)
	}
	out := make([]AuditEntry, limit)
	copy(out, n.audit[len(n.audit)-limit:])
	return out
}

func (n *Node) record(action string, detail map[string]any, ok bool, err error) {
	e := AuditEntry{Time: time.Now().UTC(), Action: action, Detail: detail, OK: ok}
	if err != nil {
		e.Error = err.Error()
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.audit) >= 200 {
		copy(n.audit, n.audit[1:])
		n.audit = n.audit[:199]
	}
	n.audit = append(n.audit, e)
}

func JSON(v any) string { b, _ := json.MarshalIndent(v, "", "  "); return string(b) }
