#!/usr/bin/env node

/**
 * JARVIS MCP Server
 * 
 * Exposes JARVIS Android Agent tools via the Model Context Protocol.
 * Any MCP-compatible client (Claude Desktop, Cursor, Windsurf) can
 * control your phone through this server.
 * 
 * Architecture:
 *   MCP Client ↔ MCP Server ↔ WebSocket Relay ↔ JARVIS Android App
 * 
 * Usage:
 *   RELAY_URL=wss://your-relay.onrender.com node dist/index.js
 *   DEVICE_ID=your-device-id node dist/index.js  (optional, defaults to first connected device)
 */

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import WebSocket from "ws";
import { v4 as uuidv4 } from "uuid";

// ─── Config ───
const RELAY_URL = process.env.RELAY_URL || "ws://localhost:8080";
const DEVICE_ID = process.env.DEVICE_ID || "";
const TIMEOUT_MS = parseInt(process.env.TIMEOUT_MS || "30000");

// ─── Pending requests map (requestId → { resolve, reject, timeout }) ───
const pendingRequests = new Map<
  string,
  {
    resolve: (value: any) => void;
    reject: (reason: any) => void;
    timeout: ReturnType<typeof setTimeout>;
  }
>();

let ws: WebSocket | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

// ─── WebSocket connection to relay ───
function connectToRelay() {
  console.error(`[JARVIS MCP] Connecting to relay: ${RELAY_URL}`);

  ws = new WebSocket(RELAY_URL);

  ws.on("open", () => {
    console.error("[JARVIS MCP] Connected to relay");
  });

  ws.on("message", (data: WebSocket.Data) => {
    try {
      const msg = JSON.parse(data.toString());

      // Handle responses to our requests
      if (msg.requestId && pendingRequests.has(msg.requestId)) {
        const pending = pendingRequests.get(msg.requestId)!;
        clearTimeout(pending.timeout);
        pendingRequests.delete(msg.requestId);

        if (msg.type === "error") {
          pending.reject(new Error(msg.message || "Unknown error"));
        } else {
          pending.resolve(msg);
        }
        return;
      }

      // Handle push notifications (status updates from phone)
      if (msg.type === "status_update") {
        console.error(`[JARVIS MCP] Phone status: ${msg.status} — Step ${msg.step} — ${msg.action}`);
      }
    } catch (e) {
      console.error("[JARVIS MCP] Failed to parse relay message:", e);
    }
  });

  ws.on("close", () => {
    console.error("[JARVIS MCP] Disconnected from relay — reconnecting in 5s");
    ws = null;
    reconnectTimer = setTimeout(connectToRelay, 5000);
  });

  ws.on("error", (err: Error) => {
    console.error("[JARVIS MCP] WebSocket error:", err.message);
  });
}

// ─── Send command to phone via relay ───
function sendCommand(type: string, payload: Record<string, any> = {}): Promise<any> {
  return new Promise((resolve, reject) => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      reject(new Error("Not connected to relay. Is the relay server running and the phone connected?"));
      return;
    }

    const requestId = uuidv4();
    const message = {
      type,
      requestId,
      deviceId: DEVICE_ID || undefined,
      ...payload,
    };

    const timeout = setTimeout(() => {
      pendingRequests.delete(requestId);
      reject(new Error(`Request timed out after ${TIMEOUT_MS}ms`));
    }, TIMEOUT_MS);

    pendingRequests.set(requestId, { resolve, reject, timeout });
    ws.send(JSON.stringify(message));
  });
}

// ─── MCP Server Setup ───
const server = new Server(
  {
    name: "jarvis-agent",
    version: "1.0.0",
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
        name: "jarvis_execute",
        description:
          "Send a task to the JARVIS Android agent on your phone. " +
          "The agent will autonomously execute the task using screen control. " +
          "Examples: 'Open YouTube and search for cats', 'Send a WhatsApp message to Mom saying I'll be late', " +
          "'Take a screenshot and tell me what app is open', 'Open Settings and turn on battery saver'. " +
          "Returns the final status of the task execution.",
        inputSchema: {
          type: "object" as const,
          properties: {
            task: {
              type: "string",
              description: "The natural language task for JARVIS to execute on the phone",
            },
          },
          required: ["task"],
        },
      },
      {
        name: "jarvis_status",
        description:
          "Get the current status of the JARVIS agent on your phone. " +
          "Returns whether the agent is running, current step, and last action.",
        inputSchema: {
          type: "object" as const,
          properties: {},
        },
      },
      {
        name: "jarvis_kill",
        description:
          "Kill the currently running task on the JARVIS agent. " +
          "Use this if the agent is stuck or you want to stop execution.",
        inputSchema: {
          type: "object" as const,
          properties: {},
        },
      },
      {
        name: "jarvis_screenshot",
        description:
          "Capture a screenshot from the phone. Returns a base64-encoded JPEG image. " +
          "Use this to see what's currently on the phone screen.",
        inputSchema: {
          type: "object" as const,
          properties: {},
        },
      },
      {
        name: "jarvis_list_apps",
        description:
          "List all installed apps on the phone. Returns app names and package names.",
        inputSchema: {
          type: "object" as const,
          properties: {},
        },
      },
      {
        name: "jarvis_devices",
        description:
          "List all JARVIS devices currently connected to the relay server.",
        inputSchema: {
          type: "object" as const,
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
      case "jarvis_execute": {
        const task = (args as any)?.task;
        if (!task) {
          return {
            content: [{ type: "text", text: "Error: 'task' parameter is required" }],
            isError: true,
          };
        }

        const response = await sendCommand("execute", { task });
        const result = response as any;

        let text = `📱 Task: "${task}"\n`;
        text += `Status: ${result.status || "unknown"}\n`;
        if (result.step) text += `Steps completed: ${result.step}\n`;
        if (result.action) text += `Last action: ${result.action}\n`;
        if (result.reason) text += `Reason: ${result.reason}\n`;

        return {
          content: [{ type: "text", text }],
        };
      }

      case "jarvis_status": {
        const response = await sendCommand("status");
        const result = response as any;

        let text = `📱 JARVIS Agent Status\n`;
        text += `Running: ${result.isRunning ? "Yes" : "No"}\n`;
        text += `Status: ${result.status || "Idle"}\n`;
        text += `Step: ${result.step || 0}/50\n`;
        text += `Action: ${result.action || "—"}\n`;
        text += `Provider: ${result.provider || "unknown"}\n`;

        return {
          content: [{ type: "text", text }],
        };
      }

      case "jarvis_kill": {
        const response = await sendCommand("kill");
        const result = response as any;

        return {
          content: [{ type: "text", text: `📱 Task killed: ${result.message || "done"}` }],
        };
      }

      case "jarvis_screenshot": {
        const response = await sendCommand("screenshot");
        const result = response as any;

        if (result.base64) {
          return {
            content: [
              {
                type: "image",
                data: result.base64,
                mimeType: "image/jpeg",
              },
            ],
          };
        } else {
          return {
            content: [{ type: "text", text: "Failed to capture screenshot. Is the accessibility service active?" }],
            isError: true,
          };
        }
      }

      case "jarvis_list_apps": {
        const response = await sendCommand("list_apps");
        const result = response as any;

        if (result.apps && Array.isArray(result.apps)) {
          const appList = result.apps
            .slice(0, 50)
            .map((app: any) => `• ${app.name} (${app.packageName})`)
            .join("\n");
          return {
            content: [{ type: "text", text: `📱 Installed Apps (${result.apps.length} total):\n${appList}` }],
          };
        } else {
          return {
            content: [{ type: "text", text: "Failed to list apps." }],
            isError: true,
          };
        }
      }

      case "jarvis_devices": {
        const response = await sendCommand("list_devices");
        const result = response as any;

        if (result.devices && Array.isArray(result.devices)) {
          const deviceList = result.devices
            .map((d: any) => `• ${d.deviceId} — ${d.model || "unknown"} — ${d.connected ? "🟢 Online" : "🔴 Offline"}`)
            .join("\n");
          return {
            content: [{ type: "text", text: `📱 Connected Devices:\n${deviceList || "No devices connected"}` }],
          };
        } else {
          return {
            content: [{ type: "text", text: "No devices connected to relay." }],
          };
        }
      }

      default:
        return {
          content: [{ type: "text", text: `Unknown tool: ${name}` }],
          isError: true,
        };
    }
  } catch (error: any) {
    return {
      content: [{ type: "text", text: `Error: ${error.message}` }],
      isError: true,
    };
  }
});

// ─── Start ───
async function main() {
  console.error("[JARVIS MCP] Starting server...");

  // Connect to relay
  connectToRelay();

  // Start MCP server with stdio transport
  const transport = new StdioServerTransport();
  await server.connect(transport);

  console.error("[JARVIS MCP] Server running on stdio");
  console.error(`[JARVIS MCP] Relay: ${RELAY_URL}`);
  console.error(`[JARVIS MCP] Device ID: ${DEVICE_ID || "auto (first connected)"}`);
}

main().catch((error) => {
  console.error("[JARVIS MCP] Fatal error:", error);
  process.exit(1);
});
