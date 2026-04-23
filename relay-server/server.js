/**
 * JARVIS MCP Relay Server v2.1 — HTTP Long-Polling
 *
 * Bulletproof on Render free tier:
 *   - No WebSockets (avoids Render WS proxy issues)
 *   - HTTP long-polling with 25s timeout (stays within Render 30s limit)
 *   - Graceful cold-start handling with /api/warmup
 *   - In-memory state with automatic cleanup
 *   - CORS fully open for any client
 *
 * Endpoints:
 *   POST /api/register     — Device registers itself
 *   GET  /api/poll         — Device long-polls for commands (25s timeout)
 *   POST /api/status       — Device pushes status update
 *   POST /api/response     — Device pushes command response
 *   POST /api/command      — MCP client sends command to device
 *   GET  /api/devices      — MCP client lists connected devices
 *   GET  /api/health       — Health check
 *   GET  /api/warmup       — Force server awake (for cold-start prevention)
 *   GET  /                 — Server info
 */

const express = require('express');
const cors = require('cors');
const crypto = require('crypto');

const app = express();
const PORT = process.env.PORT || 10000;

// ─── Middleware ───
app.use(cors());
app.use(express.json({ limit: '10mb' }));

// ─── In-Memory State ───
const devices = new Map();       // deviceId → { info, lastSeen, pendingCommands[] }
const responses = new Map();     // requestId → { response, timestamp }
const statusUpdates = new Map(); // deviceId → { status, step, action, timestamp }

const POLL_TIMEOUT_MS = 25000;   // 25s long-poll (Render 30s limit)
const CLEANUP_INTERVAL_MS = 60000; // Clean stale data every 60s
const DEVICE_STALE_MS = 120000;  // Device considered stale after 2min
const RESPONSE_TTL_MS = 300000;  // Responses kept for 5min

// ─── Server uptime tracking ───
const startTime = Date.now();

// ─── Health Check ───
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    version: '2.1.0',
    protocol: 'HTTP Long-Polling',
    devices: devices.size,
    pendingResponses: responses.size,
    uptime: process.uptime(),
    uptimeMs: Date.now() - startTime,
    timestamp: Date.now()
  });
});

// ─── Warmup endpoint (prevents Render cold start) ───
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
  devices.set(deviceId, {
    info: { model: model || 'Unknown', androidVersion: androidVersion || '?', sdkVersion: sdkVersion || '?' },
    lastSeen: Date.now(),
    pendingCommands: existing?.pendingCommands || [],
    connected: true
  });

  console.log(`[REGISTER] ${deviceId} — ${model} (Android ${androidVersion})`);
  res.json({ ok: true, message: 'Registered', deviceId });
});

// ─── Device Long-Poll for Commands ───
app.get('/api/poll', async (req, res) => {
  const { deviceId, lastMsgId } = req.query;
  if (!deviceId) {
    return res.status(400).json({ error: 'deviceId required' });
  }

  // Update last seen
  const device = devices.get(deviceId);
  if (device) {
    device.lastSeen = Date.now();
    device.connected = true;
  } else {
    // Auto-register if not found
    devices.set(deviceId, {
      info: { model: 'Unknown', androidVersion: '?', sdkVersion: '?' },
      lastSeen: Date.now(),
      pendingCommands: [],
      connected: true
    });
  }

  const dev = devices.get(deviceId);

  // Check for pending commands immediately
  if (dev.pendingCommands.length > 0) {
    const commands = [];
    // Send ALL pending commands (not just one)
    while (dev.pendingCommands.length > 0) {
      commands.push(dev.pendingCommands.shift());
    }
    return res.json({ commands, serverTime: Date.now() });
  }

  // Long-poll: wait up to POLL_TIMEOUT_MS for a command
  const pollStart = Date.now();
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

  // Check every 500ms for pending commands
  intervalId = setInterval(() => {
    if (dev.pendingCommands.length > 0) {
      const commands = [];
      while (dev.pendingCommands.length > 0) {
        commands.push(dev.pendingCommands.shift());
      }
      sendResponse(commands);
    }
  }, 500);

  // Timeout after POLL_TIMEOUT_MS
  timeoutId = setTimeout(() => {
    sendResponse([]); // Empty response = no commands
  }, POLL_TIMEOUT_MS);

  // Handle client disconnect
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

  // Update device last seen
  const device = devices.get(deviceId);
  if (device) {
    device.lastSeen = Date.now();
    device.connected = true;
  }

  // Store latest status
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

  // Update device last seen
  const device = devices.get(deviceId);
  if (device) {
    device.lastSeen = Date.now();
  }

  // Store response
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

  // If deviceId specified, send to that device only
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

  // If no deviceId, send to first available device
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
  const pollStart = Date.now();
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

    if (Date.now() - pollStart >= POLL_TIMEOUT_MS) {
      sendResponse({ type: 'timeout', message: 'No response from device in time' });
    }
  }, 500);

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

  // Mark stale devices
  for (const [id, dev] of devices) {
    if (now - dev.lastSeen > DEVICE_STALE_MS) {
      dev.connected = false;
    }
    // Clean old pending commands (> 5min)
    dev.pendingCommands = dev.pendingCommands.filter(c => now - c.timestamp < 300000);
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
    name: 'JARVIS MCP Relay Server',
    version: '2.1.0',
    protocol: 'HTTP Long-Polling',
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
      'GET  /api/warmup': 'Keep server awake / prevent cold start'
    }
  });
});

// ─── Start Server ───
app.listen(PORT, '0.0.0.0', () => {
  console.log(`╔══════════════════════════════════════════╗`);
  console.log(`║   JARVIS MCP Relay Server v2.1           ║`);
  console.log(`║   HTTP Long-Polling — Bulletproof Mode   ║`);
  console.log(`║   Port: ${PORT}                            ║`);
  console.log(`╚══════════════════════════════════════════╝`);
});
