package config

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

type Config struct {
	Name            string
	Listen          string
	Token           string
	TokenGenerated  bool
	AllowedRoots    []string
	ExecMode        string
	AllowedCommands map[string]struct{}
	CommandTimeout  time.Duration
	MaxReadBytes    int64
	MaxWriteBytes   int64
	AllowedRemoteCIDRs []string
}

func Load() (Config, error) {
	host, _ := os.Hostname()
	if host == "" {
		host = "hermes-node"
	}

	token := strings.TrimSpace(os.Getenv("HERMES_NODE_TOKEN"))
	generated := false
	if token == "" {
		b := make([]byte, 32)
		if _, err := rand.Read(b); err != nil {
			return Config{}, err
		}
		token = hex.EncodeToString(b)
		generated = true
	}

	roots := parseRoots(os.Getenv("HERMES_NODE_ALLOWED_DIRS"))
	if len(roots) == 0 {
		if home, err := os.UserHomeDir(); err == nil && home != "" {
			roots = []string{home}
		}
	}

	mode := strings.ToLower(strings.TrimSpace(os.Getenv("HERMES_NODE_EXEC_MODE")))
	if mode == "" {
		mode = "restricted"
	}
	if mode != "disabled" && mode != "restricted" && mode != "admin" {
		mode = "restricted"
	}

	allowed := map[string]struct{}{}
	raw := os.Getenv("HERMES_NODE_ALLOWED_COMMANDS")
	if strings.TrimSpace(raw) == "" {
		raw = "git,docker,hermes,adb,ping"
	}
	for _, item := range strings.Split(raw, ",") {
		item = strings.ToLower(strings.TrimSpace(item))
		if item != "" {
			allowed[item] = struct{}{}
		}
	}

	return Config{
		Name:            getenv("HERMES_NODE_NAME", host),
		Listen:          getenv("HERMES_NODE_LISTEN", "127.0.0.1:9090"),
		Token:           token,
		TokenGenerated:  generated,
		AllowedRoots:    roots,
		ExecMode:        mode,
		AllowedCommands: allowed,
		CommandTimeout:  parseDuration("HERMES_NODE_COMMAND_TIMEOUT", 30*time.Second),
		MaxReadBytes:    parseBytes("HERMES_NODE_MAX_READ_BYTES", 1024*1024),
		MaxWriteBytes:   parseBytes("HERMES_NODE_MAX_WRITE_BYTES", 1024*1024),
		AllowedRemoteCIDRs: parseCSV("HERMES_NODE_ALLOWED_REMOTE_CIDRS"),
	}, nil
}

func parseRoots(v string) []string {
	if strings.TrimSpace(v) == "" {
		return nil
	}
	parts := filepath.SplitList(v)
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			if abs, err := filepath.Abs(p); err == nil {
				out = append(out, filepath.Clean(abs))
			}
		}
	}
	return out
}


func parseCSV(key string) []string {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return nil
	}
	out := []string{}
	for _, item := range strings.Split(raw, ",") {
		item = strings.TrimSpace(item)
		if item != "" {
			out = append(out, item)
		}
	}
	return out
}

func parseDuration(key string, fallback time.Duration) time.Duration {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		if d, err := time.ParseDuration(v); err == nil && d > 0 {
			return d
		}
	}
	return fallback
}

func parseBytes(key string, fallback int64) int64 {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		var n int64
		if _, err := fmtSscan(v, &n); err == nil && n > 0 {
			return n
		}
	}
	return fallback
}

func getenv(k, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return fallback
}

// Small indirection keeps config dependency-free and easy to cross-compile.
func fmtSscan(s string, dst *int64) (int, error) {
	var n int64
	neg := false
	if s == "" {
		return 0, os.ErrInvalid
	}
	for i, r := range s {
		if i == 0 && r == '-' {
			neg = true
			continue
		}
		if r < '0' || r > '9' {
			return 0, os.ErrInvalid
		}
		n = n*10 + int64(r-'0')
	}
	if neg {
		n = -n
	}
	*dst = n
	return 1, nil
}

func Platform() string { return runtime.GOOS + "/" + runtime.GOARCH }
