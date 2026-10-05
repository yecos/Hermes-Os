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

type BrowserElement struct {
	ElementID string `json:"element_id"`
	Tag       string `json:"tag,omitempty"`
	Role      string `json:"role,omitempty"`
	Text      string `json:"text,omitempty"`
	AriaLabel string `json:"aria_label,omitempty"`
	Placeholder string `json:"placeholder,omitempty"`
	Name      string `json:"name,omitempty"`
	Type      string `json:"type,omitempty"`
	Value     string `json:"value,omitempty"`
	Href      string `json:"href,omitempty"`
	Disabled  bool   `json:"disabled"`
	Checked   bool   `json:"checked"`
	X         int    `json:"x,omitempty"`
	Y         int    `json:"y,omitempty"`
	Width     int    `json:"width,omitempty"`
	Height    int    `json:"height,omitempty"`
}

type BrowserSnapshot struct {
	TabID        string           `json:"tab_id"`
	Title        string           `json:"title"`
	URL          string           `json:"url"`
	ElementCount int              `json:"element_count"`
	Elements     []BrowserElement `json:"elements"`
}

type BrowserActionResult struct {
	OK        bool   `json:"ok"`
	ElementID string `json:"element_id,omitempty"`
	Title     string `json:"title,omitempty"`
	URL       string `json:"url,omitempty"`
	Value     string `json:"value,omitempty"`
	Error     string `json:"error,omitempty"`
}

type BrowserPageText struct {
	Title     string `json:"title"`
	URL       string `json:"url"`
	Text      string `json:"text"`
	Truncated bool   `json:"truncated"`
}
