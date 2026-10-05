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

func New(node *core.Node) *Server { return &Server{node: node, in: os.Stdin, out: os.Stdout} }
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
			_ = enc.Encode(response{JSONRPC: "2.0", Error: &rpcError{Code: -32700, Message: "parse error"}})
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
		res.Result = map[string]any{"protocolVersion": "2025-11-25", "capabilities": map[string]any{"tools": map[string]any{}}, "serverInfo": map[string]any{"name": "hermes-node", "version": "0.1.0"}}
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
	return map[string]any{"content": []map[string]any{{"type": "text", "text": text}}, "isError": isErr}
}
func fail(id any, code int, msg string) response {
	return response{JSONRPC: "2.0", ID: id, Error: &rpcError{Code: code, Message: msg}}
}

func (s *Server) call(name string, a map[string]any) (string, error) {
	str := func(k string) string { v, _ := a[k].(string); return v }
	switch name {
	case "system_status":
		return core.JSON(s.node.Device()), nil
	case "execute_command":
		args := []string{}
		if raw, ok := a["args"].([]any); ok {
			for _, v := range raw {
				args = append(args, fmt.Sprint(v))
			}
		}
		r, err := s.node.Execute(core.ExecRequest{Command: str("command"), Args: args, Dir: str("dir")})
		return core.JSON(r), err
	case "list_directory":
		v, e := s.node.ListDirectory(str("path"))
		return core.JSON(v), e
	case "read_file":
		v, e := s.node.ReadFile(str("path"))
		return v, e
	case "write_file":
		appendMode, _ := a["append"].(bool)
		e := s.node.WriteFile(str("path"), str("content"), appendMode)
		if e != nil {
			return "", e
		}
		return "ok", nil
	case "list_processes":
		v, e := s.node.Processes()
		return core.JSON(v), e
	case "logs":
		limit := 50
		if f, ok := a["limit"].(float64); ok {
			limit = int(f)
		}
		return core.JSON(s.node.Logs(limit)), nil
	default:
		return "", fmt.Errorf("unknown tool %q", name)
	}
}

func tools() []map[string]any {
	return []map[string]any{
		{"name": "system_status", "description": "Return Hermes Node device status and capabilities.", "inputSchema": map[string]any{"type": "object", "properties": map[string]any{}}},
		{"name": "execute_command", "description": "Execute a command using the node command policy.", "inputSchema": map[string]any{"type": "object", "required": []string{"command"}, "properties": map[string]any{"command": map[string]any{"type": "string"}, "args": map[string]any{"type": "array", "items": map[string]any{"type": "string"}}, "dir": map[string]any{"type": "string"}}}},
		{"name": "list_directory", "description": "List files inside an allowed directory.", "inputSchema": map[string]any{"type": "object", "required": []string{"path"}, "properties": map[string]any{"path": map[string]any{"type": "string"}}}},
		{"name": "read_file", "description": "Read a UTF-8 text file inside an allowed root.", "inputSchema": map[string]any{"type": "object", "required": []string{"path"}, "properties": map[string]any{"path": map[string]any{"type": "string"}}}},
		{"name": "write_file", "description": "Create, replace or append a text file inside an allowed root.", "inputSchema": map[string]any{"type": "object", "required": []string{"path", "content"}, "properties": map[string]any{"path": map[string]any{"type": "string"}, "content": map[string]any{"type": "string"}, "append": map[string]any{"type": "boolean"}}}},
		{"name": "list_processes", "description": "List running processes.", "inputSchema": map[string]any{"type": "object", "properties": map[string]any{}}},
		{"name": "logs", "description": "Return recent Hermes Node audit entries.", "inputSchema": map[string]any{"type": "object", "properties": map[string]any{"limit": map[string]any{"type": "integer", "minimum": 1, "maximum": 200}}}},
	}
}
