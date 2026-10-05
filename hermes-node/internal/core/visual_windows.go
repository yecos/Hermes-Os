//go:build windows

package core

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	user32 = syscall.NewLazyDLL("user32.dll")
	gdi32  = syscall.NewLazyDLL("gdi32.dll")
	kernel = syscall.NewLazyDLL("kernel32.dll")
	shell  = syscall.NewLazyDLL("shell32.dll")

	procGetSystemMetrics             = user32.NewProc("GetSystemMetrics")
	procGetDC                        = user32.NewProc("GetDC")
	procReleaseDC                    = user32.NewProc("ReleaseDC")
	procEnumDisplayMonitors          = user32.NewProc("EnumDisplayMonitors")
	procGetMonitorInfoW              = user32.NewProc("GetMonitorInfoW")
	procEnumWindows                  = user32.NewProc("EnumWindows")
	procIsWindowVisible              = user32.NewProc("IsWindowVisible")
	procGetWindowTextLengthW         = user32.NewProc("GetWindowTextLengthW")
	procGetWindowTextW               = user32.NewProc("GetWindowTextW")
	procGetWindowThreadProcessId     = user32.NewProc("GetWindowThreadProcessId")
	procGetWindowRect                = user32.NewProc("GetWindowRect")
	procGetForegroundWindow          = user32.NewProc("GetForegroundWindow")
	procSetForegroundWindow          = user32.NewProc("SetForegroundWindow")
	procBringWindowToTop             = user32.NewProc("BringWindowToTop")
	procShowWindowAsync              = user32.NewProc("ShowWindowAsync")
	procPostMessageW                 = user32.NewProc("PostMessageW")
	procSetCursorPos                 = user32.NewProc("SetCursorPos")
	procMouseEvent                   = user32.NewProc("mouse_event")
	procKeybdEvent                   = user32.NewProc("keybd_event")
	procSendInput                    = user32.NewProc("SendInput")
	procOpenClipboard                = user32.NewProc("OpenClipboard")
	procCloseClipboard               = user32.NewProc("CloseClipboard")
	procEmptyClipboard               = user32.NewProc("EmptyClipboard")
	procGetClipboardData             = user32.NewProc("GetClipboardData")
	procSetClipboardData             = user32.NewProc("SetClipboardData")
	procCreateCompatibleDC           = gdi32.NewProc("CreateCompatibleDC")
	procDeleteDC                     = gdi32.NewProc("DeleteDC")
	procCreateCompatibleBitmap       = gdi32.NewProc("CreateCompatibleBitmap")
	procSelectObject                 = gdi32.NewProc("SelectObject")
	procBitBlt                       = gdi32.NewProc("BitBlt")
	procGetDIBits                    = gdi32.NewProc("GetDIBits")
	procDeleteObject                 = gdi32.NewProc("DeleteObject")
	procGlobalAlloc                  = kernel.NewProc("GlobalAlloc")
	procGlobalLock                   = kernel.NewProc("GlobalLock")
	procGlobalUnlock                 = kernel.NewProc("GlobalUnlock")
	procGlobalSize                   = kernel.NewProc("GlobalSize")
	procShellExecuteW                = shell.NewProc("ShellExecuteW")
)

const (
	smXVirtualScreen  = 76
	smYVirtualScreen  = 77
	smCXVirtualScreen = 78
	smCYVirtualScreen = 79

	monitorInfoFPrimary = 1

	srcCopy    = 0x00CC0020
	captureBLT = 0x40000000
	dibRGB     = 0
	biRGB      = 0

	wmClose   = 0x0010
	swRestore = 9
	swShow    = 5

	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseMiddleDown = 0x0020
	mouseMiddleUp   = 0x0040
	mouseWheel      = 0x0800

	keyEventFKeyUp   = 0x0002
	keyEventFUnicode = 0x0004
	inputKeyboard    = 1

	cfUnicodeText = 13
	gmemMoveable  = 0x0002
)

type rect struct {
	Left, Top, Right, Bottom int32
}

type monitorInfo struct {
	Size    uint32
	Monitor rect
	Work    rect
	Flags   uint32
}

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type keybdInput struct {
	VirtualKey uint16
	ScanCode   uint16
	Flags      uint32
	Time       uint32
	ExtraInfo  uintptr
}

type input struct {
	Type uint32
	_    uint32
	Ki   keybdInput
	_2   [8]byte
}

func visualCapabilities() []string {
	return []string{
		"screenshot", "list_displays", "list_windows", "get_active_window",
		"open_application", "focus_window", "close_window",
		"mouse_move", "mouse_click", "mouse_drag", "mouse_scroll",
		"key_press", "hotkey", "type_text", "clipboard_read", "clipboard_write",
	}
}

func (n *Node) Displays() ([]DisplayInfo, error) {
	displays := make([]DisplayInfo, 0, 4)
	cb := syscall.NewCallback(func(hMonitor, hdc, lprc, data uintptr) uintptr {
		var mi monitorInfo
		mi.Size = uint32(unsafe.Sizeof(mi))
		ok, _, _ := procGetMonitorInfoW.Call(hMonitor, uintptr(unsafe.Pointer(&mi)))
		if ok == 0 {
			return 1
		}
		r := mi.Monitor
		displays = append(displays, DisplayInfo{
			Index:   len(displays),
			Left:    int(r.Left),
			Top:     int(r.Top),
			Right:   int(r.Right),
			Bottom:  int(r.Bottom),
			Width:   int(r.Right - r.Left),
			Height:  int(r.Bottom - r.Top),
			Primary: mi.Flags&monitorInfoFPrimary != 0,
		})
		return 1
	})

	ok, _, callErr := procEnumDisplayMonitors.Call(0, 0, cb, 0)
	if ok == 0 {
		return nil, fmt.Errorf("EnumDisplayMonitors failed: %v", callErr)
	}
	n.record("list_displays", map[string]any{"count": len(displays)}, true, nil)
	return displays, nil
}

func (n *Node) Screenshot(req ScreenshotRequest) (Screenshot, error) {
	left, top, width, height := 0, 0, 0, 0
	if req.Display >= 0 {
		displays, err := n.Displays()
		if err != nil {
			return Screenshot{}, err
		}
		if req.Display >= len(displays) {
			return Screenshot{}, fmt.Errorf("display index %d does not exist", req.Display)
		}
		d := displays[req.Display]
		left, top, width, height = d.Left, d.Top, d.Width, d.Height
	} else {
		x, _, _ := procGetSystemMetrics.Call(smXVirtualScreen)
		y, _, _ := procGetSystemMetrics.Call(smYVirtualScreen)
		w, _, _ := procGetSystemMetrics.Call(smCXVirtualScreen)
		h, _, _ := procGetSystemMetrics.Call(smCYVirtualScreen)
		left, top, width, height = int(int32(x)), int(int32(y)), int(w), int(h)
	}
	if width <= 0 || height <= 0 {
		return Screenshot{}, errors.New("invalid desktop dimensions")
	}

	screenDC, _, err := procGetDC.Call(0)
	if screenDC == 0 {
		return Screenshot{}, fmt.Errorf("GetDC failed: %v", err)
	}
	defer procReleaseDC.Call(0, screenDC)

	memDC, _, err := procCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		return Screenshot{}, fmt.Errorf("CreateCompatibleDC failed: %v", err)
	}
	defer procDeleteDC.Call(memDC)

	bitmap, _, err := procCreateCompatibleBitmap.Call(screenDC, uintptr(width), uintptr(height))
	if bitmap == 0 {
		return Screenshot{}, fmt.Errorf("CreateCompatibleBitmap failed: %v", err)
	}
	defer procDeleteObject.Call(bitmap)

	old, _, _ := procSelectObject.Call(memDC, bitmap)
	defer procSelectObject.Call(memDC, old)

	ok, _, err := procBitBlt.Call(
		memDC, 0, 0, uintptr(width), uintptr(height),
		screenDC, uintptr(left), uintptr(top), srcCopy|captureBLT,
	)
	if ok == 0 {
		return Screenshot{}, fmt.Errorf("BitBlt failed: %v", err)
	}

	header := bitmapInfoHeader{
		Size:        uint32(unsafe.Sizeof(bitmapInfoHeader{})),
		Width:       int32(width),
		Height:      -int32(height),
		Planes:      1,
		BitCount:    32,
		Compression: biRGB,
	}
	pixels := make([]byte, width*height*4)
	lines, _, err := procGetDIBits.Call(
		memDC,
		bitmap,
		0,
		uintptr(height),
		uintptr(unsafe.Pointer(&pixels[0])),
		uintptr(unsafe.Pointer(&header)),
		dibRGB,
	)
	if lines == 0 {
		return Screenshot{}, fmt.Errorf("GetDIBits failed: %v", err)
	}

	img := image.NewNRGBA(image.Rect(0, 0, width, height))
	for i := 0; i < width*height; i++ {
		src := i * 4
		dst := i * 4
		img.Pix[dst+0] = pixels[src+2]
		img.Pix[dst+1] = pixels[src+1]
		img.Pix[dst+2] = pixels[src+0]
		img.Pix[dst+3] = 255
	}

	format := strings.ToLower(strings.TrimSpace(req.Format))
	if format == "" {
		format = "jpeg"
	}
	var buf bytes.Buffer
	mime := ""
	switch format {
	case "jpg", "jpeg":
		format = "jpeg"
		q := req.Quality
		if q <= 0 || q > 100 {
			q = 82
		}
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: q}); err != nil {
			return Screenshot{}, err
		}
		mime = "image/jpeg"
	case "png":
		if err := png.Encode(&buf, img); err != nil {
			return Screenshot{}, err
		}
		mime = "image/png"
	default:
		return Screenshot{}, errors.New("format must be jpeg or png")
	}

	result := Screenshot{
		Data:       buf.Bytes(),
		MIMEType:   mime,
		Format:     format,
		Display:    req.Display,
		Left:       left,
		Top:        top,
		Width:      width,
		Height:     height,
		CapturedAt: time.Now().UTC(),
	}
	n.record("screenshot", map[string]any{
		"display": req.Display, "format": format, "width": width, "height": height, "bytes": len(result.Data),
	}, true, nil)
	return result, nil
}

func windowTitle(hwnd uintptr) string {
	length, _, _ := procGetWindowTextLengthW.Call(hwnd)
	if length == 0 {
		return ""
	}
	buf := make([]uint16, int(length)+1)
	procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	return syscall.UTF16ToString(buf)
}

func windowInfo(hwnd uintptr, active uintptr) (WindowInfo, bool) {
	visible, _, _ := procIsWindowVisible.Call(hwnd)
	title := strings.TrimSpace(windowTitle(hwnd))
	if visible == 0 || title == "" {
		return WindowInfo{}, false
	}
	var pid uint32
	procGetWindowThreadProcessId.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
	var r rect
	ok, _, _ := procGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
	if ok == 0 {
		return WindowInfo{}, false
	}
	return WindowInfo{
		Handle:  fmt.Sprintf("0x%X", hwnd),
		Title:   title,
		PID:     pid,
		Left:    r.Left,
		Top:     r.Top,
		Right:   r.Right,
		Bottom:  r.Bottom,
		Width:   r.Right - r.Left,
		Height:  r.Bottom - r.Top,
		Visible: true,
		Active:  hwnd == active,
	}, true
}

func (n *Node) Windows() ([]WindowInfo, error) {
	active, _, _ := procGetForegroundWindow.Call()
	windows := make([]WindowInfo, 0, 32)
	cb := syscall.NewCallback(func(hwnd, data uintptr) uintptr {
		if info, ok := windowInfo(hwnd, active); ok {
			windows = append(windows, info)
		}
		return 1
	})
	ok, _, err := procEnumWindows.Call(cb, 0)
	if ok == 0 {
		return nil, fmt.Errorf("EnumWindows failed: %v", err)
	}
	n.record("list_windows", map[string]any{"count": len(windows)}, true, nil)
	return windows, nil
}

func (n *Node) ActiveWindow() (WindowInfo, error) {
	hwnd, _, _ := procGetForegroundWindow.Call()
	if hwnd == 0 {
		return WindowInfo{}, errors.New("no active window")
	}
	info, ok := windowInfo(hwnd, hwnd)
	if !ok {
		return WindowInfo{Handle: fmt.Sprintf("0x%X", hwnd), Active: true}, nil
	}
	return info, nil
}

func (n *Node) resolveWindow(ref string) (uintptr, error) {
	ref = strings.TrimSpace(ref)
	if ref == "" {
		return 0, errors.New("window handle or title is required")
	}
	if strings.HasPrefix(strings.ToLower(ref), "0x") {
		v, err := strconv.ParseUint(ref[2:], 16, 64)
		if err != nil || v == 0 {
			return 0, errors.New("invalid window handle")
		}
		return uintptr(v), nil
	}
	windows, err := n.Windows()
	if err != nil {
		return 0, err
	}
	needle := strings.ToLower(ref)
	for _, w := range windows {
		if strings.Contains(strings.ToLower(w.Title), needle) {
			v, _ := strconv.ParseUint(strings.TrimPrefix(w.Handle, "0x"), 16, 64)
			return uintptr(v), nil
		}
	}
	return 0, fmt.Errorf("window matching %q was not found", ref)
}

func (n *Node) FocusWindow(ref string) (WindowInfo, error) {
	hwnd, err := n.resolveWindow(ref)
	if err != nil {
		return WindowInfo{}, err
	}
	procShowWindowAsync.Call(hwnd, swRestore)
	procBringWindowToTop.Call(hwnd)
	ok, _, callErr := procSetForegroundWindow.Call(hwnd)
	if ok == 0 {
		return WindowInfo{}, fmt.Errorf("SetForegroundWindow failed: %v", callErr)
	}
	time.Sleep(80 * time.Millisecond)
	active, _, _ := procGetForegroundWindow.Call()
	info, _ := windowInfo(hwnd, active)
	n.record("focus_window", map[string]any{"window": ref, "handle": fmt.Sprintf("0x%X", hwnd)}, true, nil)
	return info, nil
}

func (n *Node) CloseWindow(ref string) error {
	hwnd, err := n.resolveWindow(ref)
	if err != nil {
		return err
	}
	ok, _, callErr := procPostMessageW.Call(hwnd, wmClose, 0, 0)
	if ok == 0 {
		return fmt.Errorf("PostMessageW(WM_CLOSE) failed: %v", callErr)
	}
	n.record("close_window", map[string]any{"window": ref, "handle": fmt.Sprintf("0x%X", hwnd)}, true, nil)
	return nil
}

func utf16Ptr(s string) (*uint16, error) {
	return syscall.UTF16PtrFromString(s)
}

func resolveApplicationTarget(target string) string {
	trimmed := strings.TrimSpace(target)
	lower := strings.ToLower(trimmed)
	candidates := map[string][]string{
		"chrome": {
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe"),
		},
		"chrome.exe": {
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Google", "Chrome", "Application", "chrome.exe"),
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Google", "Chrome", "Application", "chrome.exe"),
		},
		"edge": {
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
		},
		"msedge": {
			filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
			filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
		},
	}
	if items, ok := candidates[lower]; ok {
		for _, item := range items {
			if item != "" {
				if info, err := os.Stat(item); err == nil && !info.IsDir() {
					return item
				}
			}
		}
	}
	return trimmed
}

func (n *Node) OpenApplication(req OpenApplicationRequest) error {
	target := resolveApplicationTarget(req.Target)
	if target == "" {
		return errors.New("target is required")
	}
	dir := strings.TrimSpace(req.Dir)
	if dir != "" {
		resolved, err := n.ResolvePath(dir)
		if err != nil {
			return err
		}
		dir = resolved
	}

	verb, _ := utf16Ptr("open")
	targetPtr, err := utf16Ptr(target)
	if err != nil {
		return err
	}
	var paramsPtr, dirPtr *uint16
	if req.Parameters != "" {
		paramsPtr, _ = utf16Ptr(req.Parameters)
	}
	if dir != "" {
		dirPtr, _ = utf16Ptr(dir)
	}

	result, _, callErr := procShellExecuteW.Call(
		0,
		uintptr(unsafe.Pointer(verb)),
		uintptr(unsafe.Pointer(targetPtr)),
		uintptr(unsafe.Pointer(paramsPtr)),
		uintptr(unsafe.Pointer(dirPtr)),
		swShow,
	)
	if result <= 32 {
		return fmt.Errorf("ShellExecuteW failed with code %d: %v", result, callErr)
	}
	n.record("open_application", map[string]any{"requested_target": req.Target, "resolved_target": target, "parameters": req.Parameters, "dir": dir}, true, nil)
	return nil
}

func (n *Node) MouseMove(x, y int) error {
	ok, _, callErr := procSetCursorPos.Call(uintptr(x), uintptr(y))
	if ok == 0 {
		return fmt.Errorf("SetCursorPos failed: %v", callErr)
	}
	n.record("mouse_move", map[string]any{"x": x, "y": y}, true, nil)
	return nil
}

func mouseFlags(button string) (uintptr, uintptr, error) {
	switch strings.ToLower(strings.TrimSpace(button)) {
	case "", "left":
		return mouseLeftDown, mouseLeftUp, nil
	case "right":
		return mouseRightDown, mouseRightUp, nil
	case "middle":
		return mouseMiddleDown, mouseMiddleUp, nil
	default:
		return 0, 0, errors.New("button must be left, right or middle")
	}
}

func (n *Node) MouseClick(button string, count int) error {
	down, up, err := mouseFlags(button)
	if err != nil {
		return err
	}
	if count <= 0 {
		count = 1
	}
	if count > 3 {
		count = 3
	}
	for i := 0; i < count; i++ {
		procMouseEvent.Call(down, 0, 0, 0, 0)
		procMouseEvent.Call(up, 0, 0, 0, 0)
		if i+1 < count {
			time.Sleep(90 * time.Millisecond)
		}
	}
	n.record("mouse_click", map[string]any{"button": button, "count": count}, true, nil)
	return nil
}

func (n *Node) MouseDrag(req MouseDragRequest) error {
	down, up, err := mouseFlags(req.Button)
	if err != nil {
		return err
	}
	steps := req.Steps
	if steps <= 0 {
		steps = 12
	}
	if steps > 100 {
		steps = 100
	}
	if err := n.MouseMove(req.StartX, req.StartY); err != nil {
		return err
	}
	procMouseEvent.Call(down, 0, 0, 0, 0)
	for i := 1; i <= steps; i++ {
		x := req.StartX + (req.EndX-req.StartX)*i/steps
		y := req.StartY + (req.EndY-req.StartY)*i/steps
		procSetCursorPos.Call(uintptr(x), uintptr(y))
		time.Sleep(8 * time.Millisecond)
	}
	procMouseEvent.Call(up, 0, 0, 0, 0)
	n.record("mouse_drag", map[string]any{
		"start_x": req.StartX, "start_y": req.StartY, "end_x": req.EndX, "end_y": req.EndY, "button": req.Button,
	}, true, nil)
	return nil
}

func (n *Node) MouseScroll(delta int) error {
	if delta == 0 {
		return nil
	}
	procMouseEvent.Call(mouseWheel, 0, 0, uintptr(uint32(int32(delta*120))), 0)
	n.record("mouse_scroll", map[string]any{"delta": delta}, true, nil)
	return nil
}

var virtualKeys = map[string]byte{
	"BACKSPACE": 0x08, "TAB": 0x09, "ENTER": 0x0D, "SHIFT": 0x10, "CTRL": 0x11, "CONTROL": 0x11,
	"ALT": 0x12, "ESC": 0x1B, "ESCAPE": 0x1B, "SPACE": 0x20, "PAGEUP": 0x21, "PAGEDOWN": 0x22,
	"END": 0x23, "HOME": 0x24, "LEFT": 0x25, "UP": 0x26, "RIGHT": 0x27, "DOWN": 0x28,
	"INSERT": 0x2D, "DELETE": 0x2E, "WIN": 0x5B, "LWIN": 0x5B, "RWIN": 0x5C,
	"F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73, "F5": 0x74, "F6": 0x75,
	"F7": 0x76, "F8": 0x77, "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
}

func keyCode(key string) (byte, error) {
	key = strings.ToUpper(strings.TrimSpace(key))
	if len(key) == 1 {
		c := key[0]
		if (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') {
			return c, nil
		}
	}
	if vk, ok := virtualKeys[key]; ok {
		return vk, nil
	}
	return 0, fmt.Errorf("unsupported key %q", key)
}

func keyEvent(vk byte, up bool) {
	flags := uintptr(0)
	if up {
		flags = keyEventFKeyUp
	}
	procKeybdEvent.Call(uintptr(vk), 0, flags, 0)
}

func (n *Node) KeyPress(key string) error {
	vk, err := keyCode(key)
	if err != nil {
		return err
	}
	keyEvent(vk, false)
	keyEvent(vk, true)
	n.record("key_press", map[string]any{"key": key}, true, nil)
	return nil
}

func (n *Node) Hotkey(keys []string) error {
	if len(keys) == 0 {
		return errors.New("keys are required")
	}
	if len(keys) > 8 {
		return errors.New("too many keys in hotkey")
	}
	codes := make([]byte, len(keys))
	for i, key := range keys {
		vk, err := keyCode(key)
		if err != nil {
			return err
		}
		codes[i] = vk
	}
	for _, vk := range codes {
		keyEvent(vk, false)
	}
	for i := len(codes) - 1; i >= 0; i-- {
		keyEvent(codes[i], true)
	}
	n.record("hotkey", map[string]any{"keys": keys}, true, nil)
	return nil
}

func sendUnicode(code uint16, keyUp bool) error {
	flags := uint32(keyEventFUnicode)
	if keyUp {
		flags |= keyEventFKeyUp
	}
	in := input{
		Type: inputKeyboard,
		Ki: keybdInput{
			ScanCode: code,
			Flags:    flags,
		},
	}
	sent, _, callErr := procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
	if sent != 1 {
		return fmt.Errorf("SendInput failed: %v", callErr)
	}
	return nil
}

func (n *Node) TypeText(text string) error {
	units := utf16.Encode([]rune(text))
	for _, code := range units {
		if err := sendUnicode(code, false); err != nil {
			return err
		}
		if err := sendUnicode(code, true); err != nil {
			return err
		}
	}
	n.record("type_text", map[string]any{"characters": len([]rune(text))}, true, nil)
	return nil
}

func openClipboardRetry() error {
	for i := 0; i < 10; i++ {
		ok, _, _ := procOpenClipboard.Call(0)
		if ok != 0 {
			return nil
		}
		time.Sleep(30 * time.Millisecond)
	}
	return errors.New("could not open clipboard")
}

func (n *Node) ClipboardRead() (string, error) {
	if err := openClipboardRetry(); err != nil {
		return "", err
	}
	defer procCloseClipboard.Call()

	handle, _, callErr := procGetClipboardData.Call(cfUnicodeText)
	if handle == 0 {
		return "", fmt.Errorf("GetClipboardData failed: %v", callErr)
	}
	ptr, _, callErr := procGlobalLock.Call(handle)
	if ptr == 0 {
		return "", fmt.Errorf("GlobalLock failed: %v", callErr)
	}
	defer procGlobalUnlock.Call(handle)

	size, _, _ := procGlobalSize.Call(handle)
	if size < 2 {
		return "", nil
	}
	units := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), int(size/2))
	end := 0
	for end < len(units) && units[end] != 0 {
		end++
	}
	text := string(utf16.Decode(units[:end]))
	n.record("clipboard_read", map[string]any{"characters": len([]rune(text))}, true, nil)
	return text, nil
}

func (n *Node) ClipboardWrite(text string) error {
	if err := openClipboardRetry(); err != nil {
		return err
	}
	defer procCloseClipboard.Call()

	ok, _, callErr := procEmptyClipboard.Call()
	if ok == 0 {
		return fmt.Errorf("EmptyClipboard failed: %v", callErr)
	}

	units := utf16.Encode([]rune(text))
	units = append(units, 0)
	size := uintptr(len(units) * 2)
	handle, _, callErr := procGlobalAlloc.Call(gmemMoveable, size)
	if handle == 0 {
		return fmt.Errorf("GlobalAlloc failed: %v", callErr)
	}
	ptr, _, callErr := procGlobalLock.Call(handle)
	if ptr == 0 {
		return fmt.Errorf("GlobalLock failed: %v", callErr)
	}
	dst := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), len(units))
	copy(dst, units)
	procGlobalUnlock.Call(handle)

	set, _, callErr := procSetClipboardData.Call(cfUnicodeText, handle)
	if set == 0 {
		return fmt.Errorf("SetClipboardData failed: %v", callErr)
	}
	n.record("clipboard_write", map[string]any{"characters": len([]rune(text))}, true, nil)
	return nil
}
