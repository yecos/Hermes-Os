//go:build !windows

package core

func visualCapabilities() []string { return nil }

func (n *Node) Displays() ([]DisplayInfo, error) {
	return nil, n.visualUnsupported("list_displays")
}
func (n *Node) Screenshot(ScreenshotRequest) (Screenshot, error) {
	return Screenshot{}, n.visualUnsupported("screenshot")
}
func (n *Node) Windows() ([]WindowInfo, error) {
	return nil, n.visualUnsupported("list_windows")
}
func (n *Node) ActiveWindow() (WindowInfo, error) {
	return WindowInfo{}, n.visualUnsupported("get_active_window")
}
func (n *Node) FocusWindow(string) (WindowInfo, error) {
	return WindowInfo{}, n.visualUnsupported("focus_window")
}
func (n *Node) CloseWindow(string) error {
	return n.visualUnsupported("close_window")
}
func (n *Node) OpenApplication(OpenApplicationRequest) error {
	return n.visualUnsupported("open_application")
}
func (n *Node) MouseMove(int, int) error {
	return n.visualUnsupported("mouse_move")
}
func (n *Node) MouseClick(string, int) error {
	return n.visualUnsupported("mouse_click")
}
func (n *Node) MouseDrag(MouseDragRequest) error {
	return n.visualUnsupported("mouse_drag")
}
func (n *Node) MouseScroll(int) error {
	return n.visualUnsupported("mouse_scroll")
}
func (n *Node) KeyPress(string) error {
	return n.visualUnsupported("key_press")
}
func (n *Node) Hotkey([]string) error {
	return n.visualUnsupported("hotkey")
}
func (n *Node) TypeText(string) error {
	return n.visualUnsupported("type_text")
}
func (n *Node) ClipboardRead() (string, error) {
	return "", n.visualUnsupported("clipboard_read")
}
func (n *Node) ClipboardWrite(string) error {
	return n.visualUnsupported("clipboard_write")
}
