package core

import "time"

type UIQuery struct {
	Window       string `json:"window,omitempty"`
	Name         string `json:"name,omitempty"`
	AutomationID string `json:"automation_id,omitempty"`
	ControlType  string `json:"control_type,omitempty"`
	ClassName    string `json:"class_name,omitempty"`
	Contains     bool   `json:"contains,omitempty"`
	MaxDepth     int    `json:"max_depth,omitempty"`
	MaxResults   int    `json:"max_results,omitempty"`
}

type UIElement struct {
	ElementID       string   `json:"element_id"`
	Name            string   `json:"name,omitempty"`
	AutomationID    string   `json:"automation_id,omitempty"`
	ControlType     string   `json:"control_type,omitempty"`
	ClassName       string   `json:"class_name,omitempty"`
	FrameworkID     string   `json:"framework_id,omitempty"`
	ProcessID       int      `json:"process_id,omitempty"`
	NativeHandle    int      `json:"native_handle,omitempty"`
	Left            float64  `json:"left,omitempty"`
	Top             float64  `json:"top,omitempty"`
	Width           float64  `json:"width,omitempty"`
	Height          float64  `json:"height,omitempty"`
	Enabled         bool     `json:"enabled"`
	Offscreen       bool     `json:"offscreen"`
	Focusable       bool     `json:"focusable"`
	HasKeyboardFocus bool    `json:"has_keyboard_focus"`
	Depth           int      `json:"depth,omitempty"`
	Patterns        []string `json:"patterns,omitempty"`
}

type UISnapshot struct {
	Window      string      `json:"window,omitempty"`
	CapturedAt  time.Time   `json:"captured_at"`
	ElementCount int        `json:"element_count"`
	Elements    []UIElement `json:"elements"`
}

type UIActionResult struct {
	Action  string    `json:"action"`
	Element UIElement `json:"element"`
	OK      bool      `json:"ok"`
	Fallback string   `json:"fallback,omitempty"`
}

type ScreenRegionRequest struct {
	Left    int    `json:"left"`
	Top     int    `json:"top"`
	Width   int    `json:"width"`
	Height  int    `json:"height"`
	Format  string `json:"format"`
	Quality int    `json:"quality"`
}

type WindowScreenshotRequest struct {
	Window  string `json:"window"`
	Format  string `json:"format"`
	Quality int    `json:"quality"`
}

type BrowserTab struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	Title       string `json:"title"`
	URL         string `json:"url"`
	DevToolsURL string `json:"devtools_url,omitempty"`
}
