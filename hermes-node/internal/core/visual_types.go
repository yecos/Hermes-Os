package core

import "time"

type DisplayInfo struct {
	Index   int  `json:"index"`
	Left    int  `json:"left"`
	Top     int  `json:"top"`
	Right   int  `json:"right"`
	Bottom  int  `json:"bottom"`
	Width   int  `json:"width"`
	Height  int  `json:"height"`
	Primary bool `json:"primary"`
}

type WindowInfo struct {
	Handle  string `json:"handle"`
	Title   string `json:"title"`
	PID     uint32 `json:"pid"`
	Left    int32  `json:"left"`
	Top     int32  `json:"top"`
	Right   int32  `json:"right"`
	Bottom  int32  `json:"bottom"`
	Width   int32  `json:"width"`
	Height  int32  `json:"height"`
	Visible bool   `json:"visible"`
	Active  bool   `json:"active"`
}

type ScreenshotRequest struct {
	Display int    `json:"display"`
	Format  string `json:"format"`
	Quality int    `json:"quality"`
}

type Screenshot struct {
	Data       []byte    `json:"-"`
	MIMEType   string    `json:"mime_type"`
	Format     string    `json:"format"`
	Display    int       `json:"display"`
	Left       int       `json:"left"`
	Top        int       `json:"top"`
	Width      int       `json:"width"`
	Height     int       `json:"height"`
	CapturedAt time.Time `json:"captured_at"`
}

type MouseDragRequest struct {
	StartX int    `json:"start_x"`
	StartY int    `json:"start_y"`
	EndX   int    `json:"end_x"`
	EndY   int    `json:"end_y"`
	Button string `json:"button"`
	Steps  int    `json:"steps"`
}

type OpenApplicationRequest struct {
	Target     string `json:"target"`
	Parameters string `json:"parameters"`
	Dir        string `json:"dir"`
}

func (n *Node) visualUnsupported(action string) error {
	return &UnsupportedPlatformError{Action: action}
}

type UnsupportedPlatformError struct {
	Action string
}

func (e *UnsupportedPlatformError) Error() string {
	return e.Action + " is not supported on this operating system"
}
