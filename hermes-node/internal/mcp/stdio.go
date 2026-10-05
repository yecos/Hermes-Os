package mcp

import (
	"bufio"
	"encoding/base64"
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
			"serverInfo":      map[string]any{"name": "hermes-commander", "version": "0.3.0"},
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

		if p.Name == "screenshot" {
			shot, err := s.node.Screenshot(core.ScreenshotRequest{
				Display: intArg(p.Arguments, "display", -1),
				Format:  stringArg(p.Arguments, "format"),
				Quality: intArg(p.Arguments, "quality", 82),
			})
			if err != nil {
				res.Result = toolResult(err.Error(), true)
			} else {
				res.Result = screenshotToolResult(shot)
			}
			break
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

func screenshotToolResult(shot core.Screenshot) map[string]any {
	meta := map[string]any{
		"display":     shot.Display,
		"left":        shot.Left,
		"top":         shot.Top,
		"width":       shot.Width,
		"height":      shot.Height,
		"format":      shot.Format,
		"captured_at": shot.CapturedAt,
	}
	return map[string]any{
		"content": []map[string]any{
			{"type": "text", "text": core.JSON(meta)},
			{
				"type":     "image",
				"data":     base64.StdEncoding.EncodeToString(shot.Data),
				"mimeType": shot.MIMEType,
			},
		},
		"isError": false,
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

	case "list_displays":
		v, err := s.node.Displays()
		return core.JSON(v), err

	case "list_windows":
		v, err := s.node.Windows()
		return core.JSON(v), err

	case "get_active_window":
		v, err := s.node.ActiveWindow()
		return core.JSON(v), err

	case "open_application":
		err := s.node.OpenApplication(core.OpenApplicationRequest{
			Target:     stringArg(a, "target"),
			Parameters: stringArg(a, "parameters"),
			Dir:        stringArg(a, "dir"),
		})
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "focus_window":
		v, err := s.node.FocusWindow(stringArg(a, "window"))
		return core.JSON(v), err

	case "close_window":
		err := s.node.CloseWindow(stringArg(a, "window"))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "mouse_move":
		err := s.node.MouseMove(intArg(a, "x", 0), intArg(a, "y", 0))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "mouse_click":
		err := s.node.MouseClick(stringArg(a, "button"), intArg(a, "count", 1))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "mouse_drag":
		err := s.node.MouseDrag(core.MouseDragRequest{
			StartX: intArg(a, "start_x", 0),
			StartY: intArg(a, "start_y", 0),
			EndX:   intArg(a, "end_x", 0),
			EndY:   intArg(a, "end_y", 0),
			Button: stringArg(a, "button"),
			Steps:  intArg(a, "steps", 12),
		})
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "mouse_scroll":
		err := s.node.MouseScroll(intArg(a, "delta", 0))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "key_press":
		err := s.node.KeyPress(stringArg(a, "key"))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "hotkey":
		err := s.node.Hotkey(stringSliceArg(a, "keys"))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "type_text":
		err := s.node.TypeText(stringArg(a, "text"))
		if err != nil {
			return "", err
		}
		return "ok", nil

	case "clipboard_read":
		return s.node.ClipboardRead()

	case "clipboard_write":
		err := s.node.ClipboardWrite(stringArg(a, "text"))
		if err != nil {
			return "", err
		}
		return "ok", nil

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

	base := []map[string]any{
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

	visual := []map[string]any{
		{
			"name":        "screenshot",
			"description": "Capture the Windows desktop as an image the model can inspect. Use display=-1 for the complete virtual desktop or a display index from list_displays.",
			"inputSchema": schema(map[string]any{
				"display": map[string]any{"type": "integer", "minimum": -1},
				"format":  map[string]any{"type": "string", "enum": []string{"jpeg", "png"}},
				"quality": map[string]any{"type": "integer", "minimum": 1, "maximum": 100},
			}),
		},
		{
			"name":        "list_displays",
			"description": "List Windows monitors and their virtual-desktop coordinates.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "list_windows",
			"description": "List visible top-level Windows windows with title, PID, handle and screen bounds.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "get_active_window",
			"description": "Return the currently foreground Windows window.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "open_application",
			"description": "Open an application, file, folder or URL through the Windows shell.",
			"inputSchema": schema(map[string]any{
				"target":     stringType(),
				"parameters": stringType(),
				"dir":        stringType(),
			}, "target"),
		},
		{
			"name":        "focus_window",
			"description": "Bring a visible window to the foreground by hexadecimal handle or partial title.",
			"inputSchema": schema(map[string]any{"window": stringType()}, "window"),
		},
		{
			"name":        "close_window",
			"description": "Request a visible window to close cleanly by hexadecimal handle or partial title.",
			"inputSchema": schema(map[string]any{"window": stringType()}, "window"),
		},
		{
			"name":        "mouse_move",
			"description": "Move the Windows mouse pointer to absolute virtual-desktop coordinates.",
			"inputSchema": schema(map[string]any{"x": intType(), "y": intType()}, "x", "y"),
		},
		{
			"name":        "mouse_click",
			"description": "Click the current mouse position with the left, right or middle button.",
			"inputSchema": schema(map[string]any{
				"button": map[string]any{"type": "string", "enum": []string{"left", "right", "middle"}},
				"count":  map[string]any{"type": "integer", "minimum": 1, "maximum": 3},
			}),
		},
		{
			"name":        "mouse_drag",
			"description": "Drag between two absolute virtual-desktop coordinates.",
			"inputSchema": schema(map[string]any{
				"start_x": intType(), "start_y": intType(),
				"end_x": intType(), "end_y": intType(),
				"button": map[string]any{"type": "string", "enum": []string{"left", "right", "middle"}},
				"steps":  map[string]any{"type": "integer", "minimum": 1, "maximum": 100},
			}, "start_x", "start_y", "end_x", "end_y"),
		},
		{
			"name":        "mouse_scroll",
			"description": "Scroll the mouse wheel. Positive values scroll up and negative values scroll down; each unit is one wheel notch.",
			"inputSchema": schema(map[string]any{"delta": intType()}, "delta"),
		},
		{
			"name":        "key_press",
			"description": "Press and release one named keyboard key.",
			"inputSchema": schema(map[string]any{"key": stringType()}, "key"),
		},
		{
			"name":        "hotkey",
			"description": "Press a keyboard shortcut such as [CTRL,L] or [ALT,TAB].",
			"inputSchema": schema(map[string]any{"keys": stringArray()}, "keys"),
		},
		{
			"name":        "type_text",
			"description": "Type Unicode text into the currently focused Windows control.",
			"inputSchema": schema(map[string]any{"text": stringType()}, "text"),
		},
		{
			"name":        "clipboard_read",
			"description": "Read Unicode text currently stored in the Windows clipboard.",
			"inputSchema": schema(map[string]any{}),
		},
		{
			"name":        "clipboard_write",
			"description": "Replace the Windows clipboard with Unicode text.",
			"inputSchema": schema(map[string]any{"text": stringType()}, "text"),
		},
	}

	return append(base, visual...)
}
