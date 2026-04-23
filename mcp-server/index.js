#!/usr/bin/env node

/**
 * JARVIS MCP Server v2.1 — HTTP Long-Polling
 *
 * MCP tools exposed:
 *   jarvis_execute    — Send a task to JARVIS agent
 *   jarvis_status     — Get current agent status
 *   jarvis_kill       — Kill running task
 *   jarvis_screenshot — Capture current screen
 *   jarvis_list_apps  — List installed apps on device
 *   jarvis_devices    — List connected devices
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
    signal: AbortSignal.timeout(POLL_TIMEOUT_MS + 5000), // 35s total timeout
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
      // If it's a timeout or network error, retry (likely Render cold start)
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

      if (!isRetryable) {
        throw err; // Don't retry non-network errors
      }
    }
  }
  throw lastError;
}

// ─── Helper: Find active device ───
async function findActiveDevice() {
  const devicesData = await httpRequest('GET', '/api/devices');
  const devices = devicesData.devices || [];
  const activeDevice = devices.find(d => d.connected);
  return activeDevice || null;
}

// ─── Helper: Send command and wait for response ───
async function sendCommand(type, params = {}) {
  const requestId = crypto.randomUUID();

  // Find available device
  const activeDevice = await findActiveDevice();
  if (!activeDevice) {
    return {
      content: [{ type: 'text', text: 'No JARVIS device connected. Open the JARVIS app and enable MCP Relay in Settings.' }],
      isError: true,
    };
  }

  // Send command to device
  await httpRequest('POST', '/api/command', {
    requestId,
    type,
    deviceId: activeDevice.deviceId,
    ...params,
  });

  // Poll for response (long-poll, up to 25s from server + retry)
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

// ─── Helper: Send execute command and monitor progress ───
async function sendExecuteCommand(task) {
  const requestId = crypto.randomUUID();

  // Find available device
  const activeDevice = await findActiveDevice();
  if (!activeDevice) {
    return {
      content: [{ type: 'text', text: 'No JARVIS device connected. Open the JARVIS app and enable MCP Relay in Settings.' }],
      isError: true,
    };
  }

  // Send execute command
  await httpRequest('POST', '/api/command', {
    requestId,
    type: 'execute',
    deviceId: activeDevice.deviceId,
    task,
  });

  // Wait for ack (long-poll)
  let ack;
  try {
    ack = await httpRequest('GET', `/api/response/${requestId}`);
  } catch (err) {
    return {
      content: [{ type: 'text', text: `Task sent but no ack received: ${err.message}` }],
    };
  }

  // Poll for status updates and completion
  let finalResult = null;
  const maxWait = 120000; // 2 minutes max
  const startTime = Date.now();

  while (Date.now() - startTime < maxWait) {
    await new Promise(r => setTimeout(r, 3000));

    try {
      const statusData = await httpRequest('GET', `/api/device/${activeDevice.deviceId}/status`, null, 1);
      const status = statusData.status;

      if (status && !status.isRunning) {
        finalResult = status;
        break;
      }
    } catch (err) {
      // Non-fatal — device status poll failed, keep trying
    }
  }

  if (finalResult) {
    return {
      content: [{
        type: 'text',
        text: `Task Complete!\nStatus: ${finalResult.status}\nSteps: ${finalResult.step}\nLast Action: ${finalResult.action}\nDevice: ${activeDevice.deviceId}`
      }],
    };
  } else {
    return {
      content: [{
        type: 'text',
        text: `Task is still running on device. Ack: ${ack.type || 'started'}\nDevice: ${activeDevice.deviceId}\nTask: ${task}`
      }],
    };
  }
}

// ─── Create MCP Server ───
const server = new Server(
  {
    name: 'jarvis-mcp-server',
    version: '2.1.0',
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
        name: 'jarvis_execute',
        description: 'Execute a task on your JARVIS Android agent. The agent will autonomously perform the task on your phone using accessibility gestures. Returns when the task completes or times out.',
        inputSchema: {
          type: 'object',
          properties: {
            task: {
              type: 'string',
              description: 'The task for JARVIS to execute (e.g., "Open YouTube and search for cooking tutorials", "Send a message to Mom saying I\'ll be late", "Take a screenshot")',
            },
          },
          required: ['task'],
        },
      },
      {
        name: 'jarvis_status',
        description: 'Get the current status of your JARVIS agent — whether it\'s running a task, what step it\'s on, the current action, and which AI provider is being used.',
        inputSchema: {
          type: 'object',
          properties: {},
        },
      },
      {
        name: 'jarvis_kill',
        description: 'Kill the currently running task on JARVIS. Use this to stop a task that is stuck or taking too long.',
        inputSchema: {
          type: 'object',
          properties: {},
        },
      },
      {
        name: 'jarvis_screenshot',
        description: 'Capture a screenshot from the JARVIS device. Returns a base64-encoded JPEG image of the current screen.',
        inputSchema: {
          type: 'object',
          properties: {},
        },
      },
      {
        name: 'jarvis_list_apps',
        description: 'List all installed apps on the JARVIS device. Returns app names and package names.',
        inputSchema: {
          type: 'object',
          properties: {},
        },
      },
      {
        name: 'jarvis_devices',
        description: 'List all JARVIS devices connected to the relay server.',
        inputSchema: {
          type: 'object',
          properties: {},
        },
      },
    ],
  };
});

// ─── Handle Tool Calls ───
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    switch (name) {
      case 'jarvis_execute': {
        const task = args?.task;
        if (!task) {
          return {
            content: [{ type: 'text', text: 'Missing required parameter: task' }],
            isError: true,
          };
        }
        return await sendExecuteCommand(task);
      }

      case 'jarvis_status': {
        return await sendCommand('status');
      }

      case 'jarvis_kill': {
        return await sendCommand('kill');
      }

      case 'jarvis_screenshot': {
        const result = await sendCommand('screenshot');
        // If response has base64 image, format it properly
        try {
          const parsed = JSON.parse(result.content[0].text);
          if (parsed.base64) {
            return {
              content: [
                {
                  type: 'image',
                  data: parsed.base64,
                  mimeType: parsed.mimeType || 'image/jpeg',
                },
                {
                  type: 'text',
                  text: `Screenshot captured from device: ${parsed.deviceId || 'unknown'}`,
                },
              ],
            };
          }
        } catch (e) {
          // Not JSON or no base64, return as-is
        }
        return result;
      }

      case 'jarvis_list_apps': {
        return await sendCommand('list_apps');
      }

      case 'jarvis_devices': {
        const data = await httpRequest('GET', '/api/devices');
        const devices = data.devices || [];
        if (devices.length === 0) {
          return {
            content: [{ type: 'text', text: 'No JARVIS devices connected. Open the JARVIS app and enable MCP Relay in Settings.' }],
          };
        }
        return {
          content: [{
            type: 'text',
            text: devices.map(d =>
              `${d.connected ? '[ONLINE]' : '[OFFLINE]'} ${d.deviceId}\n   Model: ${d.model}\n   Android: ${d.androidVersion}\n   Last Seen: ${new Date(d.lastSeen).toLocaleTimeString()}\n   Status: ${d.status ? d.status.status : 'Idle'}`
            ).join('\n\n'),
          }],
        };
      }

      default:
        return {
          content: [{ type: 'text', text: `Unknown tool: ${name}` }],
          isError: true,
        };
    }
  } catch (error) {
    return {
      content: [{ type: 'text', text: `Error: ${error.message}` }],
      isError: true,
    };
  }
});

// ─── Start ───
async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error('JARVIS MCP Server v2.1 running (HTTP Long-Polling)');
  console.error(`Relay: ${RELAY_URL}`);
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
