package httpapi

import (
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/core"
)

type Server struct {
	node *core.Node
	mux  *http.ServeMux
}

func New(node *core.Node) *Server {
	s := &Server{node: node, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) routes() {
	s.mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, r *http.Request) {
		jsonOut(w, 200, map[string]any{"ok": true, "time": time.Now().UTC()})
	})
	s.mux.Handle("GET /v1/device", s.auth(http.HandlerFunc(s.device)))
	s.mux.Handle("POST /v1/exec", s.auth(http.HandlerFunc(s.exec)))
	s.mux.Handle("GET /v1/files", s.auth(http.HandlerFunc(s.listFiles)))
	s.mux.Handle("GET /v1/file", s.auth(http.HandlerFunc(s.readFile)))
	s.mux.Handle("POST /v1/file", s.auth(http.HandlerFunc(s.writeFile)))
	s.mux.Handle("GET /v1/processes", s.auth(http.HandlerFunc(s.processes)))
	s.mux.Handle("GET /v1/logs", s.auth(http.HandlerFunc(s.logs)))
	s.mux.Handle("POST /v1/tool/{name}", s.auth(http.HandlerFunc(s.tool)))
}

func (s *Server) Handler() http.Handler { return s.mux }

func (s *Server) ListenAndServe() error {
	addr := s.node.Config().Listen
	log.Printf("Hermes Node listening on http://%s", addr)
	srv := &http.Server{Addr: addr, Handler: s.mux, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second}
	return srv.ListenAndServe()
}

func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		want := s.node.Config().Token
		if len(got) != len(want) || subtle.ConstantTimeCompare([]byte(got), []byte(want)) != 1 {
			jsonOut(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (s *Server) device(w http.ResponseWriter, r *http.Request) { jsonOut(w, 200, s.node.Device()) }
func (s *Server) exec(w http.ResponseWriter, r *http.Request) {
	var req core.ExecRequest
	if err := decode(r, &req); err != nil {
		jsonOut(w, 400, map[string]any{"error": err.Error()})
		return
	}
	result, err := s.node.Execute(req)
	if err != nil {
		jsonOut(w, 422, map[string]any{"error": err.Error(), "result": result})
		return
	}
	jsonOut(w, 200, result)
}
func (s *Server) listFiles(w http.ResponseWriter, r *http.Request) {
	v, err := s.node.ListDirectory(r.URL.Query().Get("path"))
	if err != nil {
		jsonOut(w, 400, map[string]any{"error": err.Error()})
		return
	}
	jsonOut(w, 200, v)
}
func (s *Server) readFile(w http.ResponseWriter, r *http.Request) {
	v, err := s.node.ReadFile(r.URL.Query().Get("path"))
	if err != nil {
		jsonOut(w, 400, map[string]any{"error": err.Error()})
		return
	}
	jsonOut(w, 200, map[string]any{"content": v})
}
func (s *Server) writeFile(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path    string `json:"path"`
		Content string `json:"content"`
		Append  bool   `json:"append"`
	}
	if err := decode(r, &req); err != nil {
		jsonOut(w, 400, map[string]any{"error": err.Error()})
		return
	}
	if err := s.node.WriteFile(req.Path, req.Content, req.Append); err != nil {
		jsonOut(w, 400, map[string]any{"error": err.Error()})
		return
	}
	jsonOut(w, 200, map[string]any{"ok": true})
}
func (s *Server) processes(w http.ResponseWriter, r *http.Request) {
	v, err := s.node.Processes()
	if err != nil {
		jsonOut(w, 500, map[string]any{"error": err.Error()})
		return
	}
	jsonOut(w, 200, v)
}
func (s *Server) logs(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	jsonOut(w, 200, s.node.Logs(limit))
}


func (s *Server) tool(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimSpace(r.PathValue("name"))
	if name == "" {
		jsonOut(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "tool name is required"})
		return
	}
	var args map[string]any
	if err := decode(r, &args); err != nil {
		jsonOut(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	result, err := s.node.CallTool(name, args)
	if err != nil {
		jsonOut(w, http.StatusUnprocessableEntity, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	out := map[string]any{"ok": true}
	if result.Text != "" {
		out["text"] = result.Text
	}
	if result.Image != nil {
		out["image"] = map[string]any{
			"data":        base64.StdEncoding.EncodeToString(result.Image.Data),
			"mime_type":   result.Image.MIMEType,
			"format":      result.Image.Format,
			"display":     result.Image.Display,
			"left":        result.Image.Left,
			"top":         result.Image.Top,
			"width":       result.Image.Width,
			"height":      result.Image.Height,
			"captured_at": result.Image.CapturedAt,
		}
	}
	jsonOut(w, http.StatusOK, out)
}

func decode(r *http.Request, v any) error {
	d := json.NewDecoder(http.MaxBytesReader(nil, r.Body, 2<<20))
	d.DisallowUnknownFields()
	if err := d.Decode(v); err != nil {
		return fmt.Errorf("invalid json: %w", err)
	}
	return nil
}
func jsonOut(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
