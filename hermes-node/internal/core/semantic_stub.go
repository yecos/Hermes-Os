//go:build !windows

package core

import "time"

func semanticCapabilities() []string { return nil }

func (n *Node) UISnapshot(UIQuery) (UISnapshot, error) {
	return UISnapshot{}, n.visualUnsupported("ui_snapshot")
}
func (n *Node) FindUIElements(UIQuery) ([]UIElement, error) {
	return nil, n.visualUnsupported("find_elements")
}
func (n *Node) InvokeUIElement(string) (UIActionResult, error) {
	return UIActionResult{}, n.visualUnsupported("invoke_element")
}
func (n *Node) ClickUIElement(string) (UIActionResult, error) {
	return UIActionResult{}, n.visualUnsupported("click_element")
}
func (n *Node) SetUIElementText(string, string) (UIActionResult, error) {
	return UIActionResult{}, n.visualUnsupported("set_element_text")
}
func (n *Node) FocusUIElement(string) (UIActionResult, error) {
	return UIActionResult{}, n.visualUnsupported("focus_element")
}
func (n *Node) ScrollUIElement(string, int) (UIActionResult, error) {
	return UIActionResult{}, n.visualUnsupported("scroll_element")
}
func (n *Node) WaitForUIElement(UIQuery, time.Duration) (UIElement, error) {
	return UIElement{}, n.visualUnsupported("wait_for_element")
}
func (n *Node) ScreenRegion(ScreenRegionRequest) (Screenshot, error) {
	return Screenshot{}, n.visualUnsupported("screen_region")
}
func (n *Node) WindowScreenshot(WindowScreenshotRequest) (Screenshot, error) {
	return Screenshot{}, n.visualUnsupported("window_screenshot")
}
func (n *Node) BrowserOpenManaged(string) error {
	return n.visualUnsupported("browser_open_managed")
}
func (n *Node) BrowserTabs() ([]BrowserTab, error) {
	return nil, n.visualUnsupported("browser_tabs")
}
func (n *Node) BrowserOpenTab(string) (BrowserTab, error) {
	return BrowserTab{}, n.visualUnsupported("browser_open_tab")
}
