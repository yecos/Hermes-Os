param(
    [Parameter(Mandatory=$true)][string]$Action,
    [Parameter(Mandatory=$true)][string]$PayloadBase64
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$OutputEncoding = [Text.Encoding]::UTF8

$payloadJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($PayloadBase64))
$payload = $payloadJson | ConvertFrom-Json

if (-not $payload.websocket_url) {
    throw "websocket_url is required"
}

$ws = [System.Net.WebSockets.ClientWebSocket]::new()
$uri = [Uri][string]$payload.websocket_url
$connectCts = [Threading.CancellationTokenSource]::new()
$connectCts.CancelAfter(5000)
try {
    $ws.ConnectAsync($uri, $connectCts.Token).GetAwaiter().GetResult()
}
finally {
    $connectCts.Dispose()
}

$script:nextId = 1

function Send-Text([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    $segment = [ArraySegment[byte]]::new($bytes)
    $ws.SendAsync(
        $segment,
        [System.Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
}

function Receive-Text {
    $stream = [IO.MemoryStream]::new()
    try {
        do {
            $buffer = New-Object byte[] 65536
            $segment = [ArraySegment[byte]]::new($buffer)
            $receiveCts = [Threading.CancellationTokenSource]::new()
            $receiveCts.CancelAfter(10000)
            try {
                $result = $ws.ReceiveAsync(
                    $segment,
                    $receiveCts.Token
                ).GetAwaiter().GetResult()
            }
            finally {
                $receiveCts.Dispose()
            }

            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                throw "Chrome DevTools websocket closed unexpectedly."
            }

            if ($result.Count -gt 0) {
                $stream.Write($buffer, 0, $result.Count)
            }
        } while (-not $result.EndOfMessage)

        return [Text.Encoding]::UTF8.GetString($stream.ToArray())
    }
    finally {
        $stream.Dispose()
    }
}

function Invoke-CDP([string]$method, $params) {
    $id = $script:nextId
    $script:nextId++

    $message = @{
        id = $id
        method = $method
        params = $params
    } | ConvertTo-Json -Compress -Depth 20

    Send-Text $message

    while ($true) {
        $raw = Receive-Text
        if (-not $raw) { continue }

        $obj = $raw | ConvertFrom-Json
        if ($obj.id -eq $id) {
            if ($obj.error) {
                throw ("CDP {0} failed: {1}" -f $method, ($obj.error | ConvertTo-Json -Compress))
            }
            return $obj.result
        }
    }
}

function Evaluate-JS([string]$expression) {
    $result = Invoke-CDP "Runtime.evaluate" @{
        expression = $expression
        returnByValue = $true
        awaitPromise = $true
        userGesture = $true
    }

    if ($result.exceptionDetails) {
        throw ("JavaScript evaluation failed: " + ($result.exceptionDetails | ConvertTo-Json -Compress -Depth 8))
    }
    return $result.result.value
}

function Emit($value) {
    $value | ConvertTo-Json -Compress -Depth 20
}

try {
    Invoke-CDP "Runtime.enable" @{} | Out-Null
    Invoke-CDP "Page.enable" @{} | Out-Null

    switch ($Action) {
        "snapshot" {
            $maxResults = if ($payload.max_results) { [Math]::Min([Math]::Max([int]$payload.max_results, 1), 500) } else { 150 }
            $expression = @"
(() => {
  if (!window.__hermesNextId) window.__hermesNextId = 1;
  const max = $maxResults;
  const selector = [
    'a','button','input','textarea','select','summary',
    '[role]','[contenteditable="true"]','[tabindex]',
    'h1','h2','h3','label'
  ].join(',');
  const visible = el => {
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  };
  const els = [...document.querySelectorAll(selector)].filter(visible).slice(0, max);
  const items = els.map(el => {
    if (!el.dataset.hermesId) el.dataset.hermesId = 'he-' + (window.__hermesNextId++);
    const r = el.getBoundingClientRect();
    const text = (el.innerText || el.textContent || '').trim().replace(/\s+/g,' ').slice(0,300);
    return {
      element_id: el.dataset.hermesId,
      tag: el.tagName.toLowerCase(),
      role: el.getAttribute('role') || '',
      text,
      aria_label: el.getAttribute('aria-label') || '',
      placeholder: el.getAttribute('placeholder') || '',
      name: el.getAttribute('name') || '',
      type: el.getAttribute('type') || '',
      value: ('value' in el ? String(el.value ?? '') : '').slice(0,500),
      href: el.href || '',
      disabled: !!el.disabled,
      checked: ('checked' in el ? !!el.checked : false),
      x: Math.round(r.x),
      y: Math.round(r.y),
      width: Math.round(r.width),
      height: Math.round(r.height)
    };
  });
  return {
    title: document.title,
    url: location.href,
    element_count: items.length,
    elements: items
  };
})()
"@
            $value = Evaluate-JS $expression
            Emit $value
        }

        "click" {
            $idJson = ([string]$payload.element_id | ConvertTo-Json -Compress)
            $expression = @"
(() => {
  const id = $idJson;
  const el = document.querySelector('[data-hermes-id="' + CSS.escape(id) + '"]');
  if (!el) return {ok:false,error:'element_not_found'};
  el.scrollIntoView({block:'center', inline:'center'});
  if (typeof el.focus === 'function') el.focus();
  el.click();
  return {ok:true, element_id:id, title:document.title, url:location.href};
})()
"@
            $value = Evaluate-JS $expression
            Emit $value
        }

        "set_text" {
            $idJson = ([string]$payload.element_id | ConvertTo-Json -Compress)
            $textJson = ([string]$payload.text | ConvertTo-Json -Compress)
            $expression = @"
(() => {
  const id = $idJson;
  const text = $textJson;
  const el = document.querySelector('[data-hermes-id="' + CSS.escape(id) + '"]');
  if (!el) return {ok:false,error:'element_not_found'};
  el.scrollIntoView({block:'center', inline:'center'});
  if (typeof el.focus === 'function') el.focus();

  if (el.isContentEditable) {
    el.textContent = text;
  } else if ('value' in el) {
    let proto = null;
    if (el instanceof HTMLTextAreaElement) proto = HTMLTextAreaElement.prototype;
    else if (el instanceof HTMLSelectElement) proto = HTMLSelectElement.prototype;
    else proto = HTMLInputElement.prototype;

    const desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, text);
    else el.value = text;
  } else {
    return {ok:false,error:'element_not_editable'};
  }

  el.dispatchEvent(new Event('input', {bubbles:true}));
  el.dispatchEvent(new Event('change', {bubbles:true}));
  return {ok:true, element_id:id, value:('value' in el ? String(el.value ?? '') : el.textContent)};
})()
"@
            $value = Evaluate-JS $expression
            Emit $value
        }

        "navigate" {
            $result = Invoke-CDP "Page.navigate" @{
                url = [string]$payload.url
            }
            Emit ([ordered]@{
                ok = $true
                frame_id = [string]$result.frameId
                loader_id = [string]$result.loaderId
                url = [string]$payload.url
            })
        }

        "page_text" {
            $maxChars = if ($payload.max_chars) { [Math]::Min([Math]::Max([int]$payload.max_chars, 100), 100000) } else { 20000 }
            $expression = @"
(() => {
  const maxChars = $maxChars;
  const text = (document.body ? document.body.innerText : '').replace(/\n{3,}/g,'\n\n');
  return {
    title: document.title,
    url: location.href,
    text: text.slice(0, maxChars),
    truncated: text.length > maxChars
  };
})()
"@
            $value = Evaluate-JS $expression
            Emit $value
        }

        "screenshot" {
            $format = if ($payload.format -eq "png") { "png" } else { "jpeg" }
            $quality = if ($payload.quality) { [Math]::Min([Math]::Max([int]$payload.quality, 1), 100) } else { 82 }
            $params = @{
                format = $format
                fromSurface = $true
                captureBeyondViewport = $false
            }
            if ($format -eq "jpeg") { $params.quality = $quality }
            $result = Invoke-CDP "Page.captureScreenshot" $params
            Emit ([ordered]@{
                data = [string]$result.data
                format = $format
                mime_type = if ($format -eq "png") { "image/png" } else { "image/jpeg" }
            })
        }

        default {
            throw "Unknown browser CDP action: $Action"
        }
    }
}
finally {
    if ($ws.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        try {
            $ws.CloseAsync(
                [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                "done",
                [Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()
        } catch {}
    }
    $ws.Dispose()
}
