//go:build windows

package core

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"time"
	"unsafe"
)

func captureWindowsRect(left, top, width, height int, format string, quality int) (Screenshot, error) {
	if width <= 0 || height <= 0 {
		return Screenshot{}, errors.New("invalid capture dimensions")
	}

	screenDC, _, callErr := procGetDC.Call(0)
	if screenDC == 0 {
		return Screenshot{}, fmt.Errorf("GetDC failed: %v", callErr)
	}
	defer procReleaseDC.Call(0, screenDC)

	memDC, _, callErr := procCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		return Screenshot{}, fmt.Errorf("CreateCompatibleDC failed: %v", callErr)
	}
	defer procDeleteDC.Call(memDC)

	bitmap, _, callErr := procCreateCompatibleBitmap.Call(screenDC, uintptr(width), uintptr(height))
	if bitmap == 0 {
		return Screenshot{}, fmt.Errorf("CreateCompatibleBitmap failed: %v", callErr)
	}
	defer procDeleteObject.Call(bitmap)

	old, _, _ := procSelectObject.Call(memDC, bitmap)
	defer procSelectObject.Call(memDC, old)

	ok, _, callErr := procBitBlt.Call(
		memDC, 0, 0, uintptr(width), uintptr(height),
		screenDC, uintptr(left), uintptr(top), srcCopy|captureBLT,
	)
	if ok == 0 {
		return Screenshot{}, fmt.Errorf("BitBlt failed: %v", callErr)
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
	lines, _, callErr := procGetDIBits.Call(
		memDC,
		bitmap,
		0,
		uintptr(height),
		uintptr(unsafe.Pointer(&pixels[0])),
		uintptr(unsafe.Pointer(&header)),
		dibRGB,
	)
	if lines == 0 {
		return Screenshot{}, fmt.Errorf("GetDIBits failed: %v", callErr)
	}

	img := image.NewNRGBA(image.Rect(0, 0, width, height))
	for i := 0; i < width*height; i++ {
		src := i * 4
		dst := i * 4
		img.Pix[dst] = pixels[src+2]
		img.Pix[dst+1] = pixels[src+1]
		img.Pix[dst+2] = pixels[src]
		img.Pix[dst+3] = 255
	}

	format = stringsToLowerTrim(format)
	if format == "" {
		format = "jpeg"
	}
	var buf bytes.Buffer
	mime := ""
	switch format {
	case "jpg", "jpeg":
		format = "jpeg"
		if quality <= 0 || quality > 100 {
			quality = 82
		}
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
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

	return Screenshot{
		Data:       buf.Bytes(),
		MIMEType:   mime,
		Format:     format,
		Display:    -2,
		Left:       left,
		Top:        top,
		Width:      width,
		Height:     height,
		CapturedAt: time.Now().UTC(),
	}, nil
}

func stringsToLowerTrim(s string) string {
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t' || s[0] == '\r' || s[0] == '\n') {
		s = s[1:]
	}
	for len(s) > 0 {
		last := s[len(s)-1]
		if last != ' ' && last != '\t' && last != '\r' && last != '\n' {
			break
		}
		s = s[:len(s)-1]
	}
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}
