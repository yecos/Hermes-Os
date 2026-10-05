package mcp

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"

	"github.com/yecos/Hermes-Os/hermes-node/internal/core"
)

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      any             `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type response struct {
	JSONRPC string    `json:"jsonrpc"`
	ID      any       `json:"id,omitempty"`
	Result  any       `json:"result,omitempty"`
	Error   *rpcError `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data,omitempty"`
}

type Server struct {
	node *core.Node
	in   io.Reader
	out  io.Writer
}

func New(node *core.Node) *Server {
	return &Server{node: node, in: os.Stdin, out: os.Stdout}
}

func NewIO(node *core.Node, in io.Reader, out io.Writer) *Server {
	return &Server{node: node, in: in, out: out}
}

func (s *Server) Serve() error {
	scanner := bufio.NewScanner(s.in)
	scanner.Buffer(make([]byte, 4096), 4<<20)
	enc := json.NewEncoder(s.out)

	for scanner.Scan() {
		var req request
		if err := json.Unmarshal(scanner.Bytes(), &req); err != nil {
			_ = enc.Encode(response{
				JSONRPC: "2.0",
				Error:   &rpcError{Code: -32700, Message: "parse error"},
			})
			continue
		}

		if req.Method == "notifications/initialized" {
			continue
		}

		resp := s.handle(req)
		if req.ID != nil {
			if err := enc.Encode(resp); err != nil {
				return err
			}
		}
	}

	return scanner.Err()
}

func (s *Server) handle(req request) response {
	res := response{JSONRPC: "2.0", ID: req.ID}

	switch req.Method {
	case "initialize":
		res.Result = map[string]any{
			"protocolVersion": "2025-11-25",
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": "hermes-commander", "version": "0.2.0"},
		}
	case "ping":
		res.Result = map[string]any{}
	case "tools/list":
		res.Result = map[string]any{"tools": tools()}
	case "tools/call":
		var p struct {
			Name      string         `json:"name"`
			Arguments map[string]any `json:"arguments"`
		}
		if err := json.Unmarshal(req.Params, &p); err != nil {
			return fail(req.ID, -32602, err.Error())
		}
		text, err := s.call(p.Name, p.Arguments)
		if err != nil {
			res.Result = toolResult(err.Error(), true)
		} else {
			res.Result = toolResult(text, false)
		}
	default:
		return fail(req.ID, -32601, "method not found")
	}

	return res
}

func toolResult(text string, isErr bool) map[string]any {
	return map[string]any{
		"content": []map[string]any{{"type": "text", "text": text}},
		"isError": isErr,
	}
}

func fail(id any, code int, msg string) response {
	return response{
		JSONRPC: "2.0",
		ID:      id,
		Error:   &rpcError{Code: code, Message: msg},
	}
}

func stringArg(a map[string]any, key string) string {
	v, _ := a[key].(string)
	return v
}

func boolArg(a map[string]any, key string) bool {
	v, _ := a[key].(bool)
	return v
}

func intArg(a map[string]any, key string, fallback int) int {
	if v, ok := a[key].(float64); ok {
		return int(v)
	}
	return fallback
}

func stringSliceArg(a map[string]any, key string) []string {
	out := []string{}
	raw, ok := a[key].([]any)
	if !ok {
		return out
	}
	for _, v := range raw {
		out = append(out, fmt.Sprint(v))
	}
	return out
}

func (s *Server) call(name string, a map[string]any) (string, error) {
	switch name {
	case "system_status":
		return core.JSON(s.node.Device()), nil

	case "execute_command":
		r, err := s.node.Execute(core.ExecRequest{
			Command: stringArg(a, "command"),
			Args:    stringSliceArg(a, "args"),
			Dir:     stringArg(a, "dir"),
		})
		return core.JSON(r), err

	case "list_directory":
		v, err := s.node.ListDirectory(stringArg(a, "path"))
		return core.JSON(v), err

	case "get_file_info":
		v, err := s.node.GetFileInfo(stringArg(a, "path"))
		return core.JSON(v), err

	case "create_directory":
		err := s.node.CreateDirectory(stringArg(a, "path"))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "read_file":
		return s.node.ReadFile(stringArg(a, "path"))

	case "read_multiple_files":
		paths := stringSliceArg(a, "paths")
		out := map[string]any{}
		for _, path := range paths {
			value, err := s.node.ReadFile(path)
			if err != nil {
				out[path] = map[string]any{"error": err.Error()}
				continue
			}
			out[path] = map[string]any{"content": value}
		}
		return core.JSON(out), nil

	case "write_file":
		err := s.node.WriteFile(
			stringArg(a, "path"),
			stringArg(a, "content"),
			boolArg(a, "append"),
		)
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "edit_block":
		v, err := s.node.EditBlock(
			stringArg(a, "path"),
			stringArg(a, "old_text"),
			stringArg(a, "new_text"),
			intArg(a, "expected_replacements", 1),
		)
		return core.JSON(v), err

	case "move_file":
		err := s.node.MovePath(
			stringArg(a, "source"),
			stringArg(a, "destination"),
		)
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "delete_path":
		err := s.node.DeletePath(
			stringArg(a, "path"),
			boolArg(a, "recursive"),
		)
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "search_files":
		v, err := s.node.Search(core.SearchRequest{
			Path:       stringArg(a, "path"),
			Pattern:    stringArg(a, "pattern"),
			SearchType: stringArg(a, "search_type"),
			MaxResults: intArg(a, "max_results", 100),
			MaxDepth:   intArg(a, "max_depth", 12),
		})
		return core.JSON(v), err

	case "list_processes":
		v, err := s.node.Processes()
		return core.JSON(v), err

	case "kill_process":
		err := s.node.KillProcess(intArg(a, "pid", 0))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "start_process":
		v, err := s.node.StartProcess(core.ExecRequest{
			Command: stringArg(a, "command"),
			Args:    stringSliceArg(a, "args"),
			Dir:     stringArg(a, "dir"),
		})
		return core.JSON(v), err

	case "read_process_output":
		v, err := s.node.ReadProcessOutput(stringArg(a, "session_id"))
		return core.JSON(v), err

	case "interact_with_process":
		v, err := s.node.SendProcessInput(
			stringArg(a, "session_id"),
			stringArg(a, "input"),
			boolArg(a, "newline"),
		)
		return core.JSON(v), err

	case "force_terminate":
		v, err := s.node.StopProcess(stringArg(a, "session_id"))
		return core.JSON(v), err

	case "list_sessions":
		return core.JSON(s.node.ListSessions()), nil

	case "logs":
		return core.JSON(s.node.Logs(intArg(a, "limit", 50))), nil

	default:
		return "", fmt.Errorf("unknown tool %q", name)
	}
}

func schema(properties map[string]any, required ...string) map[string]any {
	out := map[string]any{
		"type":       "object",
		"properties": properties,
	}
	if len(required) > 0 {
		out["required"] = required
	}
	return out
}

func tools() []map[string]any {
	stringType := func() map[string]any { return map[string]any{"type": "string"} }
	boolType := func() map[string]any { return map[string]any{"type": "boolean"} }
	intType := func() map[string]any { return map[string]any{"type": "integer"} }
	stringArray := func() map[string]any {
		return map[string]any{"type": "array", "items": map[string]any{"type": "string"}}
	}

	return []map[string]any{
		{
			"name":        "system_status",
			"description": "Return Hermes Commander device status, policy and capabilities.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "execute_command",
			"description": "Execute one local command using the Hermes Node command policy.",
			"inputSchema": schema(map[string]any{
				"command": stringType(),
				"args":    stringArray(),
				"dir":     stringType(),
			}, "command"),
		},
		{
			"name":        "list_directory",
			"description": "List files and directories inside an allowed root.",
			"inputSchema": schema(map[string]any{"path": stringType()}, "path"),
		},
		{
			"name":        "get_file_info",
			"description": "Return metadata for a file or directory.",
			"inputSchema": schema(map[string]any{"path": stringType()}, "path"),
		},
		{
			"name":        "create_directory",
			"description": "Create a directory, including missing parent directories.",
			"inputSchema": schema(map[string]any{"path": stringType()}, "path"),
		},
		{
			"name":        "read_file",
			"description": "Read a UTF-8 text file inside an allowed root.",
			"inputSchema": schema(map[string]any{"path": stringType()}, "path"),
		},
		{
			"name":        "read_multiple_files",
			"description": "Read several UTF-8 text files in one call.",
			"inputSchema": schema(map[string]any{"paths": stringArray()}, "paths"),
		},
		{
			"name":        "write_file",
			"description": "Create, replace or append a UTF-8 text file.",
			"inputSchema": schema(map[string]any{
				"path":    stringType(),
				"content": stringType(),
				"append":  boolType(),
			}, "path", "content"),
		},
		{
			"name":        "edit_block",
			"description": "Apply a surgical exact-text replacement to a UTF-8 text file.",
			"inputSchema": schema(map[string]any{
				"path":                  stringType(),
				"old_text":              stringType(),
				"new_text":              stringType(),
				"expected_replacements": intType(),
			}, "path", "old_text", "new_text"),
		},
		{
			"name":        "move_file",
			"description": "Move or rename a file or directory inside allowed roots.",
			"inputSchema": schema(map[string]any{
				"source":      stringType(),
				"destination": stringType(),
			}, "source", "destination"),
		},
		{
			"name":        "delete_path",
			"description": "Delete a file or directory. Recursive deletion must be explicitly requested.",
			"inputSchema": schema(map[string]any{
				"path":      stringType(),
				"recursive": boolType(),
			}, "path"),
		},
		{
			"name":        "search_files",
			"description": "Search file names or text content recursively within an allowed directory.",
			"inputSchema": schema(map[string]any{
				"path":        stringType(),
				"pattern":     stringType(),
				"search_type": map[string]any{"type": "string", "enum": []string{"files", "content"}},
				"max_results": intType(),
				"max_depth":   intType(),
			}, "path", "pattern"),
		},
		{
			"name":        "list_processes",
			"description": "List running operating-system processes.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "kill_process",
			"description": "Terminate a running operating-system process by PID.",
			"inputSchema": schema(map[string]any{"pid": intType()}, "pid"),
		},
		{
			"name":        "start_process",
			"description": "Start a persistent local process or interactive command session.",
			"inputSchema": schema(map[string]any{
				"command": stringType(),
				"args":    stringArray(),
				"dir":     stringType(),
			}, "command"),
		},
		{
			"name":        "read_process_output",
			"description": "Read accumulated output and status from a persistent session.",
			"inputSchema": schema(map[string]any{"session_id": stringType()}, "session_id"),
		},
		{
			"name":        "interact_with_process",
			"description": "Send input to a persistent process session.",
			"inputSchema": schema(map[string]any{
				"session_id": stringType(),
				"input":      stringType(),
				"newline":    boolType(),
			}, "session_id", "input"),
		},
		{
			"name":        "force_terminate",
			"description": "Force terminate a persistent process session.",
			"inputSchema": schema(map[string]any{"session_id": stringType()}, "session_id"),
		},
		{
			"name":        "list_sessions",
			"description": "List persistent process sessions started through Hermes Commander.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "logs",
			"description": "Return recent Hermes Commander audit entries.",
			"inputSchema": schema(map[string]any{
				"limit": map[string]any{"type": "integer", "minimum": 1, "maximum": 200},
			}),
		},
	}
}
