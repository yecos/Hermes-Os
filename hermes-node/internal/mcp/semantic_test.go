package mcp

import "testing"

func TestSemanticToolsAreAdvertised(t *testing.T) {
	names := map[string]bool{}
	for _, tool := range tools() {
		name, _ := tool["name"].(string)
		names[name] = true
	}

	required := []string{
		"ui_snapshot",
		"find_elements",
		"wait_for_element",
		"click_element",
		"invoke_element",
		"set_element_text",
		"focus_element",
		"scroll_element",
		"screen_region",
		"window_screenshot",
		"browser_open_managed",
		"browser_tabs",
		"browser_open_tab",
		"browser_snapshot",
		"browser_click",
		"browser_set_text",
		"browser_navigate",
		"browser_page_text",
		"browser_screenshot",
	}

	for _, name := range required {
		if !names[name] {
			t.Fatalf("semantic tool %q is missing", name)
		}
	}
}
