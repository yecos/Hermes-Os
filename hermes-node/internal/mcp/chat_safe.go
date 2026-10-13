package mcp

// ChatSafeServer is a deliberately separate, read-only MCP entrypoint for
// ChatGPT Chat and mobile access through a private Secure MCP Tunnel.
//
// This is NOT a filtered tools/list around the full Hermes Commander: it has
// no code path that can call Execute, write files, alter UI, or reach remote
// devices. Even manually calling a hidden tool returns a tool error.
import (
    "bufio"
    "encoding/json"
    "io"
    "os"
    "runtime"

    "github.com/yecos/Hermes-Os/hermes-node/internal/core"
)

type ChatSafeServer struct {
    node *core.Node
    in io.Reader
    out io.Writer
}

func NewChatSafe(node *core.Node) *ChatSafeServer {
    return NewChatSafeIO(node, os.Stdin, os.Stdout)
}

func NewChatSafeIO(node *core.Node, in io.Reader, out io.Writer) *ChatSafeServer {
    return &ChatSafeServer{node: node, in: in, out: out}
}

func (s *ChatSafeServer) Serve() error {
    scanner := bufio.NewScanner(s.in)
    scanner.Buffer(make([]byte, 4096), 1<<20)
    enc := json.NewEncoder(s.out)
    for scanner.Scan() {
        var req request
        if err := json.Unmarshal(scanner.Bytes(), &req); err != nil {
            if err := enc.Encode(response{JSONRPC: "2.0", Error: &rpcError{
                Code: -32700, Message: "parse error",
            }}); err != nil {
                return err
            }
            continue
        }
        if req.ID == nil {
            // MCP initialization notifications are not RPC calls.
            continue
        }
        if err := enc.Encode(s.handle(req)); err != nil {
            return err
        }
    }
    return scanner.Err()
}

func (s *ChatSafeServer) handle(req request) response {
    res := response{JSONRPC: "2.0", ID: req.ID}
    switch req.Method {
    case "initialize":
        res.Result = map[string]any{
            "protocolVersion": "2025-11-25",
            "capabilities": map[string]any{"tools": map[string]any{}},
            "serverInfo": map[string]any{
                "name": "hermes-commander-chat-safe",
                "version": "0.1.0",
            },
        }
    case "ping":
        res.Result = map[string]any{}
    case "tools/list":
        res.Result = map[string]any{"tools": []map[string]any{{
            "name": "system_status",
            "description": "Read-only status of this Hermes Node (no filesystem, shell or desktop access).",
            "inputSchema": map[string]any{
                "type": "object",
                "properties": map[string]any{},
                "additionalProperties": false,
            },
            "annotations": map[string]any{"readOnlyHint": true},
        }}}
    case "tools/call":
        var p struct {
            Name string `json:"name"`
            Arguments map[string]any `json:"arguments"`
        }
        if err := json.Unmarshal(req.Params, &p); err != nil {
            return fail(req.ID, -32602, "invalid tool call")
        }
        if p.Name != "system_status" {
            res.Result = toolResult("tool not available in chat-safe mode", true)
            break
        }
        // Do not serialize s.node.Device(): it includes filesystem roots,
        // configured capabilities, process ids and machine paths.
        res.Result = toolResult(core.JSON(map[string]any{
            "name": s.node.Config().Name,
            "os": runtime.GOOS,
            "arch": runtime.GOARCH,
            "node_version": "0.5.0",
            "access": "read-only",
            "mode": "chat-safe",
        }), false)
    default:
        return fail(req.ID, -32601, "method not found")
    }
    return res
}
