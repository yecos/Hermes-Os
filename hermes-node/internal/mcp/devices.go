package mcp

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/yecos/Hermes-Os/hermes-node/internal/core"
)

type DeviceEntry struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	URL     string `json:"url"`
	Token   string `json:"token"`
	Enabled bool   `json:"enabled"`
}

type deviceRegistryFile struct {
	Devices []DeviceEntry `json:"devices"`
}

type DeviceSummary struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	URL          string   `json:"url,omitempty"`
	Local        bool     `json:"local"`
	Enabled      bool     `json:"enabled"`
	Online       bool     `json:"online"`
	OS           string   `json:"os,omitempty"`
	Arch         string   `json:"arch,omitempty"`
	Version      string   `json:"version,omitempty"`
	Capabilities []string `json:"capabilities,omitempty"`
	Error        string   `json:"error,omitempty"`
}

type DeviceManager struct {
	node         *core.Node
	registryPath string
	client       *http.Client

	mu      sync.RWMutex
	devices map[string]DeviceEntry
	active  string
}

func NewDeviceManager(node *core.Node) *DeviceManager {
	path := strings.TrimSpace(os.Getenv("HERMES_COMMANDER_DEVICES_FILE"))
	if path == "" {
		home, _ := os.UserHomeDir()
		path = filepath.Join(home, ".hermes-node", "devices.json")
	}
	m := &DeviceManager{
		node:         node,
		registryPath: path,
		client:       &http.Client{Timeout: 6 * time.Second},
		devices:      map[string]DeviceEntry{},
		active:       "local",
	}
	_ = m.load()
	return m
}

func (m *DeviceManager) load() error {
	b, err := os.ReadFile(m.registryPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var f deviceRegistryFile
	if err := json.Unmarshal(b, &f); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, d := range f.Devices {
		if strings.TrimSpace(d.ID) == "" || strings.TrimSpace(d.URL) == "" || strings.TrimSpace(d.Token) == "" {
			continue
		}
		if !d.Enabled {
			d.Enabled = true
		}
		m.devices[d.ID] = d
	}
	return nil
}

func (m *DeviceManager) saveLocked() error {
	if err := os.MkdirAll(filepath.Dir(m.registryPath), 0700); err != nil {
		return err
	}
	items := make([]DeviceEntry, 0, len(m.devices))
	for _, d := range m.devices {
		items = append(items, d)
	}
	sort.Slice(items, func(i, j int) bool { return items[i].ID < items[j].ID })
	b, err := json.MarshalIndent(deviceRegistryFile{Devices: items}, "", "  ")
	if err != nil {
		return err
	}
	tmp := m.registryPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, m.registryPath)
}

func normalizeDeviceID(v string) string {
	v = strings.ToLower(strings.TrimSpace(v))
	var b strings.Builder
	for _, r := range v {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
			b.WriteRune(r)
		}
	}
	return strings.Trim(b.String(), "-_")
}

func validatePrivateNodeURL(raw string) (string, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", errors.New("url is required")
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", errors.New("node URL must use http or https")
	}
	if u.User != nil {
		return "", errors.New("credentials must not be embedded in node URL")
	}
	host := strings.TrimSpace(u.Hostname())
	if host == "" {
		return "", errors.New("node URL host is required")
	}
	allowed := false
	lower := strings.ToLower(host)
	if lower == "localhost" || strings.HasSuffix(lower, ".ts.net") {
		allowed = true
	}
	if ip := net.ParseIP(host); ip != nil {
		addr, ok := netip.AddrFromSlice(ip)
		if ok {
			addr = addr.Unmap()
			tailnet := netip.MustParsePrefix("100.64.0.0/10")
			if addr.IsLoopback() || addr.IsPrivate() || tailnet.Contains(addr) {
				allowed = true
			}
		}
	}
	if !allowed {
		return "", errors.New("remote Hermes Nodes are restricted to loopback, private LAN, or Tailscale addresses")
	}
	u.Path = strings.TrimRight(u.Path, "/")
	u.RawQuery = ""
	u.Fragment = ""
	return strings.TrimRight(u.String(), "/"), nil
}

func (m *DeviceManager) Add(id, name, rawURL, token string) (DeviceSummary, error) {
	id = normalizeDeviceID(id)
	if id == "" || id == "local" {
		return DeviceSummary{}, errors.New("device_id must be a non-local identifier")
	}
	rawURL, err := validatePrivateNodeURL(rawURL)
	if err != nil {
		return DeviceSummary{}, err
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return DeviceSummary{}, errors.New("token is required")
	}
	entry := DeviceEntry{ID: id, Name: strings.TrimSpace(name), URL: rawURL, Token: token, Enabled: true}
	if entry.Name == "" {
		entry.Name = id
	}

	summary, err := m.pingEntry(entry)
	if err != nil {
		return DeviceSummary{}, fmt.Errorf("node verification failed: %w", err)
	}

	m.mu.Lock()
	m.devices[id] = entry
	err = m.saveLocked()
	m.mu.Unlock()
	if err != nil {
		return DeviceSummary{}, err
	}
	summary.ID = id
	summary.Name = entry.Name
	summary.URL = entry.URL
	return summary, nil
}

func (m *DeviceManager) Remove(id string) error {
	id = normalizeDeviceID(id)
	if id == "" || id == "local" {
		return errors.New("local device cannot be removed")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.devices[id]; !ok {
		return fmt.Errorf("device %q is not registered", id)
	}
	delete(m.devices, id)
	if m.active == id {
		m.active = "local"
	}
	return m.saveLocked()
}

func (m *DeviceManager) Select(id string) (DeviceSummary, error) {
	id = normalizeDeviceID(id)
	if id == "" {
		return DeviceSummary{}, errors.New("device_id is required")
	}
	s, err := m.Ping(id)
	if err != nil {
		return DeviceSummary{}, err
	}
	m.mu.Lock()
	m.active = id
	m.mu.Unlock()
	return s, nil
}

func (m *DeviceManager) Active() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.active
}

func (m *DeviceManager) Current() DeviceSummary {
	id := m.Active()
	s, err := m.Ping(id)
	if err != nil {
		return DeviceSummary{ID: id, Name: id, Local: id == "local", Enabled: true, Online: false, Error: err.Error()}
	}
	return s
}

func (m *DeviceManager) List() []DeviceSummary {
	items := []DeviceSummary{}
	local, err := m.Ping("local")
	if err != nil {
		local = DeviceSummary{ID: "local", Name: m.node.Config().Name, Local: true, Enabled: true, Online: false, Error: err.Error()}
	}
	items = append(items, local)

	m.mu.RLock()
	entries := make([]DeviceEntry, 0, len(m.devices))
	for _, d := range m.devices {
		entries = append(entries, d)
	}
	m.mu.RUnlock()
	sort.Slice(entries, func(i, j int) bool { return entries[i].ID < entries[j].ID })

	for _, d := range entries {
		s, err := m.pingEntry(d)
		if err != nil {
			s = DeviceSummary{ID: d.ID, Name: d.Name, URL: d.URL, Enabled: d.Enabled, Online: false, Error: err.Error()}
		}
		items = append(items, s)
	}
	return items
}

func (m *DeviceManager) Ping(id string) (DeviceSummary, error) {
	id = normalizeDeviceID(id)
	if id == "" {
		id = m.Active()
	}
	if id == "local" {
		d := m.node.Device()
		return summaryFromDeviceMap("local", m.node.Config().Name, "", true, d), nil
	}
	m.mu.RLock()
	entry, ok := m.devices[id]
	m.mu.RUnlock()
	if !ok {
		return DeviceSummary{}, fmt.Errorf("device %q is not registered", id)
	}
	return m.pingEntry(entry)
}

func (m *DeviceManager) pingEntry(entry DeviceEntry) (DeviceSummary, error) {
	req, err := http.NewRequest(http.MethodGet, entry.URL+"/v1/device", nil)
	if err != nil {
		return DeviceSummary{}, err
	}
	req.Header.Set("Authorization", "Bearer "+entry.Token)
	resp, err := m.client.Do(req)
	if err != nil {
		return DeviceSummary{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return DeviceSummary{}, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	var d map[string]any
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2<<20)).Decode(&d); err != nil {
		return DeviceSummary{}, err
	}
	return summaryFromDeviceMap(entry.ID, entry.Name, entry.URL, false, d), nil
}

func summaryFromDeviceMap(id, name, rawURL string, local bool, d map[string]any) DeviceSummary {
	s := DeviceSummary{ID: id, Name: name, URL: rawURL, Local: local, Enabled: true, Online: true}
	if v, ok := d["name"].(string); ok && strings.TrimSpace(v) != "" {
		s.Name = v
	}
	s.OS, _ = d["os"].(string)
	s.Arch, _ = d["arch"].(string)
	s.Version, _ = d["version"].(string)
	if raw, ok := d["capabilities"].([]any); ok {
		for _, v := range raw {
			if x, ok := v.(string); ok {
				s.Capabilities = append(s.Capabilities, x)
			}
		}
	}
	return s
}

type remoteToolImage struct {
	Data       string    `json:"data"`
	MIMEType   string    `json:"mime_type"`
	Format     string    `json:"format"`
	Display    int       `json:"display"`
	Left       int       `json:"left"`
	Top        int       `json:"top"`
	Width      int       `json:"width"`
	Height     int       `json:"height"`
	CapturedAt time.Time `json:"captured_at"`
}

type remoteToolResponse struct {
	OK    bool             `json:"ok"`
	Text  string           `json:"text,omitempty"`
	Image *remoteToolImage `json:"image,omitempty"`
	Error string           `json:"error,omitempty"`
}

func (m *DeviceManager) Call(name string, args map[string]any) (core.ToolCallResult, string, error) {
	target := normalizeDeviceID(stringArg(args, "device_id"))
	if target == "" {
		target = m.Active()
	}
	clean := make(map[string]any, len(args))
	for k, v := range args {
		if k != "device_id" {
			clean[k] = v
		}
	}

	if target == "local" {
		result, err := m.node.CallTool(name, clean)
		return result, target, err
	}

	m.mu.RLock()
	entry, ok := m.devices[target]
	m.mu.RUnlock()
	if !ok {
		return core.ToolCallResult{}, target, fmt.Errorf("device %q is not registered", target)
	}

	body, err := json.Marshal(clean)
	if err != nil {
		return core.ToolCallResult{}, target, err
	}
	req, err := http.NewRequest(http.MethodPost, entry.URL+"/v1/tool/"+url.PathEscape(name), bytes.NewReader(body))
	if err != nil {
		return core.ToolCallResult{}, target, err
	}
	req.Header.Set("Authorization", "Bearer "+entry.Token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := m.client.Do(req)
	if err != nil {
		return core.ToolCallResult{}, target, err
	}
	defer resp.Body.Close()
	var wire remoteToolResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, 24<<20)).Decode(&wire); err != nil {
		return core.ToolCallResult{}, target, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 || !wire.OK {
		if wire.Error == "" {
			wire.Error = fmt.Sprintf("remote node returned HTTP %d", resp.StatusCode)
		}
		return core.ToolCallResult{}, target, errors.New(wire.Error)
	}
	out := core.ToolCallResult{Text: wire.Text}
	if wire.Image != nil {
		data, err := base64.StdEncoding.DecodeString(wire.Image.Data)
		if err != nil {
			return core.ToolCallResult{}, target, err
		}
		shot := core.Screenshot{
			Data:       data,
			MIMEType:   wire.Image.MIMEType,
			Format:     wire.Image.Format,
			Display:    wire.Image.Display,
			Left:       wire.Image.Left,
			Top:        wire.Image.Top,
			Width:      wire.Image.Width,
			Height:     wire.Image.Height,
			CapturedAt: wire.Image.CapturedAt,
		}
		out.Image = &shot
	}
	return out, target, nil
}
