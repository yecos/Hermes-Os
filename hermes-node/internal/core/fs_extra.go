package core

import (
	"bufio"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type FileInfo struct {
	Name       string    `json:"name"`
	Path       string    `json:"path"`
	IsDir      bool      `json:"is_dir"`
	Size       int64     `json:"size"`
	Mode       string    `json:"mode"`
	Modified   time.Time `json:"modified"`
	Executable bool      `json:"executable"`
}

func (n *Node) GetFileInfo(path string) (FileInfo, error) {
	resolved, err := n.ResolvePath(path)
	if err != nil {
		return FileInfo{}, err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return FileInfo{}, err
	}
	out := FileInfo{
		Name:       info.Name(),
		Path:       resolved,
		IsDir:      info.IsDir(),
		Size:       info.Size(),
		Mode:       info.Mode().String(),
		Modified:   info.ModTime(),
		Executable: info.Mode()&0111 != 0,
	}
	n.record("get_file_info", map[string]any{"path": resolved}, true, nil)
	return out, nil
}

func (n *Node) CreateDirectory(path string) error {
	resolved, err := n.ResolvePath(path)
	if err != nil {
		return err
	}
	err = os.MkdirAll(resolved, 0755)
	n.record("create_directory", map[string]any{"path": resolved}, err == nil, err)
	return err
}

func (n *Node) MovePath(source, destination string) error {
	src, err := n.ResolvePath(source)
	if err != nil {
		return err
	}
	dst, err := n.ResolvePath(destination)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
		return err
	}
	err = os.Rename(src, dst)
	n.record("move_file", map[string]any{"source": src, "destination": dst}, err == nil, err)
	return err
}

func (n *Node) DeletePath(path string, recursive bool) error {
	resolved, err := n.ResolvePath(path)
	if err != nil {
		return err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return err
	}
	if info.IsDir() && !recursive {
		err = os.Remove(resolved)
	} else if info.IsDir() {
		err = os.RemoveAll(resolved)
	} else {
		err = os.Remove(resolved)
	}
	n.record("delete_path", map[string]any{"path": resolved, "recursive": recursive}, err == nil, err)
	return err
}

type SearchRequest struct {
	Path       string `json:"path"`
	Pattern    string `json:"pattern"`
	SearchType string `json:"search_type"`
	MaxResults int    `json:"max_results"`
	MaxDepth   int    `json:"max_depth"`
}

type SearchResult struct {
	Path    string `json:"path"`
	Line    int    `json:"line,omitempty"`
	Preview string `json:"preview,omitempty"`
}

func (n *Node) Search(req SearchRequest) ([]SearchResult, error) {
	root, err := n.ResolvePath(req.Path)
	if err != nil {
		return nil, err
	}
	pattern := strings.TrimSpace(req.Pattern)
	if pattern == "" {
		return nil, errors.New("pattern is required")
	}
	if req.SearchType == "" {
		req.SearchType = "files"
	}
	if req.SearchType != "files" && req.SearchType != "content" {
		return nil, errors.New("search_type must be files or content")
	}
	if req.MaxResults <= 0 || req.MaxResults > 500 {
		req.MaxResults = 100
	}
	if req.MaxDepth <= 0 || req.MaxDepth > 32 {
		req.MaxDepth = 12
	}

	lowerPattern := strings.ToLower(pattern)
	results := make([]SearchResult, 0, min(req.MaxResults, 32))
	stop := errors.New("search limit reached")

	err = filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return nil
		}
		depth := 0
		if rel != "." {
			depth = len(strings.Split(rel, string(os.PathSeparator)))
		}
		if entry.IsDir() {
			if depth > req.MaxDepth {
				return filepath.SkipDir
			}
			return nil
		}
		if depth > req.MaxDepth {
			return nil
		}

		if req.SearchType == "files" {
			if strings.Contains(strings.ToLower(entry.Name()), lowerPattern) {
				results = append(results, SearchResult{Path: path})
			}
		} else {
			info, err := entry.Info()
			if err != nil || info.Size() > n.cfg.MaxReadBytes {
				return nil
			}
			f, err := os.Open(path)
			if err != nil {
				return nil
			}
			scanner := bufio.NewScanner(f)
			buf := make([]byte, 64*1024)
			scanner.Buffer(buf, 1024*1024)
			lineNo := 0
			for scanner.Scan() {
				lineNo++
				line := scanner.Text()
				if strings.Contains(strings.ToLower(line), lowerPattern) {
					preview := strings.TrimSpace(line)
					if len(preview) > 240 {
						preview = preview[:240]
					}
					results = append(results, SearchResult{Path: path, Line: lineNo, Preview: preview})
					if len(results) >= req.MaxResults {
						_ = f.Close()
						return stop
					}
				}
			}
			_ = f.Close()
		}

		if len(results) >= req.MaxResults {
			return stop
		}
		return nil
	})
	if err != nil && !errors.Is(err, stop) {
		return nil, fmt.Errorf("search failed: %w", err)
	}
	n.record("search_files", map[string]any{"path": root, "pattern": pattern, "type": req.SearchType, "count": len(results)}, true, nil)
	return results, nil
}
