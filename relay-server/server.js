/**
 * V.A.Y.U MCP Relay Server v3.0 — HTTP Long-Polling
 *
 * Improvements over v2.1:
 *   - Better connection stability (heartbeat tracking, faster reconnect)
 *   - Support for sequence commands and Chrome URL macro
 *   - Reduced response polling interval (200ms → faster command delivery)
 *   - Better cleanup of stale data
 *   - Response size limit increased for auto-capture screenshots
 *   - Device reconnection support without duplicate registration
 *
 * Endpoints:
 *   POST /api/register     — Device registers itself
 *   GET  /api/poll         — Device long-polls for commands (25s timeout)
 *   POST /api/status       — Device pushes status update
 *   POST /api/response     — Device pushes command response
 *   POST /api/command      — MCP client sends command to device
 *   GET  /api/devices      — MCP client lists connected devices
 *   GET  /api/health       — Health check
 *   GET  /api/warmup       — Force server awake
 *   GET  /                 — Server info
 */

const express = require('express');
const cors = require('cors');
const crypto = require('crypto');

const app = express();
const PORT = process.env.PORT || 10000;

// ─── Middleware ───
app.use(cors());
app.use(express.json({ limit: '20mb' })); // Increased for auto-capture screenshots

// ─── In-Memory State ───
const devices = new Map();       // deviceId → { info, lastSeen, pendingCommands[], connected }
const responses = new Map();     // requestId → { response, timestamp }
const statusUpdates = new Map(); // deviceId → { status, step, action, timestamp }

const POLL_TIMEOUT_MS = 25000;     // 25s long-poll (Render 30s limit)
const CLEANUP_INTERVAL_MS = 45000; // Clean stale data every 45s (more frequent)
const DEVICE_STALE_MS = 90000;     // Device considered stale after 90s (was 2min)
const RESPONSE_TTL_MS = 300000;    // Responses kept for 5min
const COMMAND_TTL_MS = 120000;     // Commands expire after 2min

const startTime = Date.now();

// ─── Health Check ───
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    version: '3.0.0',
    protocol: 'HTTP Long-Polling',
    devices: devices.size,
    pendingResponses: responses.size,
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

  // Update last seen & ensure registered
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

  // Check for pending commands immediately
  if (dev.pendingCommands.length > 0) {
    const commands = [];
    while (dev.pendingCommands.length > 0) {
      commands.push(dev.pendingCommands.shift());
    }
    return res.json({ commands, serverTime: Date.now() });
  }

  // Long-poll: wait up to POLL_TIMEOUT_MS for a command
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

  // Check every 300ms for pending commands (faster than 500ms)
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
      type,
      task,
      ...rest,
      timestamp: Date.now()
    });

    console.log(`[COMMAND] ${type} → ${deviceId} (requestId: ${requestId})`);
    return res.json({ ok: true, requestId, deviceId });
  }

  // No deviceId: send to first available device
  for (const [id, dev] of devices) {
    if (dev.connected && Date.now() - dev.lastSeen < DEVICE_STALE_MS) {
      dev.pendingCommands.push({
        requestId,
        type,
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

  // Check immediately
  const existing = responses.get(requestId);
  if (existing) {
    responses.delete(requestId);
    return res.json(existing.response);
  }

  // Long-poll for response (25s max)
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

  // Check every 200ms (faster than 500ms for quicker response delivery)
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
    // Clean old pending commands (> 2min)
    dev.pendingCommands = dev.pendingCommands.filter(c => now - c.timestamp < COMMAND_TTL_MS);
  }

  // Remove old responses
  for (const [id, resp] of responses) {
    if (now - resp.timestamp > RESPONSE_TTL_MS) {
      responses.delete(id);
    }
  }

  // Remove old status updates
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
    version: '4.0.0',
    protocol: 'HTTP Long-Polling + MCP SSE',
    features: ['auto-capture', 'sequence-commands', 'chrome-url-macro', 'mcp-sse-claude-ai'],
    endpoints: {
      'POST /api/register': 'Device registration',
      'GET  /api/poll': 'Device long-poll for commands',
      'POST /api/status': 'Device push status',
      'POST /api/response': 'Device push command response',
      'POST /api/command': 'MCP client send command',
      'GET  /api/response/:requestId': 'MCP client poll for response',
      'GET  /api/devices': 'List connected devices',
      'GET  /api/device/:deviceId/status': 'Get device status',
      'GET  /api/health': 'Health check',
      'GET  /api/warmup': 'Keep server awake',
      'GET  /sse': 'MCP SSE endpoint (for Claude.ai)',
      'POST /messages': 'MCP SSE messages (for Claude.ai)',
    }
  });
});

// ─── MCP SSE Transport (for Claude.ai / remote MCP clients) ───
// This allows Claude.ai to connect directly to the relay server
// without needing a local MCP server process.

try {
  const { Server: McpServer } = require('@modelcontextprotocol/sdk/server/index.js');
  const { SSEServerTransport } = require('@modelcontextprotocol/sdk/server/sse.js');
  const {
    CallToolRequestSchema,
    ListToolsRequestSchema,
  } = require('@modelcontextprotocol/sdk/types.js');

  // Create the MCP server instance (same tools as mcp-server/index.js)
  const mcpServer = new McpServer(
    { name: 'vayu-mcp-server', version: '4.0.0' },
    { capabilities: { tools: {} } }
  );

  // ─── List Tools ───
  mcpServer.setRequestHandler(ListToolsRequestSchema, async () => {
    return {
      tools: [
        {
          name: 'vayu_look',
          description: 'Observe the current phone screen. Captures screenshot AND UI tree. Use this FIRST to understand what\'s on screen before taking any action.',
          inputSchema: { type: 'object', properties: {} },
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
      ],
    };
  });

  // ─── Helper: Send command to device and wait for response ───
  async function mcpSendCommand(type, params = {}) {
    const requestId = crypto.randomUUID();
    
    // Find active device
    let activeDevice = null;
    for (const [id, dev] of devices) {
      if (dev.connected && Date.now() - dev.lastSeen < DEVICE_STALE_MS) {
        activeDevice = { deviceId: id, ...dev };
        break;
      }
    }
    
    if (!activeDevice) {
      return {
        content: [{ type: 'text', text: 'No V.A.Y.U device connected. Open the V.A.Y.U app and enable MCP Relay in Settings.' }],
        isError: true,
      };
    }

    const device = devices.get(activeDevice.deviceId);
    device.pendingCommands.push({
      requestId,
      type,
      ...params,
      timestamp: Date.now()
    });

    console.log(`[MCP-SSE] ${type} → ${activeDevice.deviceId} (requestId: ${requestId})`);

    // Wait for response (long-poll)
    const maxWait = 30000;
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
      content: [{ type: 'text', text: 'Timeout waiting for device response' }],
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

  // ─── Handle Tool Calls ───
  mcpServer.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;
    try {
      switch (name) {
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
        default:
          return { content: [{ type: 'text', text: `Unknown tool: ${name}` }], isError: true };
      }
    } catch (error) {
      return { content: [{ type: 'text', text: `Error: ${error.message}` }], isError: true };
    }
  });

  // ─── SSE Endpoint for Claude.ai ───
  // Claude.ai connects to: https://j-a-r-v-i-s-ktlh.onrender.com/sse
  let sseTransport = null;

  app.get('/sse', (req, res) => {
    console.log('[MCP-SSE] New SSE connection from Claude.ai');
    sseTransport = new SSEServerTransport('/messages', res);
    mcpServer.connect(sseTransport);
    
    req.on('close', () => {
      console.log('[MCP-SSE] SSE connection closed');
      sseTransport = null;
    });
  });

  app.post('/messages', express.json(), (req, res) => {
    if (sseTransport) {
      sseTransport.handlePostMessage(req, res);
    } else {
      res.status(400).json({ error: 'No active SSE connection' });
    }
  });

  console.log('✅ MCP SSE transport enabled at /sse and /messages');

} catch (err) {
  console.warn('⚠️  MCP SSE transport not available (SDK not installed):', err.message);
  console.warn('   Run: cd relay-server && npm install');
}

// ─── Start Server ───
app.listen(PORT, '0.0.0.0', () => {
  console.log(`╔══════════════════════════════════════════╗`);
  console.log(`║   V.A.Y.U MCP Relay Server v4.0           ║`);
  console.log(`║   HTTP Long-Polling + MCP SSE            ║`);
  console.log(`║   Claude.ai: /sse endpoint ready         ║`);
  console.log(`║   Port: ${PORT}                            ║`);
  console.log(`╚══════════════════════════════════════════╝`);
});
