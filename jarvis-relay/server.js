/**
 * V.A.Y.U MCP Relay Server v4.2 — HTTP Long-Polling + MCP SSE + Streamable HTTP
 *
 * Supports Claude.ai remote MCP connections via:
 *   - SSE transport: GET /sse + POST /messages (legacy, widely supported)
 *   - Streamable HTTP: POST /mcp (newer, better for proxies/Render)
 *
 * Android device communication via HTTP Long-Polling:
 *   POST /api/register     — Device registers itself
 *   GET  /api/poll         — Device long-polls for commands (25s timeout)
 *   POST /api/status       — Device pushes status update
 *   POST /api/response     — Device pushes command response
 *   POST /api/command      — MCP client sends command to device
 *   GET  /api/devices      — MCP client lists connected devices
 *   GET  /api/health       — Health check
 *   GET  /api/warmup       — Force server awake
 */

const express = require('express');
const cors = require('cors');
const crypto = require('crypto');

const app = express();
const PORT = process.env.PORT || 10000;

// ─── Middleware ───
app.use(cors());
app.use(express.json({ limit: '20mb' }));

// ─── In-Memory State ───
const devices = new Map();
const responses = new Map();
const statusUpdates = new Map();

const POLL_TIMEOUT_MS = 25000;
const CLEANUP_INTERVAL_MS = 60000;     // Clean stale data every 60s
const DEVICE_STALE_MS = 300000;     // 5 minutes (was 90s — too aggressive for Render restarts)
const RESPONSE_TTL_MS = 300000;
const COMMAND_TTL_MS = 120000;

const startTime = Date.now();

// ─── Health Check ───
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    version: '6.0.0',
    protocol: 'HTTP Long-Polling + MCP SSE + Streamable HTTP',
    devices: devices.size,
    pendingResponses: responses.size,
    syncCallEndpoint: '/api/call',
    supportedSyncTools: SYNC_CALL_TOOLS,
    modelRecommendEndpoint: '/api/models/recommend',
    uptime: process.uptime(),
    uptimeMs: Date.now() - startTime,
    timestamp: Date.now()
  });
});

// ─── Warmup endpoint ───
app.get('/api/warmup', (req, res) => {
  res.json({
    status: 'warm',
    uptime: process.uptime(),
    devices: devices.size,
    timestamp: Date.now()
  });
});

// ─── Device Registration ───
app.post('/api/register', (req, res) => {
  const { deviceId, model, androidVersion, sdkVersion } = req.body;
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId required' });
  }

  const existing = devices.get(deviceId);
  const wasConnected = existing?.connected || false;

  devices.set(deviceId, {
    info: { model: model || 'Unknown', androidVersion: androidVersion || '?', sdkVersion: sdkVersion || '?' },
    lastSeen: Date.now(),
    pendingCommands: existing?.pendingCommands || [],
    connected: true,
    registeredAt: existing?.registeredAt || Date.now(),
    reconnectCount: (existing?.reconnectCount || 0) + (wasConnected ? 0 : 1)
  });

  console.log(`[REGISTER] ${deviceId} — ${model} (Android ${androidVersion})${wasConnected ? ' (reconnect)' : ''}`);
  res.json({ ok: true, message: 'Registered', deviceId, reconnectCount: devices.get(deviceId).reconnectCount });
});

// ─── Device Long-Poll for Commands ───
app.get('/api/poll', async (req, res) => {
  const { deviceId, lastMsgId } = req.query;
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId required' });
  }

  const device = devices.get(deviceId);
  if (device) {
    device.lastSeen = Date.now();
    device.connected = true;
  } else {
    devices.set(deviceId, {
      info: { model: 'Unknown', androidVersion: '?', sdkVersion: '?' },
      lastSeen: Date.now(),
      pendingCommands: [],
      connected: true,
      registeredAt: Date.now(),
      reconnectCount: 0
    });
  }

  const dev = devices.get(deviceId);

  if (dev.pendingCommands.length > 0) {
    const commands = [];
    while (dev.pendingCommands.length > 0) {
      commands.push(dev.pendingCommands.shift());
    }
    return res.json({ commands, serverTime: Date.now() });
  }

  let intervalId = null;
  let timeoutId = null;
  let responded = false;

  const sendResponse = (commands) => {
    if (responded) return;
    responded = true;
    if (intervalId) clearInterval(intervalId);
    if (timeoutId) clearTimeout(timeoutId);
    res.json({ commands, serverTime: Date.now() });
  };

  intervalId = setInterval(() => {
    if (dev.pendingCommands.length > 0) {
      const commands = [];
      while (dev.pendingCommands.length > 0) {
        commands.push(dev.pendingCommands.shift());
      }
      sendResponse(commands);
    }
  }, 300);

  timeoutId = setTimeout(() => {
    sendResponse([]);
  }, POLL_TIMEOUT_MS);

  req.on('close', () => {
    if (intervalId) clearInterval(intervalId);
    if (timeoutId) clearTimeout(timeoutId);
    if (!responded) {
      responded = true;
    }
  });
});

// ─── Device Push Status ───
app.post('/api/status', (req, res) => {
  const { deviceId, status, step, action, isRunning, timestamp } = req.body;
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId required' });
  }

  const device = devices.get(deviceId);
  if (device) {
    device.lastSeen = Date.now();
    device.connected = true;
  }

  statusUpdates.set(deviceId, {
    status: status || '',
    step: step || 0,
    action: action || '',
    isRunning: isRunning || false,
    timestamp: Date.now()
  });

  res.json({ ok: true });
});

// ─── Device Push Command Response ───
app.post('/api/response', (req, res) => {
  const { requestId, type, deviceId, ...rest } = req.body;
  if (!requestId) {
    return res.status(400).json({ error: 'requestId required' });
  }

  const device = devices.get(deviceId);
  if (device) {
    device.lastSeen = Date.now();
  }

  responses.set(requestId, {
    response: { type, deviceId, ...rest },
    timestamp: Date.now()
  });

  console.log(`[RESPONSE] ${type} from ${deviceId} (requestId: ${requestId})`);
  res.json({ ok: true });
});

// ─── MCP Client: Send Command to Device ───
app.post('/api/command', (req, res) => {
  const { deviceId, type, task, ...rest } = req.body;
  if (!type) {
    return res.status(400).json({ error: 'type required' });
  }

  const requestId = rest.requestId || crypto.randomUUID();

  if (deviceId) {
    const device = devices.get(deviceId);
    if (!device) {
      return res.status(404).json({ error: 'Device not found', deviceId });
    }

    device.pendingCommands.push({
      requestId,
      type: normalizeDeviceCommandType(type),
      task,
      ...rest,
      timestamp: Date.now()
    });

    console.log(`[COMMAND] ${type} → ${deviceId} (requestId: ${requestId})`);
    return res.json({ ok: true, requestId, deviceId });
  }

  for (const [id, dev] of devices) {
    if (dev.connected && Date.now() - dev.lastSeen < DEVICE_STALE_MS) {
      dev.pendingCommands.push({
        requestId,
        type: normalizeDeviceCommandType(type),
        task,
        ...rest,
        timestamp: Date.now()
      });

      console.log(`[COMMAND] ${type} → ${id} (requestId: ${requestId})`);
      return res.json({ ok: true, requestId, deviceId: id });
    }
  }

  return res.status(404).json({ error: 'No connected devices available' });
});

// ─── MCP Client: Poll for Response ───
app.get('/api/response/:requestId', async (req, res) => {
  const { requestId } = req.params;

  const existing = responses.get(requestId);
  if (existing) {
    responses.delete(requestId);
    return res.json(existing.response);
  }

  let intervalId = null;
  let timeoutId = null;
  let responded = false;

  const sendResponse = (data) => {
    if (responded) return;
    responded = true;
    if (intervalId) clearInterval(intervalId);
    if (timeoutId) clearTimeout(timeoutId);
    res.json(data);
  };

  intervalId = setInterval(() => {
    const resp = responses.get(requestId);
    if (resp) {
      responses.delete(requestId);
      sendResponse(resp.response);
    }
  }, 200);

  timeoutId = setTimeout(() => {
    sendResponse({ type: 'timeout', message: 'No response from device in time' });
  }, POLL_TIMEOUT_MS);

  req.on('close', () => {
    if (intervalId) clearInterval(intervalId);
    if (timeoutId) clearTimeout(timeoutId);
    if (!responded) responded = true;
  });
});

// ─── Synchronous Tool Bridge (No SSE required) ───
function pickTargetDevice(deviceId) {
  if (deviceId) return devices.has(deviceId) ? deviceId : null;
  for (const [id, dev] of devices) {
    if (dev.connected && Date.now() - dev.lastSeen < DEVICE_STALE_MS) return id;
  }
  return null;
}

const DEVICE_COMMAND_ALIASES = {
  get_ui_tree: 'ui_tree',
  get_screenshot_and_ui: 'screenshot_and_ui',
  read_screen: 'screenshot_and_ui',
  screen_text: 'ui_tree',
  paste_text: 'type_text'
};

function normalizeDeviceCommandType(type) {
  return DEVICE_COMMAND_ALIASES[type] || type;
}

function waitForResponse(requestId, timeoutMs = 45000) {
  return new Promise((resolve) => {
    const started = Date.now();
    const timer = setInterval(() => {
      const hit = responses.get(requestId);
      if (hit) {
        responses.delete(requestId);
        clearInterval(timer);
        resolve({ ok: true, response: hit.response });
        return;
      }
      if (Date.now() - started >= timeoutMs) {
        clearInterval(timer);
        resolve({ ok: false, error: `Timeout after ${timeoutMs}ms` });
      }
    }, 150);
  });
}

function decodeUiValue(value = '') {
  return String(value)
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&')
    .trim();
}

function normalizeText(value = '') {
  return decodeUiValue(value).toLowerCase().replace(/\s+/g, ' ').trim();
}

function parseUiTreeNodes(uiTree = '') {
  const nodes = [];

  // Android app parser format:
  // [android.widget.TextView] text='OK' desc='' resId='...' bounds=[1,2][3,4] click edit
  const lineRe = /\[([^\]]+)\]\s+text='([^']*)'\s+desc='([^']*)'\s+resId='([^']*)'\s+bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\]([^\n]*)/g;
  let line;
  while ((line = lineRe.exec(uiTree)) !== null) {
    const flags = line[9] || '';
    const x1 = Number(line[5]), y1 = Number(line[6]), x2 = Number(line[7]), y2 = Number(line[8]);
    nodes.push({
      className: decodeUiValue(line[1]),
      text: decodeUiValue(line[2]),
      desc: decodeUiValue(line[3]),
      resId: decodeUiValue(line[4]),
      bounds: { x1, y1, x2, y2, x: Math.floor((x1 + x2) / 2), y: Math.floor((y1 + y2) / 2), width: x2 - x1, height: y2 - y1 },
      clickable: /\bclick\b/.test(flags),
      editable: /\bedit\b/.test(flags),
      scrollable: /\bscroll\b/.test(flags),
      raw: line[0]
    });
  }

  // Standard Android uiautomator XML format.
  const xmlNodeRe = /<node\b[^>]*>/g;
  const attr = (tag, name) => new RegExp(`${name}="([^"]*)"`).exec(tag)?.[1] || '';
  let xml;
  while ((xml = xmlNodeRe.exec(uiTree)) !== null) {
    const tag = xml[0];
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    if (!b) continue;
    const x1 = Number(b[1]), y1 = Number(b[2]), x2 = Number(b[3]), y2 = Number(b[4]);
    nodes.push({
      className: decodeUiValue(attr(tag, 'class')),
      text: decodeUiValue(attr(tag, 'text')),
      desc: decodeUiValue(attr(tag, 'content-desc')),
      resId: decodeUiValue(attr(tag, 'resource-id')),
      bounds: { x1, y1, x2, y2, x: Math.floor((x1 + x2) / 2), y: Math.floor((y1 + y2) / 2), width: x2 - x1, height: y2 - y1 },
      clickable: attr(tag, 'clickable') === 'true',
      editable: /EditText/i.test(attr(tag, 'class')) || attr(tag, 'editable') === 'true',
      scrollable: attr(tag, 'scrollable') === 'true',
      raw: tag
    });
  }

  return nodes.filter((node) => node.bounds.width > 0 && node.bounds.height > 0);
}

function extractVisibleText(uiTree = '') {
  const out = [];
  for (const node of parseUiTreeNodes(uiTree)) {
    for (const value of [node.text, node.desc]) {
      const text = decodeUiValue(value);
      if (text && !out.includes(text)) out.push(text);
    }
  }
  return out;
}

function scoreNodeForQuery(node, query, { editable = false } = {}) {
  const q = normalizeText(query);
  const text = normalizeText(node.text);
  const desc = normalizeText(node.desc);
  const resId = normalizeText(node.resId);
  const haystacks = [text, desc, resId].filter(Boolean);
  if (!q || haystacks.length === 0) return -1;

  let score = -1;
  for (const value of haystacks) {
    if (value === q) score = Math.max(score, 100);
    else if (value.startsWith(q)) score = Math.max(score, 85);
    else if (value.includes(q)) score = Math.max(score, 70);
  }
  if (score < 0) return -1;
  if (editable && node.editable) score += 40;
  if (!editable && node.clickable) score += 25;
  if (node.text) score += 8;
  if (node.desc) score += 5;
  if (node.bounds.width >= 24 && node.bounds.height >= 24) score += 5;
  if (node.bounds.y1 < 0 || node.bounds.x1 < 0) score -= 20;
  return score;
}

function findBestNodeByText(uiTree = '', query = '', options = {}) {
  return parseUiTreeNodes(uiTree)
    .map((node) => ({ node, score: scoreNodeForQuery(node, query, options) }))
    .filter((item) => item.score >= 0)
    .sort((a, b) => b.score - a.score || a.node.bounds.y1 - b.node.bounds.y1)[0]?.node || null;
}

function findBestEditableNode(uiTree = '', hint = '') {
  const nodes = parseUiTreeNodes(uiTree);
  if (hint) {
    const hinted = findBestNodeByText(uiTree, hint, { editable: true });
    if (hinted) return hinted;
  }
  return nodes
    .filter((node) => node.editable)
    .sort((a, b) => (b.clickable ? 1 : 0) - (a.clickable ? 1 : 0) || a.bounds.y1 - b.bounds.y1)[0] || null;
}

function findNodeBoundsByText(uiTree = '', query = '') {
  const node = findBestNodeByText(uiTree, query);
  return node ? { x: node.bounds.x, y: node.bounds.y, node } : null;
}

function summarizeUi(uiTree = '') {
  const nodes = parseUiTreeNodes(uiTree);
  const text = extractVisibleText(uiTree).join('\n');
  const elements = nodes
    .filter((node) => node.clickable || node.editable || node.scrollable || node.text || node.desc)
    .slice(0, 80)
    .map((node, index) => ({
      index,
      text: node.text,
      desc: node.desc,
      resId: node.resId,
      className: node.className,
      clickable: node.clickable,
      editable: node.editable,
      scrollable: node.scrollable,
      bounds: node.bounds
    }));
  return { screenText: text, elements, elementCount: nodes.length };
}

const SYNC_CALL_TOOLS = ['vayu_read_screen', 'vayu_screen_text', 'vayu_wait_for_text', 'vayu_find_and_tap', 'vayu_type_in_field', 'vayu_scroll_to_text', 'vayu_assert_element_visible', 'vayu_open_url_and_wait'];

app.get('/api/call', (req, res) => {
  res.json({
    ok: true,
    message: 'Synchronous tool bridge is deployed. Use POST /api/call with JSON: { tool, args?, deviceId?, timeoutMs? }.',
    aliases: ['POST /api/call', 'POST /api/tools/call', 'POST /api/tool/call'],
    supportedTools: SYNC_CALL_TOOLS,
    example: { tool: 'vayu_read_screen' }
  });
});

const handleSyncToolCall = async (req, res) => {
  const { tool, args = {}, deviceId, timeoutMs = 45000 } = req.body || {};
  if (!tool) return res.status(400).json({ ok: false, error: 'tool is required' });
  const targetDevice = pickTargetDevice(deviceId);
  if (!targetDevice) return res.status(404).json({ ok: false, error: 'No connected device found' });

  const submitAndWait = async (type, payload = {}) => {
    const requestId = crypto.randomUUID();
    const dev = devices.get(targetDevice);
    dev.pendingCommands.push({ requestId, type: normalizeDeviceCommandType(type), ...payload, timestamp: Date.now() });
    const result = await waitForResponse(requestId, timeoutMs);
    if (!result.ok) return { ok: false, error: result.error, requestId, deviceId: targetDevice };
    if (result.response?.type === 'error') return { ok: false, error: result.response.message || 'Device returned error', requestId, deviceId: targetDevice, result: result.response };
    return { ok: true, requestId, deviceId: targetDevice, result: result.response };
  };

  try {
    if (tool === 'vayu_read_screen' || tool === 'vayu_screen_text') {
      const r = await submitAndWait('screenshot_and_ui', {});
      if (!r.ok) return res.status(504).json(r);
      const uiTree = r.result.uiTree || '';
      return res.json({ ...r, ...summarizeUi(uiTree) });
    }

    if (tool === 'vayu_wait_for_text') {
      const text = (args.text || '').trim();
      if (!text) return res.status(400).json({ ok: false, error: 'args.text required' });
      const end = Date.now() + timeoutMs;
      while (Date.now() < end) {
        const r = await submitAndWait('ui_tree', {});
        if (!r.ok) return res.status(504).json(r);
        const ui = (r.result.uiTree || '').toLowerCase();
        if (ui.includes(text.toLowerCase())) return res.json({ ...r, matched: true, text });
        await new Promise((x) => setTimeout(x, 700));
      }
      return res.status(504).json({ ok: false, error: `Text not found within ${timeoutMs}ms`, text });
    }

    if (tool === 'vayu_find_and_tap') {
      const text = (args.text || '').trim();
      if (!text) return res.status(400).json({ ok: false, error: 'args.text required' });
      const look = await submitAndWait('screenshot_and_ui', {});
      if (!look.ok) return res.status(504).json(look);
      const point = findNodeBoundsByText(look.result.uiTree || '', text);
      if (!point) return res.status(404).json({ ok: false, error: `Element text not found: ${text}` });
      const tap = await submitAndWait('tap', { x: point.x, y: point.y, capture: true });
      if (!tap.ok) return res.status(504).json(tap);
      return res.json({ ...tap, tapped: point, matchedText: text });
    }

    if (tool === 'vayu_type_in_field') {
      const text = args.text || '';
      const fieldHint = (args.fieldHint || args.hint || '').trim();
      if (!text) return res.status(400).json({ ok: false, error: 'args.text required' });
      const look = await submitAndWait('screenshot_and_ui', {});
      if (!look.ok) return res.status(504).json(look);
      const node = findBestEditableNode(look.result.uiTree || '', fieldHint);
      if (!node) return res.status(404).json({ ok: false, error: `Editable field not found${fieldHint ? ` for: ${fieldHint}` : ''}`, ...summarizeUi(look.result.uiTree || '') });
      const typed = await submitAndWait('type_text', { x: node.bounds.x, y: node.bounds.y, text, capture: true });
      if (!typed.ok) return res.status(504).json(typed);
      return res.json({ ...typed, typedInto: node.bounds, fieldHint });
    }

    if (tool === 'vayu_scroll_to_text') {
      const text = (args.text || '').trim();
      if (!text) return res.status(400).json({ ok: false, error: 'args.text required' });
      const maxScrolls = Math.min(Number(args.maxScrolls || 6), 12);
      for (let i = 0; i <= maxScrolls; i++) {
        const look = await submitAndWait('screenshot_and_ui', {});
        if (!look.ok) return res.status(504).json(look);
        const point = findNodeBoundsByText(look.result.uiTree || '', text);
        if (point) return res.json({ ...look, matched: true, text, point, scrolls: i, ...summarizeUi(look.result.uiTree || '') });
        await submitAndWait('swipe', { x: 540, y: 1650, x2: 540, y2: 550, duration: 450, capture: false });
        await new Promise((x) => setTimeout(x, 500));
      }
      return res.status(404).json({ ok: false, error: `Text not found after scrolling: ${text}` });
    }

    if (tool === 'vayu_assert_element_visible') {
      const text = (args.text || '').trim();
      if (!text) return res.status(400).json({ ok: false, error: 'args.text required' });
      const look = await submitAndWait('ui_tree', {});
      if (!look.ok) return res.status(504).json(look);
      const point = findNodeBoundsByText(look.result.uiTree || '', text);
      return res.json({ ok: true, visible: Boolean(point), text, point, deviceId: targetDevice });
    }

    if (tool === 'vayu_open_url_and_wait') {
      const url = args.url;
      if (!url) return res.status(400).json({ ok: false, error: 'args.url required' });
      const open = await submitAndWait('open_chrome_url', { url });
      if (!open.ok) return res.status(504).json(open);
      const waitText = args.waitText || '';
      if (waitText) {
        const wait = await (async () => {
          const end = Date.now() + timeoutMs;
          while (Date.now() < end) {
            const r = await submitAndWait('ui_tree', {});
            if (!r.ok) return r;
            if ((r.result.uiTree || '').toLowerCase().includes(waitText.toLowerCase())) return { ok: true, result: r.result };
            await new Promise((x) => setTimeout(x, 700));
          }
          return { ok: false, error: `waitText not found: ${waitText}` };
        })();
        if (!wait.ok) return res.status(504).json(wait);
      }
      return res.json(open);
    }

    return res.status(400).json({
      ok: false,
      error: `Unknown tool '${tool}'`,
      supportedTools: SYNC_CALL_TOOLS
    });
  } catch (e) {
    return res.status(500).json({ ok: false, error: e.message || String(e) });
  }
};

app.post('/api/call', handleSyncToolCall);
app.post('/api/tools/call', handleSyncToolCall);
app.post('/api/tool/call', handleSyncToolCall);

function detectProviderFromKey(apiKey = '', baseUrl = '') {
  if (/^AIza/i.test(apiKey)) return 'gemini';
  if (/^nvapi-/i.test(apiKey)) return 'nvidia';
  if (/^sk-/i.test(apiKey) || /openai/i.test(baseUrl)) return 'openai';
  return baseUrl ? 'custom' : 'unknown';
}

function rankAgentModel(id = '', provider = '') {
  const m = id.toLowerCase();
  let score = 0;
  if (/gpt-5|gemini-2\.5-pro|claude-4|o3/.test(m)) score += 100;
  else if (/gpt-4\.1|gpt-4o|gemini-2\.5-flash|o4|nemotron/.test(m)) score += 90;
  else if (/gemini-2\.0-flash|llama-3\.1-405|gpt-4/.test(m)) score += 80;
  else if (/flash|mini|small|nano|lite/.test(m)) score += 55;
  else score += 35;
  if (/vision|4o|gpt-4\.1|gpt-5|gemini|vl|multimodal/.test(m)) score += 25;
  if (/preview|experimental|exp|deprecated|embedding|tts|audio|image|moderation/.test(m)) score -= 30;
  if (provider === 'gemini' && /generatecontent|gemini/.test(m)) score += 10;
  return score;
}

async function fetchAvailableModels({ provider, apiKey, baseUrl = '' }) {
  const headers = {};
  let url;
  if (provider === 'gemini') {
    url = `https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(apiKey)}`;
  } else if (provider === 'nvidia') {
    url = 'https://integrate.api.nvidia.com/v1/models';
    headers.Authorization = `Bearer ${apiKey}`;
  } else {
    url = `${(baseUrl || 'https://api.openai.com/v1').replace(/\/$/, '')}/models`;
    headers.Authorization = `Bearer ${apiKey}`;
  }
  const response = await fetch(url, { headers });
  const text = await response.text();
  if (!response.ok) throw new Error(`Model list HTTP ${response.status}: ${text.slice(0, 250)}`);
  const json = JSON.parse(text || '{}');
  const rawModels = json.data || json.models || [];
  return rawModels.map((model) => {
    const id = (model.id || model.name || '').replace(/^models\//, '');
    const methods = model.supportedGenerationMethods || [];
    return { id, methods, raw: model };
  }).filter((model) => model.id && !/embedding|tts|audio|image|moderation/i.test(model.id));
}

async function handleModelRecommend(req, res) {
  try {
    const input = req.method === 'GET' ? req.query : (req.body || {});
    const apiKey = String(input.apiKey || process.env.VAYU_LLM_API_KEY || '').trim();
    const baseUrl = String(input.baseUrl || '').trim();
    const provider = String(input.provider || detectProviderFromKey(apiKey, baseUrl)).toLowerCase();
    if (!apiKey) return res.status(400).json({ ok: false, error: 'apiKey is required (or set VAYU_LLM_API_KEY on Render)' });
    const models = await fetchAvailableModels({ provider, apiKey, baseUrl });
    const ranked = models
      .filter((model) => provider !== 'gemini' || model.methods.length === 0 || model.methods.includes('generateContent'))
      .map((model) => ({ id: model.id, provider, score: rankAgentModel(model.id, provider), supportsVisionLikely: /vision|4o|gpt-4\.1|gpt-5|gemini|vl|multimodal/i.test(model.id), methods: model.methods }))
      .sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
    res.json({ ok: true, provider, recommended: ranked.slice(0, 8), selected: ranked[0] || null, count: ranked.length });
  } catch (e) {
    res.status(502).json({ ok: false, error: e.message || String(e) });
  }
}

app.get('/api/models/recommend', handleModelRecommend);
app.post('/api/models/recommend', handleModelRecommend);

// ─── MCP Client: List Devices ───
app.get('/api/devices', (req, res) => {
  const deviceList = [];
  for (const [id, dev] of devices) {
    const isStale = Date.now() - dev.lastSeen > DEVICE_STALE_MS;
    const status = statusUpdates.get(id);
    deviceList.push({
      deviceId: id,
      model: dev.info.model,
      androidVersion: dev.info.androidVersion,
      connected: !isStale,
      lastSeen: dev.lastSeen,
      pendingCommands: dev.pendingCommands.length,
      status: status || null
    });
  }
  res.json({ devices: deviceList, count: deviceList.length });
});

// ─── MCP Client: Get Device Status ───
app.get('/api/device/:deviceId/status', (req, res) => {
  const { deviceId } = req.params;
  const device = devices.get(deviceId);
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }

  const status = statusUpdates.get(deviceId);
  res.json({
    deviceId,
    connected: Date.now() - device.lastSeen < DEVICE_STALE_MS,
    lastSeen: device.lastSeen,
    model: device.info.model,
    status: status || null
  });
});

// ─── Periodic Cleanup ───
setInterval(() => {
  const now = Date.now();

  for (const [id, dev] of devices) {
    if (now - dev.lastSeen > DEVICE_STALE_MS) {
      dev.connected = false;
    }
    dev.pendingCommands = dev.pendingCommands.filter(c => now - c.timestamp < COMMAND_TTL_MS);
  }

  for (const [id, resp] of responses) {
    if (now - resp.timestamp > RESPONSE_TTL_MS) {
      responses.delete(id);
    }
  }

  for (const [id, stat] of statusUpdates) {
    if (now - stat.timestamp > DEVICE_STALE_MS) {
      statusUpdates.delete(id);
    }
  }
}, CLEANUP_INTERVAL_MS);

// ─── Root endpoint ───
app.get('/', (req, res) => {
  res.json({
    name: 'V.A.Y.U MCP Relay Server',
    version: '6.0.0',
    protocol: 'HTTP Long-Polling + MCP SSE + Streamable HTTP',
    features: ['auto-capture', 'sequence-commands', 'chrome-url-macro', 'content-workflows', 'sync-api-call', 'mcp-sse', 'mcp-streamable-http'],
    endpoints: {
      'POST /api/register': 'Device registration',
      'GET  /api/poll': 'Device long-poll for commands',
      'POST /api/status': 'Device push status',
      'POST /api/response': 'Device push command response',
      'POST /api/command': 'MCP client send command',
      'POST /api/call': 'Synchronous tool bridge (returns result directly)',
      'GET  /api/call': 'Synchronous bridge usage/deploy check',
      'POST /api/tools/call': 'Alias for POST /api/call',
      'GET/POST /api/models/recommend': 'List and rank available LLMs for agentic V.A.Y.U tasks',
      'GET  /api/response/:requestId': 'MCP client poll for response',
      'GET  /api/devices': 'List connected devices',
      'GET  /api/device/:deviceId/status': 'Get device status',
      'GET  /api/health': 'Health check',
      'GET  /api/warmup': 'Keep server awake',
      'GET  /sse': 'MCP SSE endpoint (Claude.ai)',
      'POST /messages': 'MCP SSE messages (Claude.ai)',
      'POST /mcp': 'MCP Streamable HTTP endpoint (Claude.ai)',
    }
  });
});

// ═══════════════════════════════════════════════════════════════════
// MCP TRANSPORT LAYER — SSE + Streamable HTTP for Claude.ai
// ═══════════════════════════════════════════════════════════════════

let mcpTransportEnabled = false;

try {
  const { Server: McpServer } = require('@modelcontextprotocol/sdk/server/index.js');
  const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sse.js');
  const {
    CallToolRequestSchema,
    ListToolsRequestSchema,
  } = require('@modelcontextprotocol/sdk/types.js');

  // ─── MCP Tool Definitions ───
  const MCP_TOOLS = [
    {
      name: 'vayu_look',
      description: 'Observe the current phone screen. Captures screenshot AND UI tree. Use this FIRST to understand what\'s on screen before taking any action.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_read_screen',
      description: 'SMART: Read current screen as plain text plus a compact list of tappable/editable elements. Use this before choosing actions; easier than raw UI XML.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_find_and_tap',
      description: 'SMART SELECTOR: Find a visible UI element by text/content description/resource id and tap its center. Avoid coordinate guessing.',
      inputSchema: {
        type: 'object',
        properties: { text: { type: 'string', description: 'Visible text, content description, or resource id fragment to tap' } },
        required: ['text'],
      },
    },
    {
      name: 'vayu_type_in_field',
      description: 'SMART SELECTOR: Type text into an editable field found by hint/label/resource id. Avoid coordinate guessing.',
      inputSchema: {
        type: 'object',
        properties: {
          text: { type: 'string', description: 'Text to type' },
          fieldHint: { type: 'string', description: 'Optional field label/hint/resource id, e.g. Search, Email, Prompt' },
        },
        required: ['text'],
      },
    },
    {
      name: 'vayu_wait_for_text',
      description: 'SMART WAIT: Wait until text appears on screen, then return the matching UI state.',
      inputSchema: {
        type: 'object',
        properties: { text: { type: 'string' }, timeoutMs: { type: 'integer', description: 'Max wait in milliseconds' } },
        required: ['text'],
      },
    },
    {
      name: 'vayu_scroll_to_text',
      description: 'SMART SELECTOR: Scroll down until text is visible and return its coordinates/summary.',
      inputSchema: {
        type: 'object',
        properties: { text: { type: 'string' }, maxScrolls: { type: 'integer' } },
        required: ['text'],
      },
    },
    {
      name: 'vayu_assert_element_visible',
      description: 'SMART CHECK: Return true/false if text/content description/resource id is visible.',
      inputSchema: {
        type: 'object',
        properties: { text: { type: 'string' } },
        required: ['text'],
      },
    },
    {
      name: 'vayu_tap',
      description: 'Tap the phone screen at specific coordinates. Auto-captures screenshot + UI tree after tapping.',
      inputSchema: {
        type: 'object',
        properties: {
          x: { type: 'integer', description: 'X coordinate to tap' },
          y: { type: 'integer', description: 'Y coordinate to tap' },
        },
        required: ['x', 'y'],
      },
    },
    {
      name: 'vayu_swipe',
      description: 'Swipe on the phone screen from one point to another. Auto-captures screenshot.',
      inputSchema: {
        type: 'object',
        properties: {
          x: { type: 'integer', description: 'Start X coordinate' },
          y: { type: 'integer', description: 'Start Y coordinate' },
          x2: { type: 'integer', description: 'End X coordinate' },
          y2: { type: 'integer', description: 'End Y coordinate' },
          duration: { type: 'integer', description: 'Swipe duration in ms (default: 400)' },
        },
        required: ['x', 'y', 'x2', 'y2'],
      },
    },
    {
      name: 'vayu_long_press',
      description: 'Long press at specific coordinates. Auto-captures screenshot.',
      inputSchema: {
        type: 'object',
        properties: {
          x: { type: 'integer', description: 'X coordinate' },
          y: { type: 'integer', description: 'Y coordinate' },
        },
        required: ['x', 'y'],
      },
    },
    {
      name: 'vayu_type_text',
      description: 'Type text into a field at specific coordinates. Uses clipboard paste for WebView/React compatibility. Auto-captures screenshot.',
      inputSchema: {
        type: 'object',
        properties: {
          x: { type: 'integer', description: 'X coordinate of the text field' },
          y: { type: 'integer', description: 'Y coordinate of the text field' },
          text: { type: 'string', description: 'Text to type' },
        },
        required: ['x', 'y', 'text'],
      },
    },
    {
      name: 'vayu_press_back',
      description: 'Press the Android back button. Auto-captures screenshot.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_press_home',
      description: 'Press the Android home button. Auto-captures screenshot.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_press_recents',
      description: 'Press the Android recents/overview button. Auto-captures screenshot.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_open_app',
      description: 'Open an app on the phone by name or package name. Auto-captures screenshot.',
      inputSchema: {
        type: 'object',
        properties: {
          app: { type: 'string', description: 'App name (e.g., "Chrome") or package name' },
        },
        required: ['app'],
      },
    },
    {
      name: 'vayu_open_url',
      description: 'Open a URL in Chrome browser. Auto-captures screenshot after page loads.',
      inputSchema: {
        type: 'object',
        properties: {
          url: { type: 'string', description: 'URL to open' },
        },
        required: ['url'],
      },
    },
    {
      name: 'vayu_open_chrome_url',
      description: 'FAST MACRO: Opens Chrome, types URL, presses Enter — all on-phone with zero round-trips. Fastest way to navigate.',
      inputSchema: {
        type: 'object',
        properties: {
          url: { type: 'string', description: 'Full URL to navigate to' },
        },
        required: ['url'],
      },
    },
    {
      name: 'vayu_sequence',
      description: 'Execute multiple actions in sequence with a single command. MUCH FASTER than individual commands. Auto-captures screenshot after all actions complete.',
      inputSchema: {
        type: 'object',
        properties: {
          actions: {
            type: 'array',
            description: 'Array of actions to execute in sequence',
            items: {
              type: 'object',
              properties: {
                action: { type: 'string', enum: ['TAP', 'LONG_PRESS', 'SWIPE', 'SCROLL', 'TYPE', 'PRESS_BACK', 'PRESS_HOME', 'PRESS_RECENTS', 'OPEN_APP', 'WAIT'], description: 'Action type' },
                x: { type: 'integer', description: 'X coordinate' },
                y: { type: 'integer', description: 'Y coordinate' },
                x2: { type: 'integer', description: 'End X coordinate (SWIPE)' },
                y2: { type: 'integer', description: 'End Y coordinate (SWIPE)' },
                text: { type: 'string', description: 'Text to type (TYPE)' },
                app: { type: 'string', description: 'App name (OPEN_APP)' },
                delay: { type: 'integer', description: 'Custom delay in ms' },
                duration: { type: 'integer', description: 'Gesture duration in ms' },
              },
              required: ['action'],
            },
          },
        },
        required: ['actions'],
      },
    },
    {
      name: 'vayu_screenshot',
      description: 'Capture a screenshot from the phone. Returns base64 JPEG.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_ui_tree',
      description: 'Get the current UI tree from the phone.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_status',
      description: 'Get the current status of your V.A.Y.U agent.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_kill',
      description: 'Kill the currently running task on V.A.Y.U.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_list_apps',
      description: 'List all installed apps on the V.A.Y.U device.',
      inputSchema: { type: 'object', properties: {} },
    },
    {
      name: 'vayu_devices',
      description: 'List all V.A.Y.U devices connected to the relay server.',
      inputSchema: { type: 'object', properties: {} },
    },

    // ═══════════════════════════════════════════════════════════
    // CONTENT CREATION WORKFLOW TOOLS — For "Why You" AI Channel
    // High-level macros that chain multiple actions for speed
    // ═══════════════════════════════════════════════════════════

    {
      name: 'vayu_create_image',
      description: 'CONTENT WORKFLOW: Generate AI image → download → view. Opens an AI image generator (Google Flow Studios, Ideogram, etc.), enters the prompt, and waits for the image to generate. FAST: Uses sequence commands to minimize round-trips.',
      inputSchema: {
        type: 'object',
        properties: {
          url: { type: 'string', description: 'AI image generator URL (e.g., https://labs.google/fx/tools/flow)' },
          prompt: { type: 'string', description: 'Image generation prompt' },
        },
        required: ['url', 'prompt'],
      },
    },
    {
      name: 'vayu_create_video',
      description: 'CONTENT WORKFLOW: Generate AI video clip. Opens an AI video generator (Google Veo, Runway, etc.), enters the prompt, and waits for the video. Returns screenshot of the result.',
      inputSchema: {
        type: 'object',
        properties: {
          url: { type: 'string', description: 'AI video generator URL' },
          prompt: { type: 'string', description: 'Video generation prompt' },
        },
        required: ['url', 'prompt'],
      },
    },
    {
      name: 'vayu_edit_in_capcut',
      description: 'CONTENT WORKFLOW: Open CapCut with a specific project or start new edit. Opens CapCut app and navigates to create/edit mode.',
      inputSchema: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['new_project', 'open_recent', 'edit_current'], description: 'What to do in CapCut' },
        },
        required: ['action'],
      },
    },
    {
      name: 'vayu_post_content',
      description: 'CONTENT WORKFLOW: Post content to social media (YouTube, Instagram, etc.). Opens the app, navigates to create post, and fills in the content. Returns screenshot for review.',
      inputSchema: {
        type: 'object',
        properties: {
          platform: { type: 'string', enum: ['youtube', 'instagram', 'twitter', 'tiktok'], description: 'Social media platform' },
          title: { type: 'string', description: 'Post title or caption' },
          description: { type: 'string', description: 'Post description or body text' },
        },
        required: ['platform'],
      },
    },
    {
      name: 'vayu_quick_sequence',
      description: 'FAST: Execute a predefined content creation sequence. Combines common multi-step workflows into one command. Available workflows: "open_and_prompt" (open URL + type prompt + submit), "download_and_save" (long press + save image), "scroll_and_find" (scroll down to find element by text).',
      inputSchema: {
        type: 'object',
        properties: {
          workflow: { type: 'string', enum: ['open_and_prompt', 'download_and_save', 'scroll_and_find', 'dismiss_popup', 'click_button_by_text'], description: 'Predefined workflow to execute' },
          url: { type: 'string', description: 'URL (for open_and_prompt)' },
          prompt: { type: 'string', description: 'Prompt text (for open_and_prompt)' },
          searchText: { type: 'string', description: 'Text to search for (for scroll_and_find, click_button_by_text)' },
          imageX: { type: 'integer', description: 'X coordinate of image (for download_and_save)' },
          imageY: { type: 'integer', description: 'Y coordinate of image (for download_and_save)' },
        },
        required: ['workflow'],
      },
    },
  ];

  // ─── Helper: Create a new MCP server instance ───
  function createMcpServer() {
    const server = new McpServer(
      { name: 'vayu-mcp-server', version: '6.0.0' },
      { capabilities: { tools: {} } }
    );

    server.setRequestHandler(ListToolsRequestSchema, async () => {
      return { tools: MCP_TOOLS };
    });

    server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const { name, arguments: args } = request.params;
      try {
        switch (name) {
          case 'vayu_read_screen': {
            const r = await mcpSendCommand('screenshot_and_ui');
            if (r.isError) return r;
            const p = r.raw || {};
            const summary = summarizeUi(p.uiTree || '');
            const c = [];
            if (p.base64 && p.base64.length > 100) c.push({ type: 'image', data: p.base64, mimeType: p.mimeType || 'image/jpeg' });
            c.push({ type: 'text', text: `Screen text:
${summary.screenText || '(no text)'}

Smart elements:
${JSON.stringify(summary.elements.slice(0, 40), null, 2)}` });
            return { content: c };
          }
          case 'vayu_find_and_tap': {
            const lookR = await mcpSendCommand('screenshot_and_ui');
            if (lookR.isError) return lookR;
            const p = lookR.raw || {};
            const point = findNodeBoundsByText(p.uiTree || '', args?.text || '');
            if (!point) return { content: [{ type: 'text', text: `Element not found: ${args?.text}

${JSON.stringify(summarizeUi(p.uiTree || ''), null, 2)}` }], isError: true };
            const tapR = await mcpSendCommand('tap', { x: point.x, y: point.y });
            return tapR.isError ? tapR : mcpFormatCapture(tapR, `find_and_tap(${args?.text}) @ ${point.x},${point.y}`);
          }
          case 'vayu_type_in_field': {
            const lookR = await mcpSendCommand('screenshot_and_ui');
            if (lookR.isError) return lookR;
            const p = lookR.raw || {};
            const node = findBestEditableNode(p.uiTree || '', args?.fieldHint || '');
            if (!node) return { content: [{ type: 'text', text: `Editable field not found.

${JSON.stringify(summarizeUi(p.uiTree || ''), null, 2)}` }], isError: true };
            const typeR = await mcpSendCommand('type_text', { x: node.bounds.x, y: node.bounds.y, text: args?.text || '' });
            return typeR.isError ? typeR : mcpFormatCapture(typeR, `type_in_field(${args?.fieldHint || 'first editable'})`);
          }
          case 'vayu_wait_for_text': {
            const end = Date.now() + Math.min(args?.timeoutMs || 45000, 120000);
            while (Date.now() < end) {
              const r = await mcpSendCommand('ui_tree');
              if (r.isError) return r;
              const uiTree = r.raw?.uiTree || '';
              if (findNodeBoundsByText(uiTree, args?.text || '') || uiTree.toLowerCase().includes(String(args?.text || '').toLowerCase())) {
                return { content: [{ type: 'text', text: `Found text: ${args?.text}

${JSON.stringify(summarizeUi(uiTree), null, 2)}` }] };
              }
              await new Promise(resolve => setTimeout(resolve, 800));
            }
            return { content: [{ type: 'text', text: `Timeout waiting for text: ${args?.text}` }], isError: true };
          }
          case 'vayu_scroll_to_text': {
            const maxScrolls = Math.min(args?.maxScrolls || 6, 12);
            for (let i = 0; i <= maxScrolls; i++) {
              const lookR = await mcpSendCommand('screenshot_and_ui');
              if (lookR.isError) return lookR;
              const p = lookR.raw || {};
              const point = findNodeBoundsByText(p.uiTree || '', args?.text || '');
              if (point) return mcpFormatCapture({ raw: { ...p, success: true, scrolls: i, text: args?.text, point } }, `scroll_to_text(${args?.text})`);
              await mcpSendCommand('swipe', { x: 540, y: 1650, x2: 540, y2: 550, duration: 450, capture: false });
            }
            return { content: [{ type: 'text', text: `Text not found after scrolling: ${args?.text}` }], isError: true };
          }
          case 'vayu_assert_element_visible': {
            const r = await mcpSendCommand('ui_tree');
            if (r.isError) return r;
            const point = findNodeBoundsByText(r.raw?.uiTree || '', args?.text || '');
            return { content: [{ type: 'text', text: JSON.stringify({ visible: Boolean(point), text: args?.text, point }, null, 2) }] };
          }
          case 'vayu_tap': {
            const r = await mcpSendCommand('tap', { x: args?.x, y: args?.y });
            return r.isError ? r : mcpFormatCapture(r, `tap(${args?.x}, ${args?.y})`);
          }
          case 'vayu_swipe': {
            const r = await mcpSendCommand('swipe', { x: args?.x, y: args?.y, x2: args?.x2, y2: args?.y2, duration: args?.duration });
            return r.isError ? r : mcpFormatCapture(r, `swipe(${args?.x},${args?.y} → ${args?.x2},${args?.y2})`);
          }
          case 'vayu_long_press': {
            const r = await mcpSendCommand('long_press', { x: args?.x, y: args?.y });
            return r.isError ? r : mcpFormatCapture(r, `long_press(${args?.x}, ${args?.y})`);
          }
          case 'vayu_type_text': {
            const r = await mcpSendCommand('type_text', { x: args?.x, y: args?.y, text: args?.text });
            return r.isError ? r : mcpFormatCapture(r, `type("${args?.text?.substring(0, 50)}")`);
          }
          case 'vayu_press_back': {
            const r = await mcpSendCommand('press_back');
            return r.isError ? r : mcpFormatCapture(r, 'press_back');
          }
          case 'vayu_press_home': {
            const r = await mcpSendCommand('press_home');
            return r.isError ? r : mcpFormatCapture(r, 'press_home');
          }
          case 'vayu_press_recents': {
            const r = await mcpSendCommand('press_recents');
            return r.isError ? r : mcpFormatCapture(r, 'press_recents');
          }
          case 'vayu_open_app': {
            const r = await mcpSendCommand('open_app', { app: args?.app });
            return r.isError ? r : mcpFormatCapture(r, `open_app(${args?.app})`);
          }
          case 'vayu_open_url': {
            const r = await mcpSendCommand('open_url', { url: args?.url });
            return r.isError ? r : mcpFormatCapture(r, `open_url(${args?.url})`);
          }
          case 'vayu_open_chrome_url': {
            const r = await mcpSendCommand('open_chrome_url', { url: args?.url });
            return r.isError ? r : mcpFormatCapture(r, `open_chrome_url(${args?.url})`);
          }
          case 'vayu_sequence': {
            const r = await mcpSendCommand('sequence', { actions: args?.actions });
            return r.isError ? r : mcpFormatCapture(r, `sequence(${args?.actions?.length} actions)`);
          }
          case 'vayu_screenshot': {
            const r = await mcpSendCommand('screenshot');
            if (r.isError) return r;
            const p = r.raw || {};
            if (p.base64) return { content: [{ type: 'image', data: p.base64, mimeType: p.mimeType || 'image/jpeg' }, { type: 'text', text: `Screenshot from device: ${p.deviceId || 'unknown'}` }] };
            return mcpFormatSimple(r);
          }
          case 'vayu_ui_tree': {
            const r = await mcpSendCommand('ui_tree');
            return r.isError ? r : mcpFormatSimple(r);
          }
          case 'vayu_look': {
            const r = await mcpSendCommand('screenshot_and_ui');
            if (r.isError) return r;
            const p = r.raw || {};
            const c = [];
            if (p.base64 && p.base64.length > 100) c.push({ type: 'image', data: p.base64, mimeType: p.mimeType || 'image/jpeg' });
            if (p.uiTree && p.uiTree.length > 10) c.push({ type: 'text', text: `UI Tree:\n${p.uiTree}` });
            return c.length > 0 ? { content: c } : mcpFormatSimple(r);
          }
          case 'vayu_status': {
            const r = await mcpSendCommand('status');
            return r.isError ? r : mcpFormatSimple(r);
          }
          case 'vayu_kill': {
            const r = await mcpSendCommand('kill');
            return r.isError ? r : mcpFormatSimple(r);
          }
          case 'vayu_list_apps': {
            const r = await mcpSendCommand('list_apps');
            return r.isError ? r : mcpFormatSimple(r);
          }
          case 'vayu_devices': {
            const deviceList = [];
            for (const [id, dev] of devices) {
              const isStale = Date.now() - dev.lastSeen > DEVICE_STALE_MS;
              const status = statusUpdates.get(id);
              deviceList.push(`${isStale ? '[OFFLINE]' : '[ONLINE]'} ${id}\n   Model: ${dev.info.model}\n   Android: ${dev.info.androidVersion}\n   Status: ${status ? status.status : 'Idle'}`);
            }
            return { content: [{ type: 'text', text: deviceList.length > 0 ? deviceList.join('\n\n') : 'No V.A.Y.U devices connected.' }] };
          }

          // ═══════════════════════════════════════════════════════
          // CONTENT CREATION WORKFLOW HANDLERS
          // ═══════════════════════════════════════════════════════

          case 'vayu_create_image': {
            // Step 1: Open the AI image generator URL
            const openR = await mcpSendCommand('open_chrome_url', { url: args?.url });
            if (openR.isError) return openR;
            // Step 2: Look at the screen to find the prompt input
            const lookR = await mcpSendCommand('screenshot_and_ui');
            if (lookR.isError) return lookR;
            const lookData = lookR.raw || {};
            const resultContent = [];
            if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
            resultContent.push({ type: 'text', text: `Opened ${args?.url}. Now look at the screen and find the prompt input field, then use vayu_type_text + vayu_tap to submit. UI Tree:\n${lookData.uiTree || 'N/A'}` });
            return { content: resultContent };
          }

          case 'vayu_create_video': {
            const openR = await mcpSendCommand('open_chrome_url', { url: args?.url });
            if (openR.isError) return openR;
            const lookR = await mcpSendCommand('screenshot_and_ui');
            if (lookR.isError) return lookR;
            const lookData = lookR.raw || {};
            const resultContent = [];
            if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
            resultContent.push({ type: 'text', text: `Opened ${args?.url} for video generation. Find the prompt field and use vayu_type_text to enter your prompt. UI Tree:\n${lookData.uiTree || 'N/A'}` });
            return { content: resultContent };
          }

          case 'vayu_edit_in_capcut': {
            const openR = await mcpSendCommand('open_app', { app: 'CapCut' });
            if (openR.isError) return openR;
            // Wait for CapCut to load
            await new Promise(r => setTimeout(r, 3000));
            const lookR = await mcpSendCommand('screenshot_and_ui');
            if (lookR.isError) return lookR;
            const lookData = lookR.raw || {};
            const resultContent = [];
            if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
            resultContent.push({ type: 'text', text: `CapCut opened. Action: ${args?.action}. Look at the screen to find the right button. UI Tree:\n${lookData.uiTree || 'N/A'}` });
            return { content: resultContent };
          }

          case 'vayu_post_content': {
            const appMap = { youtube: 'YouTube', instagram: 'Instagram', twitter: 'X', tiktok: 'TikTok' };
            const appName = appMap[args?.platform] || args?.platform;
            const openR = await mcpSendCommand('open_app', { app: appName });
            if (openR.isError) return openR;
            await new Promise(r => setTimeout(r, 2000));
            const lookR = await mcpSendCommand('screenshot_and_ui');
            if (lookR.isError) return lookR;
            const lookData = lookR.raw || {};
            const resultContent = [];
            if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
            resultContent.push({ type: 'text', text: `Opened ${appName}. Title: ${args?.title || 'N/A'}. Description: ${args?.description || 'N/A'}. Find the create/post button and proceed. UI Tree:\n${lookData.uiTree || 'N/A'}` });
            return { content: resultContent };
          }

          case 'vayu_quick_sequence': {
            const workflow = args?.workflow;
            if (workflow === 'open_and_prompt') {
              // Open URL, then type prompt, then tap submit (3 steps in one)
              const openR = await mcpSendCommand('open_chrome_url', { url: args?.url });
              if (openR.isError) return openR;
              const lookR = await mcpSendCommand('screenshot_and_ui');
              if (lookR.isError) return lookR;
              const lookData = lookR.raw || {};
              const resultContent = [];
              if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
              resultContent.push({ type: 'text', text: `URL opened. Now find the prompt input field coordinates and use vayu_type_text to enter: "${args?.prompt || ''}". Then find the submit/generate button. UI Tree:\n${lookData.uiTree || 'N/A'}` });
              return { content: resultContent };
            } else if (workflow === 'download_and_save') {
              // Long press on image, then find Save option
              const seqR = await mcpSendCommand('sequence', { actions: [
                { action: 'LONG_PRESS', x: args?.imageX || 540, y: args?.imageY || 960 },
                { action: 'WAIT', delay: 500 },
              ] });
              if (seqR.isError) return seqR;
              const lookR = await mcpSendCommand('screenshot_and_ui');
              if (lookR.isError) return lookR;
              const lookData = lookR.raw || {};
              const resultContent = [];
              if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
              resultContent.push({ type: 'text', text: `Long-pressed at (${args?.imageX}, ${args?.imageY}). Look for "Save image" or "Download" option in the context menu. UI Tree:\n${lookData.uiTree || 'N/A'}` });
              return { content: resultContent };
            } else if (workflow === 'scroll_and_find' || workflow === 'click_button_by_text') {
              // Scroll down to find text, or click a button by its text
              const lookR = await mcpSendCommand('screenshot_and_ui');
              if (lookR.isError) return lookR;
              const lookData = lookR.raw || {};
              const uiTree = lookData.uiTree || '';
              const searchText = args?.searchText || '';

              // Search in UI tree for the text
              if (searchText && uiTree.includes(searchText)) {
                // Try to find coordinates from UI tree
                const resultContent = [];
                if (lookData.base64) resultContent.push({ type: 'image', data: lookData.base64, mimeType: lookData.mimeType || 'image/jpeg' });
                resultContent.push({ type: 'text', text: `Found "${searchText}" on screen! Look at the UI tree for its coordinates and tap it. UI Tree:\n${uiTree}` });
                return { content: resultContent };
              } else if (searchText) {
                // Text not found — scroll down and try again
                const scrollR = await mcpSendCommand('sequence', { actions: [
                  { action: 'SCROLL', x: 540, y: 1500, x2: 540, y2: 500 },
                ] });
                const lookR2 = await mcpSendCommand('screenshot_and_ui');
                const lookData2 = lookR2.raw || {};
                const resultContent = [];
                if (lookData2.base64) resultContent.push({ type: 'image', data: lookData2.base64, mimeType: lookData2.mimeType || 'image/jpeg' });
                resultContent.push({ type: 'text', text: `Scrolled down. ${lookData2.uiTree?.includes(searchText) ? `Found "${searchText}"!` : `"${searchText}" still not visible, may need more scrolling.`} UI Tree:\n${lookData2.uiTree || 'N/A'}` });
                return { content: resultContent };
              }
              return { content: [{ type: 'text', text: 'No searchText provided for workflow.' }] };
            } else if (workflow === 'dismiss_popup') {
              // Try pressing back to dismiss popup/dialog
              const backR = await mcpSendCommand('press_back');
              if (backR.isError) return backR;
              return mcpFormatCapture(backR, 'dismiss_popup (pressed BACK)');
            }
            return { content: [{ type: 'text', text: `Unknown workflow: ${workflow}` }] };
          }

          default:
            return { content: [{ type: 'text', text: `Unknown tool: ${name}` }], isError: true };
        }
      } catch (error) {
        return { content: [{ type: 'text', text: `Error: ${error.message}` }], isError: true };
      }
    });

    return server;
  }

  // ─── Helper: Send command to device and wait for response ───
  async function mcpSendCommand(type, params = {}) {
    const requestId = crypto.randomUUID();

    // Find best device: prefer connected+active, then any seen in last 5 min, then any at all
    let activeDevice = null;
    let fallbackDevice = null;
    let anyDevice = null;

    for (const [id, dev] of devices) {
      if (!anyDevice) anyDevice = { deviceId: id, ...dev };
      if (Date.now() - dev.lastSeen < DEVICE_STALE_MS) {
        if (!fallbackDevice) fallbackDevice = { deviceId: id, ...dev };
        if (dev.connected) {
          activeDevice = { deviceId: id, ...dev };
          break;
        }
      }
    }

    const targetDevice = activeDevice || fallbackDevice || anyDevice;

    if (!targetDevice) {
      return {
        content: [{ type: 'text', text: 'No V.A.Y.U device has ever connected to this relay server. Open the V.A.Y.U app on your phone and enable MCP Relay in Settings.' }],
        isError: true,
      };
    }

    const deviceId = targetDevice.deviceId;
    const device = devices.get(deviceId);
    const isStale = Date.now() - device.lastSeen > DEVICE_STALE_MS;

    // Queue the command regardless — device will pick it up when it reconnects
    device.pendingCommands.push({
      requestId,
      type: normalizeDeviceCommandType(type),
      ...params,
      timestamp: Date.now()
    });

    const statusLabel = isStale ? 'STALE (waiting for reconnect)' : (device.connected ? 'ONLINE' : 'POLLING');
    console.log(`[MCP] ${type} → ${deviceId} [${statusLabel}] (requestId: ${requestId})`);

    // Wait for response — longer timeout to survive Render cold starts + device reconnect
    const maxWait = 60000; // 60s (was 30s)
    const startTime = Date.now();
    while (Date.now() - startTime < maxWait) {
      const resp = responses.get(requestId);
      if (resp) {
        responses.delete(requestId);
        return { raw: resp.response };
      }
      await new Promise(r => setTimeout(r, 200));
    }

    return {
      content: [{ type: 'text', text: `Timeout: Device ${deviceId} did not respond in 60s. ${isStale ? 'Device appears OFFLINE — open the V.A.Y.U app and ensure MCP Relay is enabled.' : 'Device was connected but did not respond.'}` }],
      isError: true,
    };
  }

  // ─── Helper: Format capture response ───
  function mcpFormatCapture(result, actionLabel) {
    try {
      const parsed = result.raw || result;
      const contents = [];

      const actionInfo = { action: actionLabel, success: parsed.success !== false };
      if (parsed.type) actionInfo.type = parsed.type;
      if (parsed.url) actionInfo.url = parsed.url;
      if (parsed.app) actionInfo.app = parsed.app;
      if (parsed.text) actionInfo.text = parsed.text;
      if (parsed.actionCount) actionInfo.actionCount = parsed.actionCount;

      if (parsed.base64 && parsed.base64.length > 100) {
        contents.push({ type: 'image', data: parsed.base64, mimeType: parsed.mimeType || 'image/jpeg' });
      }

      if (parsed.uiTree && parsed.uiTree.length > 10) {
        contents.push({ type: 'text', text: `Action: ${JSON.stringify(actionInfo)}\n\nUI Tree:\n${parsed.uiTree}` });
      } else {
        contents.push({ type: 'text', text: `Action: ${JSON.stringify(actionInfo, null, 2)}` });
      }

      return { content: contents };
    } catch (e) {
      return { content: [{ type: 'text', text: JSON.stringify(result.raw || result, null, 2) }] };
    }
  }

  function mcpFormatSimple(result) {
    const parsed = result.raw || result;
    return { content: [{ type: 'text', text: JSON.stringify(parsed, null, 2) }] };
  }

  // ═══════════════════════════════════════════════════
  // SSE Transport — FIXED: No custom heartbeat, async connect
  // ═══════════════════════════════════════════════════

  let sseTransport = null;
  let sseServer = null;

  app.get('/sse', async (req, res) => {
    console.log('[MCP-SSE] New SSE connection from brain');

    // If there's already an active connection, log it but don't forcefully close it.
    // Let the old one finish naturally — killing it mid-tool-call breaks things.
    if (sseTransport) {
      console.log('[MCP-SSE] Replacing existing SSE connection');
    }

    // Create new server + transport for this connection
    const newServer = createMcpServer();
    const newTransport = new SSEServerTransport('/messages', res);

    try {
      await newServer.connect(newTransport);
    } catch (e) {
      console.error('[MCP-SSE] Failed to connect transport:', e.message);
      return;
    }

    // Only update globals AFTER successful connect
    sseServer = newServer;
    sseTransport = newTransport;

    console.log('[MCP-SSE] SSE transport connected successfully');

    // NO custom heartbeat — SSEServerTransport manages its own SSE stream.
    // Writing directly to `res` corrupts the stream and causes disconnect loops.

    req.on('close', () => {
      console.log('[MCP-SSE] SSE connection closed');
      // Only clear globals if this is still the active connection
      if (sseTransport === newTransport) {
        sseTransport = null;
        sseServer = null;
      }
    });
  });

  app.post('/messages', async (req, res) => {
    if (sseTransport) {
      try {
        await sseTransport.handlePostMessage(req, res, req.body);
      } catch (e) {
        console.error('[MCP-SSE] Error handling message:', e.message);
        if (!res.headersSent) {
          res.status(500).json({ error: 'Message handling failed' });
        }
      }
    } else {
      res.status(400).json({ error: 'No active SSE connection. Connect to /sse first.' });
    }
  });

  console.log('✅ MCP SSE transport enabled at /sse and /messages');

  // ═══════════════════════════════════════════════════
  // Streamable HTTP Transport (newer, better for proxies)
  // ═══════════════════════════════════════════════════

  try {
    const { StreamableHTTPServerTransport } = require('@modelcontextprotocol/sdk/server/streamableHttp.js');

    // Map of session ID → { transport, server }
    const streamableSessions = new Map();

    app.post('/mcp', async (req, res) => {
      console.log('[MCP-Streamable] POST /mcp request');

      const sessionId = req.headers['mcp-session-id'];
      let session = sessionId ? streamableSessions.get(sessionId) : null;

      if (!session) {
        // Create new session
        const newServer = createMcpServer();
        const newTransport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => crypto.randomUUID(),
        });
        await newServer.connect(newTransport);
        session = { transport: newTransport, server: newServer };
        if (newTransport.sessionId) {
          streamableSessions.set(newTransport.sessionId, session);
          console.log(`[MCP-Streamable] New session: ${newTransport.sessionId}`);
        }
      }

      await session.transport.handleRequest(req, res, req.body);
    });

    // GET /mcp for SSE stream (notifications from server to client)
    app.get('/mcp', async (req, res) => {
      const sessionId = req.headers['mcp-session-id'];
      const session = sessionId ? streamableSessions.get(sessionId) : null;
      if (session) {
        await session.transport.handleRequest(req, res);
      } else {
        res.status(400).json({ error: 'Invalid or missing session ID' });
      }
    });

    // DELETE /mcp to close session
    app.delete('/mcp', async (req, res) => {
      const sessionId = req.headers['mcp-session-id'];
      const session = sessionId ? streamableSessions.get(sessionId) : null;
      if (session) {
        await session.transport.handleRequest(req, res);
        streamableSessions.delete(sessionId);
        console.log(`[MCP-Streamable] Session closed: ${sessionId}`);
      } else {
        res.status(400).json({ error: 'Invalid or missing session ID' });
      }
    });

    // Cleanup stale streamable sessions every 5 minutes
    setInterval(() => {
      for (const [id, session] of streamableSessions) {
        // Sessions without activity for 10 minutes are cleaned up
        if (session.lastActivity && Date.now() - session.lastActivity > 600000) {
          streamableSessions.delete(id);
          console.log(`[MCP-Streamable] Cleaned stale session: ${id}`);
        }
      }
    }, 300000);

    console.log('✅ MCP Streamable HTTP transport enabled at /mcp');
    mcpTransportEnabled = true;

  } catch (streamableErr) {
    console.warn('⚠️  MCP Streamable HTTP transport not available:', streamableErr.message);
    console.warn('   SSE transport still works. Streamable HTTP requires SDK >= 1.4.0');
    mcpTransportEnabled = true; // SSE still works
  }

} catch (err) {
  console.warn('⚠️  MCP SSE transport not available (SDK not installed):', err.message);
  console.warn('   Run: cd vayu-relay && npm install');
}

// ─── Start Server ───
app.listen(PORT, '0.0.0.0', () => {
  console.log(`╔══════════════════════════════════════════════╗`);
  console.log(`║   V.A.Y.U MCP Relay Server v6.0               ║`);
  console.log(`║   HTTP Long-Polling + MCP SSE + Streamable   ║`);
  console.log(`║   Claude.ai: /sse or /mcp endpoint           ║`);
  console.log(`║   Port: ${PORT}                                 ║`);
  console.log(`╚══════════════════════════════════════════════╝`);
});
