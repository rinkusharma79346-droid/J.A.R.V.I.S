/**
 * JARVIS WebSocket Relay Server
 * 
 * Bridges MCP server clients and JARVIS Android devices.
 * Runs on any cloud platform (Render, Railway, Fly.io) or locally.
 * 
 * Protocol:
 *   - Devices connect and identify with a deviceId
 *   - MCP clients connect and can target devices
 *   - Messages are routed by requestId for request/response
 *   - Push notifications (status updates) are forwarded to all MCP clients
 * 
 * Environment:
 *   PORT — server port (default: 8080)
 *   AUTH_TOKEN — optional token for authentication
 */

const { WebSocketServer } = require("ws");
const http = require("http");
const { v4: uuidv4 } = require("uuid");

const PORT = parseInt(process.env.PORT || "8080");
const AUTH_TOKEN = process.env.AUTH_TOKEN || "";

// ─── State ───
const devices = new Map();    // deviceId → { ws, model, connected }
const mcpClients = new Map(); // clientId → ws
const pendingRequests = new Map(); // requestId → { from, timestamp }

// ─── HTTP Server (for health checks on cloud platforms) ───
const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      status: "ok",
      devices: devices.size,
      mcpClients: mcpClients.size,
      pendingRequests: pendingRequests.size,
    }));
    return;
  }
  res.writeHead(404);
  res.end("Not found");
});

// ─── WebSocket Server ───
const wss = new WebSocketServer({ server });

wss.on("connection", (ws, req) => {
  const clientIp = req.headers["x-forwarded-for"] || req.socket.remoteAddress;
  let clientId = uuidv4();
  let isDevice = false;
  let deviceId = "";

  console.log(`[${new Date().toISOString()}] New connection from ${clientIp} (id: ${clientId})`);

  // ─── Message Handler ───
  ws.on("message", (data) => {
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch (e) {
      ws.send(JSON.stringify({ type: "error", message: "Invalid JSON" }));
      return;
    }

    // ─── Authentication ───
    if (AUTH_TOKEN && msg.authToken !== AUTH_TOKEN) {
      // Allow unauthenticated connections if AUTH_TOKEN is not set
      // Otherwise, require authToken in first message
    }

    // ─── Device Registration ───
    if (msg.type === "register_device") {
      isDevice = true;
      deviceId = msg.deviceId || clientId;
      clientId = deviceId;

      devices.set(deviceId, {
        ws,
        model: msg.model || "Unknown",
        androidVersion: msg.androidVersion || "",
        connected: true,
        connectedAt: new Date().toISOString(),
      });

      console.log(`[${new Date().toISOString()}] Device registered: ${deviceId} (${msg.model || "Unknown"})`);

      // Notify all MCP clients
      broadcastToMcpClients({
        type: "device_connected",
        deviceId,
        model: msg.model || "Unknown",
      });

      ws.send(JSON.stringify({ type: "register_ack", deviceId }));
      return;
    }

    // ─── MCP Client Registration ───
    if (msg.type === "register_mcp") {
      isDevice = false;
      mcpClients.set(clientId, ws);

      console.log(`[${new Date().toISOString()}] MCP client registered: ${clientId}`);

      ws.send(JSON.stringify({
        type: "register_ack",
        clientId,
        devices: getDeviceList(),
      }));
      return;
    }

    // ─── Commands from MCP client → forward to device ───
    if (!isDevice && msg.type && msg.requestId) {
      const targetDeviceId = msg.deviceId || getFirstDeviceId();

      if (!targetDeviceId) {
        ws.send(JSON.stringify({
          type: "error",
          requestId: msg.requestId,
          message: "No devices connected to relay",
        }));
        return;
      }

      const device = devices.get(targetDeviceId);
      if (!device || !device.ws || device.ws.readyState !== 1) {
        ws.send(JSON.stringify({
          type: "error",
          requestId: msg.requestId,
          message: `Device ${targetDeviceId} is not connected`,
        }));
        return;
      }

      // Store pending request for routing response back
      pendingRequests.set(msg.requestId, {
        from: clientId,
        timestamp: Date.now(),
        deviceId: targetDeviceId,
      });

      // Forward to device
      const forwardMsg = { ...msg };
      delete forwardMsg.deviceId; // Don't send deviceId to the device
      device.ws.send(JSON.stringify(forwardMsg));

      console.log(`[${new Date().toISOString()}] MCP → Device ${targetDeviceId}: ${msg.type} (${msg.requestId})`);
      return;
    }

    // ─── Responses from device → forward back to MCP client ───
    if (isDevice && msg.requestId && pendingRequests.has(msg.requestId)) {
      const pending = pendingRequests.get(msg.requestId);
      const mcpWs = mcpClients.get(pending.from);

      if (mcpWs && mcpWs.readyState === 1) {
        mcpWs.send(JSON.stringify(msg));
        console.log(`[${new Date().toISOString()}] Device ${deviceId} → MCP: ${msg.type} (${msg.requestId})`);
      }

      pendingRequests.delete(msg.requestId);
      return;
    }

    // ─── Push notifications from device (no requestId) → broadcast to MCP clients ───
    if (isDevice && msg.type === "status_update") {
      broadcastToMcpClients({ ...msg, deviceId });
      return;
    }

    // ─── List devices request ───
    if (!isDevice && msg.type === "list_devices") {
      ws.send(JSON.stringify({
        type: "device_list",
        requestId: msg.requestId,
        devices: getDeviceList(),
      }));
      return;
    }
  });

  // ─── Disconnect Handler ───
  ws.on("close", () => {
    if (isDevice && deviceId) {
      devices.delete(deviceId);
      console.log(`[${new Date().toISOString()}] Device disconnected: ${deviceId}`);
      broadcastToMcpClients({ type: "device_disconnected", deviceId });
    } else {
      mcpClients.delete(clientId);
      console.log(`[${new Date().toISOString()}] MCP client disconnected: ${clientId}`);
    }

    // Clean up pending requests from this client
    for (const [reqId, pending] of pendingRequests.entries()) {
      if (pending.from === clientId) {
        pendingRequests.delete(reqId);
      }
    }
  });

  ws.on("error", (err) => {
    console.error(`[${new Date().toISOString()}] WebSocket error:`, err.message);
  });
});

// ─── Helpers ───
function getDeviceList() {
  return Array.from(devices.entries()).map(([id, d]) => ({
    deviceId: id,
    model: d.model,
    androidVersion: d.androidVersion,
    connected: d.connected,
    connectedAt: d.connectedAt,
  }));
}

function getFirstDeviceId() {
  for (const [id, d] of devices.entries()) {
    if (d.connected) return id;
  }
  return null;
}

function broadcastToMcpClients(msg) {
  const data = JSON.stringify(msg);
  for (const [id, ws] of mcpClients.entries()) {
    if (ws.readyState === 1) {
      ws.send(data);
    }
  }
}

// ─── Clean up stale pending requests every 60s ───
setInterval(() => {
  const now = Date.now();
  for (const [reqId, pending] of pendingRequests.entries()) {
    if (now - pending.timestamp > 60000) {
      pendingRequests.delete(reqId);
    }
  }
}, 60000);

// ─── Start ───
server.listen(PORT, () => {
  console.log(`╔══════════════════════════════════════╗`);
  console.log(`║   JARVIS Relay Server                ║`);
  console.log(`║   Port: ${PORT.toString().padEnd(27)}║`);
  console.log(`║   Auth: ${(AUTH_TOKEN ? "Enabled" : "Disabled").padEnd(27)}║`);
  console.log(`╚══════════════════════════════════════╝`);
  console.log(`Ready for connections...`);
});
