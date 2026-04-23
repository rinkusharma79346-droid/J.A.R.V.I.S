#!/usr/bin/env node

/**
 * JARVIS MCP Server v3.0 — Direct Action Control
 *
 * Z AI (GLM 5.1) is the brain. Phone just executes commands.
 * No local Gemini API needed — the AI controlling via MCP makes all decisions.
 *
 * MCP tools exposed:
 *   jarvis_tap            — Tap at coordinates
 *   jarvis_swipe          — Swipe from one point to another
 *   jarvis_long_press     — Long press at coordinates
 *   jarvis_type_text      — Type text at coordinates
 *   jarvis_press_back     — Press back button
 *   jarvis_press_home     — Press home button
 *   jarvis_press_recents  — Press recents button
 *   jarvis_open_app       — Open an app by name
 *   jarvis_open_url       — Open URL in browser
 *   jarvis_screenshot     — Capture screenshot
 *   jarvis_ui_tree        — Get current UI tree
 *   jarvis_look           — Screenshot + UI tree combined (most useful for AI)
 *   jarvis_execute        — Delegate to local Gemini agent (legacy, needs API key)
 *   jarvis_status         — Get current agent status
 *   jarvis_kill           — Kill running task
 *   jarvis_list_apps      — List installed apps
 *   jarvis_devices        — List connected devices
 *
 * Usage in Claude Desktop / Cursor:
 *   {
 *     "mcpServers": {
 *       "jarvis": {
 *         "command": "npx",
 *         "args": ["-y", "jarvis-mcp-server"],
 *         "env": {
 *           "RELAY_URL": "https://j-a-r-v-i-s-ktlh.onrender.com"
 *         }
 *       }
 *     }
 *   }
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
    return {
      content: [{ type: 'text', text: JSON.stringify(result, null, 2) }],
    };
  } catch (err) {
    return {
      content: [{ type: 'text', text: `Timeout waiting for device response: ${err.message}` }],
      isError: true,
    };
  }
}

// ─── Create MCP Server ───
const server = new Server(
  {
    name: 'jarvis-mcp-server',
    version: '3.0.0',
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
        name: 'jarvis_tap',
        description: 'Tap the phone screen at specific coordinates. Use jarvis_look first to see the screen and find the right coordinates.',
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
        description: 'Swipe on the phone screen from one point to another. Use for scrolling or swiping.',
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
        description: 'Long press at specific coordinates on the phone screen (600ms hold).',
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
        description: 'Type text into a field at specific coordinates. Taps the field first, then types the text.',
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
        description: 'Press the Android back button.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_press_home',
        description: 'Press the Android home button.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_press_recents',
        description: 'Press the Android recents/overview button.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_open_app',
        description: 'Open an app on the phone by name (e.g., "Chrome", "YouTube") or package name.',
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
        description: 'Open a URL in the phone browser. Useful for navigating to websites.',
        inputSchema: {
          type: 'object',
          properties: {
            url: { type: 'string', description: 'URL to open (e.g., "https://aistudio.google.com")' },
          },
          required: ['url'],
        },
      },
      {
        name: 'jarvis_screenshot',
        description: 'Capture a screenshot from the phone. Returns a base64-encoded JPEG image.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_ui_tree',
        description: 'Get the current UI tree from the phone. Shows all visible elements with their coordinates, text, and properties. Use this to find buttons, fields, and other interactive elements.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_look',
        description: 'Capture screenshot AND UI tree from the phone in one call. This is the most useful tool for understanding what is on the screen. Returns both the image and the structured UI element data with coordinates.',
        inputSchema: { type: 'object', properties: {} },
      },
      {
        name: 'jarvis_execute',
        description: 'Delegate a task to the local JARVIS agent on the phone. The phone uses its own Gemini API to execute the task autonomously. NOTE: Requires a working Gemini API key on the phone. Prefer using direct action tools (tap, swipe, type) instead.',
        inputSchema: {
          type: 'object',
          properties: {
            task: {
              type: 'string',
              description: 'The task for JARVIS to execute autonomously',
            },
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
      case 'jarvis_tap':
        return await sendCommand('tap', { x: args?.x, y: args?.y });

      case 'jarvis_swipe':
        return await sendCommand('swipe', { x: args?.x, y: args?.y, x2: args?.x2, y2: args?.y2, duration: args?.duration });

      case 'jarvis_long_press':
        return await sendCommand('long_press', { x: args?.x, y: args?.y });

      case 'jarvis_type_text':
        return await sendCommand('type_text', { x: args?.x, y: args?.y, text: args?.text });

      case 'jarvis_press_back':
        return await sendCommand('press_back');

      case 'jarvis_press_home':
        return await sendCommand('press_home');

      case 'jarvis_press_recents':
        return await sendCommand('press_recents');

      case 'jarvis_open_app':
        return await sendCommand('open_app', { app: args?.app });

      case 'jarvis_open_url':
        return await sendCommand('open_url', { url: args?.url });

      case 'jarvis_screenshot': {
        const result = await sendCommand('screenshot');
        try {
          const parsed = JSON.parse(result.content[0].text);
          if (parsed.base64) {
            return {
              content: [
                { type: 'image', data: parsed.base64, mimeType: parsed.mimeType || 'image/jpeg' },
                { type: 'text', text: `Screenshot from device: ${parsed.deviceId || 'unknown'}` },
              ],
            };
          }
        } catch (e) {}
        return result;
      }

      case 'jarvis_ui_tree':
        return await sendCommand('ui_tree');

      case 'jarvis_look': {
        const result = await sendCommand('screenshot_and_ui');
        try {
          const parsed = JSON.parse(result.content[0].text);
          const contents = [];
          if (parsed.base64) {
            contents.push({ type: 'image', data: parsed.base64, mimeType: parsed.mimeType || 'image/jpeg' });
          }
          if (parsed.uiTree) {
            contents.push({ type: 'text', text: `UI Tree:\n${parsed.uiTree}` });
          }
          if (contents.length > 0) return { content: contents };
        } catch (e) {}
        return result;
      }

      case 'jarvis_execute': {
        const task = args?.task;
        if (!task) {
          return { content: [{ type: 'text', text: 'Missing required parameter: task' }], isError: true };
        }
        // Legacy: delegates to phone's local Gemini agent
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
        return await sendCommand('status');

      case 'jarvis_kill':
        return await sendCommand('kill');

      case 'jarvis_list_apps':
        return await sendCommand('list_apps');

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
  console.error('JARVIS MCP Server v3.0 running (Direct Action Control)');
  console.error(`Relay: ${RELAY_URL}`);
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
