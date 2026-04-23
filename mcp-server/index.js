#!/usr/bin/env node

/**
 * JARVIS MCP Server v4.0 — Fast Execution Architecture
 *
 * Key improvements:
 *   - Auto-capture: Every action returns screenshot + UI tree (eliminates jarvis_look round-trip)
 *   - jarvis_sequence: Execute multiple actions in one command
 *   - jarvis_open_chrome_url: Open URL in Chrome macro (runs entirely on phone)
 *   - Enhanced jarvis_type_text: Works with Chrome URL bar via clipboard paste
 *   - Fixed jarvis_open_url: Explicitly targets Chrome package
 *
 * MCP tools exposed:
 *   jarvis_tap            — Tap at coordinates (auto-captures screenshot)
 *   jarvis_swipe          — Swipe from one point to another (auto-captures)
 *   jarvis_long_press     — Long press at coordinates (auto-captures)
 *   jarvis_type_text      — Type text at coordinates (auto-captures, clipboard fallback)
 *   jarvis_press_back     — Press back button (auto-captures)
 *   jarvis_press_home     — Press home button (auto-captures)
 *   jarvis_press_recents  — Press recents button (auto-captures)
 *   jarvis_open_app       — Open an app by name (auto-captures)
 *   jarvis_open_url       — Open URL in Chrome browser (auto-captures)
 *   jarvis_open_chrome_url — MACRO: Open Chrome, type URL, load page (fastest way to navigate)
 *   jarvis_sequence       — Execute multiple actions in sequence (big speed boost)
 *   jarvis_screenshot     — Capture screenshot only
 *   jarvis_ui_tree        — Get current UI tree only
 *   jarvis_look           — Screenshot + UI tree (still useful for initial observation)
 *   jarvis_execute        — Delegate to local Gemini agent (legacy)
 *   jarvis_status         — Get current agent status
 *   jarvis_kill           — Kill running task
 *   jarvis_list_apps      — List installed apps
 *   jarvis_devices        — List connected devices
 */

const crypto = require('crypto');
const { Server } = require('@modelcontextprotocol/sdk/server/index.js');
const { StdioServerTransport } = require('@modelcontextprotocol/sdk/server/stdio.js');
const {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} = require('@modelcontextprotocol/sdk/types.js');

const RELAY_URL = process.env.RELAY_URL || 'https://j-a-r-v-i-s-ktlh.onrender.com';
const POLL_TIMEOUT_MS = 30000;
const COLD_START_RETRIES = 3;
const COLD_START_DELAY_MS = 5000;

// ─── Helper: HTTP request with timeout and retry ───
async function httpRequest(method, path, body = null, retries = COLD_START_RETRIES) {
  const url = `${RELAY_URL}${path}`;
  const options = {
    method,
    headers: { 'Content-Type': 'application/json' },
    signal: AbortSignal.timeout(POLL_TIMEOUT_MS + 5000),
  };

  if (body) {
    options.body = JSON.stringify(body);
  }

  let lastError = null;
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      const response = await fetch(url, options);
      if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error(`HTTP ${response.status}: ${text.slice(0, 200)}`);
      }
      return await response.json();
    } catch (err) {
      lastError = err;
      const isRetryable = err.name === 'AbortError' ||
                          err.name === 'TypeError' ||
                          err.message.includes('fetch failed') ||
                          err.message.includes('ECONNREFUSED') ||
                          err.message.includes('timeout') ||
                          err.message.includes('socket hang up');

      if (isRetryable && attempt < retries) {
        console.error(`[RETRY] Attempt ${attempt}/${retries} failed for ${method} ${path}: ${err.message}`);
        await new Promise(r => setTimeout(r, COLD_START_DELAY_MS * attempt));
        continue;
      }
      if (!isRetryable) throw err;
    }
  }
  throw lastError;
}

// ─── Helper: Find active device ───
async function findActiveDevice() {
  const devicesData = await httpRequest('GET', '/api/devices');
  const devices = devicesData.devices || [];
  return devices.find(d => d.connected) || null;
}

// ─── Helper: Send command and wait for response ───
async function sendCommand(type, params = {}) {
  const requestId = crypto.randomUUID();

  const activeDevice = await findActiveDevice();
  if (!activeDevice) {
    return {
      content: [{ type: 'text', text: 'No JARVIS device connected. Open the JARVIS app and enable MCP Relay in Settings.' }],
      isError: true,
    };
  }

  await httpRequest('POST', '/api/command', {
    requestId,
    type,
    deviceId: activeDevice.deviceId,
    ...params,
  });

  try {
    const result = await httpRequest('GET', `/api/response/${requestId}`);
    return { raw: result };
  } catch (err) {
    return {
      content: [{ type: 'text', text: `Timeout waiting for device response: ${err.message}` }],
      isError: true,
    };
  }
}

// ─── Helper: Format auto-capture response (screenshot + UI tree) ───
function formatCaptureResponse(result, actionLabel) {
  try {
    const parsed = result.raw || result;
    const contents = [];

    // Add action result text
    const actionInfo = { action: actionLabel, success: parsed.success !== false };
    if (parsed.type) actionInfo.type = parsed.type;
    if (parsed.url) actionInfo.url = parsed.url;
    if (parsed.app) actionInfo.app = parsed.app;
    if (parsed.text) actionInfo.text = parsed.text;
    if (parsed.actionCount) actionInfo.actionCount = parsed.actionCount;
    if (parsed.actions) actionInfo.actions = parsed.actions;

    // Add screenshot if available (auto-capture)
    if (parsed.base64 && parsed.base64.length > 100) {
      contents.push({
        type: 'image',
        data: parsed.base64,
        mimeType: parsed.mimeType || 'image/jpeg'
      });
    }

    // Add UI tree if available
    if (parsed.uiTree && parsed.uiTree.length > 10) {
      contents.push({
        type: 'text',
        text: `Action: ${JSON.stringify(actionInfo)}\n\nUI Tree:\n${parsed.uiTree}`
      });
    } else {
      contents.push({
        type: 'text',
        text: `Action: ${JSON.stringify(actionInfo, null, 2)}`
      });
    }

    return { content: contents };
  } catch (e) {
    // Fallback to raw text
    return {
      content: [{ type: 'text', text: JSON.stringify(result.raw || result, null, 2) }]
    };
  }
}

// ─── Helper: Format simple response (no screenshot) ───
function formatSimpleResponse(result) {
  const parsed = result.raw || result;
  return {
    content: [{ type: 'text', text: JSON.stringify(parsed, null, 2) }]
  };
}

// ─── Create MCP Server ───
const server = new Server(
  {
    name: 'jarvis-mcp-server',
    version: '4.0.0',
  },
  {
    capabilities: {
      tools: {},
    },
  }
);

// ─── List Tools ───
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: 'jarvis_look',
        description: 'Observe the current phone screen. Captures screenshot AND UI tree. Use this FIRST to understand what\'s on screen before taking any action. After actions, the screenshot is auto-captured so you don\'t need to call this again.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_tap',
        description: 'Tap the phone screen at specific coordinates. After tapping, automatically captures a screenshot + UI tree so you can see the result immediately without needing jarvis_look.',
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
        name: 'jarvis_swipe',
        description: 'Swipe on the phone screen from one point to another. Auto-captures screenshot after swiping.',
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
        name: 'jarvis_long_press',
        description: 'Long press at specific coordinates on the phone screen (600ms hold). Auto-captures screenshot.',
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
        name: 'jarvis_type_text',
        description: 'Type text into a field at specific coordinates. Taps the field first, then types using clipboard paste (works with Chrome URL bar and non-standard fields). Auto-captures screenshot after typing.',
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
        name: 'jarvis_press_back',
        description: 'Press the Android back button. Auto-captures screenshot.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_press_home',
        description: 'Press the Android home button. Auto-captures screenshot.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_press_recents',
        description: 'Press the Android recents/overview button. Auto-captures screenshot.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_open_app',
        description: 'Open an app on the phone by name (e.g., "Chrome", "YouTube") or package name. Auto-captures screenshot after app opens.',
        inputSchema: {
          type: 'object',
          properties: {
            app: { type: 'string', description: 'App name (e.g., "Chrome") or package name (e.g., "com.android.chrome")' },
          },
          required: ['app'],
        },
      },
      {
        name: 'jarvis_open_url',
        description: 'Open a URL in Chrome browser. Explicitly targets Chrome package to avoid opening wrong apps. Auto-captures screenshot after page loads.',
        inputSchema: {
          type: 'object',
          properties: {
            url: { type: 'string', description: 'URL to open (e.g., "https://aistudio.google.com")' },
          },
          required: ['url'],
        },
      },
      {
        name: 'jarvis_open_chrome_url',
        description: 'FAST MACRO: Opens Chrome, finds the URL bar, types the URL, and presses Enter — all executed on the phone in one command with zero round-trips. Use this instead of jarvis_open_url for the fastest navigation. Auto-captures screenshot when page loads.',
        inputSchema: {
          type: 'object',
          properties: {
            url: { type: 'string', description: 'Full URL to navigate to (e.g., "https://aistudio.google.com")' },
          },
          required: ['url'],
        },
      },
      {
        name: 'jarvis_sequence',
        description: 'Execute multiple actions in sequence with a single command. MUCH FASTER than individual commands because there are no round-trips between actions. Automatically captures screenshot + UI tree after all actions complete. Example: [{"action":"TAP","x":540,"y":200},{"action":"TYPE","x":540,"y":200,"text":"hello"},{"action":"TAP","x":540,"y":400}]',
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
                  x: { type: 'integer', description: 'X coordinate (for TAP, SWIPE, TYPE, LONG_PRESS)' },
                  y: { type: 'integer', description: 'Y coordinate (for TAP, SWIPE, TYPE, LONG_PRESS)' },
                  x2: { type: 'integer', description: 'End X coordinate (for SWIPE, SCROLL)' },
                  y2: { type: 'integer', description: 'End Y coordinate (for SWIPE, SCROLL)' },
                  text: { type: 'string', description: 'Text to type (for TYPE action)' },
                  app: { type: 'string', description: 'App name (for OPEN_APP action)' },
                  delay: { type: 'integer', description: 'Custom delay after this action in ms' },
                  duration: { type: 'integer', description: 'Gesture duration in ms (for SWIPE)' },
                },
                required: ['action'],
              },
            },
          },
          required: ['actions'],
        },
      },
      {
        name: 'jarvis_screenshot',
        description: 'Capture a screenshot from the phone. Returns a base64-encoded JPEG image. Prefer jarvis_look which also returns the UI tree.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_ui_tree',
        description: 'Get the current UI tree from the phone. Shows all visible elements with their coordinates, text, and properties.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_execute',
        description: 'Delegate a task to the local JARVIS agent on the phone (requires Gemini API key). Prefer using direct action tools instead.',
        inputSchema: {
          type: 'object',
          properties: {
            task: { type: 'string', description: 'The task for JARVIS to execute autonomously' },
          },
          required: ['task'],
        },
      },
      {
        name: 'jarvis_status',
        description: 'Get the current status of your JARVIS agent.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_kill',
        description: 'Kill the currently running task on JARVIS.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_list_apps',
        description: 'List all installed apps on the JARVIS device.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_devices',
        description: 'List all JARVIS devices connected to the relay server.',
        inputSchema: { type: 'object', properties: {} },
      },
    ],
  };
});

// ─── Handle Tool Calls ───
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case 'jarvis_tap': {
        const result = await sendCommand('tap', { x: args?.x, y: args?.y });
        if (result.isError) return result;
        return formatCaptureResponse(result, `tap(${args?.x}, ${args?.y})`);
      }

      case 'jarvis_swipe': {
        const result = await sendCommand('swipe', { x: args?.x, y: args?.y, x2: args?.x2, y2: args?.y2, duration: args?.duration });
        if (result.isError) return result;
        return formatCaptureResponse(result, `swipe(${args?.x},${args?.y} → ${args?.x2},${args?.y2})`);
      }

      case 'jarvis_long_press': {
        const result = await sendCommand('long_press', { x: args?.x, y: args?.y });
        if (result.isError) return result;
        return formatCaptureResponse(result, `long_press(${args?.x}, ${args?.y})`);
      }

      case 'jarvis_type_text': {
        const result = await sendCommand('type_text', { x: args?.x, y: args?.y, text: args?.text });
        if (result.isError) return result;
        return formatCaptureResponse(result, `type("${args?.text?.substring(0, 50)}")`);
      }

      case 'jarvis_press_back': {
        const result = await sendCommand('press_back');
        if (result.isError) return result;
        return formatCaptureResponse(result, 'press_back');
      }

      case 'jarvis_press_home': {
        const result = await sendCommand('press_home');
        if (result.isError) return result;
        return formatCaptureResponse(result, 'press_home');
      }

      case 'jarvis_press_recents': {
        const result = await sendCommand('press_recents');
        if (result.isError) return result;
        return formatCaptureResponse(result, 'press_recents');
      }

      case 'jarvis_open_app': {
        const result = await sendCommand('open_app', { app: args?.app });
        if (result.isError) return result;
        return formatCaptureResponse(result, `open_app(${args?.app})`);
      }

      case 'jarvis_open_url': {
        const result = await sendCommand('open_url', { url: args?.url });
        if (result.isError) return result;
        return formatCaptureResponse(result, `open_url(${args?.url})`);
      }

      case 'jarvis_open_chrome_url': {
        const result = await sendCommand('open_chrome_url', { url: args?.url });
        if (result.isError) return result;
        return formatCaptureResponse(result, `open_chrome_url(${args?.url})`);
      }

      case 'jarvis_sequence': {
        const result = await sendCommand('sequence', { actions: args?.actions });
        if (result.isError) return result;
        return formatCaptureResponse(result, `sequence(${args?.actions?.length} actions)`);
      }

      case 'jarvis_screenshot': {
        const result = await sendCommand('screenshot');
        if (result.isError) return result;
        const parsed = result.raw || {};
        if (parsed.base64) {
          return {
            content: [
              { type: 'image', data: parsed.base64, mimeType: parsed.mimeType || 'image/jpeg' },
              { type: 'text', text: `Screenshot from device: ${parsed.deviceId || 'unknown'}` },
            ],
          };
        }
        return formatSimpleResponse(result);
      }

      case 'jarvis_ui_tree': {
        const result = await sendCommand('ui_tree');
        if (result.isError) return result;
        return formatSimpleResponse(result);
      }

      case 'jarvis_look': {
        const result = await sendCommand('screenshot_and_ui');
        if (result.isError) return result;
        const parsed = result.raw || {};
        const contents = [];
        if (parsed.base64 && parsed.base64.length > 100) {
          contents.push({ type: 'image', data: parsed.base64, mimeType: parsed.mimeType || 'image/jpeg' });
        }
        if (parsed.uiTree && parsed.uiTree.length > 10) {
          contents.push({ type: 'text', text: `UI Tree:\n${parsed.uiTree}` });
        }
        if (contents.length > 0) return { content: contents };
        return formatSimpleResponse(result);
      }

      case 'jarvis_execute': {
        const task = args?.task;
        if (!task) {
          return { content: [{ type: 'text', text: 'Missing required parameter: task' }], isError: true };
        }
        const requestId = crypto.randomUUID();
        const activeDevice = await findActiveDevice();
        if (!activeDevice) {
          return { content: [{ type: 'text', text: 'No JARVIS device connected.' }], isError: true };
        }
        await httpRequest('POST', '/api/command', { requestId, type: 'execute', deviceId: activeDevice.deviceId, task });
        try {
          const ack = await httpRequest('GET', `/api/response/${requestId}`);
          return { content: [{ type: 'text', text: `Task started: ${JSON.stringify(ack)}` }] };
        } catch (err) {
          return { content: [{ type: 'text', text: `Task sent but no ack: ${err.message}` }] };
        }
      }

      case 'jarvis_status':
        return await sendCommand('status').then(r => r.isError ? r : formatSimpleResponse(r));

      case 'jarvis_kill':
        return await sendCommand('kill').then(r => r.isError ? r : formatSimpleResponse(r));

      case 'jarvis_list_apps':
        return await sendCommand('list_apps').then(r => r.isError ? r : formatSimpleResponse(r));

      case 'jarvis_devices': {
        const data = await httpRequest('GET', '/api/devices');
        const devices = data.devices || [];
        if (devices.length === 0) {
          return { content: [{ type: 'text', text: 'No JARVIS devices connected.' }] };
        }
        return {
          content: [{
            type: 'text',
            text: devices.map(d =>
              `${d.connected ? '[ONLINE]' : '[OFFLINE]'} ${d.deviceId}\n   Model: ${d.model}\n   Android: ${d.androidVersion}\n   Status: ${d.status ? d.status.status : 'Idle'}`
            ).join('\n\n'),
          }],
        };
      }

      default:
        return { content: [{ type: 'text', text: `Unknown tool: ${name}` }], isError: true };
    }
  } catch (error) {
    return { content: [{ type: 'text', text: `Error: ${error.message}` }], isError: true };
  }
});

// ─── Start ───
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('JARVIS MCP Server v4.0 running (Fast Execution Architecture)');
  console.error(`Relay: ${RELAY_URL}`);
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
