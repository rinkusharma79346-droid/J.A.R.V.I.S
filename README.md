<div align="center">

# V.A.Y.U Agent

**Versatile Agent for Your Universe**

**Autonomous Android Neural Engine**

<img src="https://img.shields.io/badge/Android-30+-green?logo=android" alt="Min SDK" />
<img src="https://img.shields.io/badge/Kotlin-1.9.22-purple?logo=kotlin" alt="Kotlin" />
<img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License" />
<img src="https://img.shields.io/badge/MCP-Protocol-orange" alt="MCP" />
<img src="https://img.shields.io/badge/Version-9.0.0-orange" alt="Version" />

<i>An AI agent that controls your Android phone like a human — taps, swipes, types, and navigates autonomously using vision models. Now with MCP remote control from Claude Desktop.</i>

<br/><br/>

**How it works:** Give V.A.Y.U a task like *"Find the best Italian restaurant in Manhattan and save the address to my notes"* — it reads the screen, thinks, and acts. All by itself.

<br/>

<img src="https://img.shields.io/badge/claude--desktop-MCP%20Ready-orange?logo=anthropic" alt="Claude Desktop" />
<img src="https://img.shields.io/badge/cursor-MCP%20Ready-blue?logo=cursor" alt="Cursor" />
<img src="https://img.shields.io/badge/gemini-2.0-flash-blue?logo=google" alt="Gemini" />
<img src="https://img.shields.io/badge/openai-gpt--4o-green?logo=openai" alt="OpenAI" />

<br/><br/>

<a href="https://github.com/rinkusharma79346-droid/J.A.R.V.I.S/stargazers">
  <img src="https://reporoster.com/stars/dark/rinkusharma79346-droid/J.A.R.V.I.S" alt="Stargazers" />
</a>

</div>

---

## Why V.A.Y.U?

**Most AI agents live in chat windows.** V.A.Y.U lives on your phone screen.

Instead of just *talking* about tasks, V.A.Y.U actually *does* them:

- "Open WhatsApp and send 'I'll be there in 5 min' to Mom" — **done**
- "Go to YouTube, search for 'Kotlin coroutines tutorial', and watch the first result" — **done**
- "Open Chrome, go to amazon.com, and add wireless earbuds to my cart" — **done**
- "Take a screenshot and send it to my email" — **done**

It sees your screen through accessibility services, uses AI vision to understand what's happening, and performs gestures just like a human finger.

**Plus:** Control your phone remotely from Claude Desktop or Cursor IDE using the Model Context Protocol (MCP). An AI on your computer can now see and control your phone.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Build the Android App](#build-the-android-app)
  - [Configure API Keys](#configure-api-keys)
- [MCP Remote Control](#mcp-remote-control)
  - [Setup with Claude Desktop](#setup-with-claude-desktop)
  - [Setup with Cursor / Windsurf](#setup-with-cursor--windsurf)
  - [Deploy the Relay Server](#deploy-the-relay-server)
- [How It Works](#how-it-works)
- [Supported Actions](#supported-actions)
- [Web Dashboard](#web-dashboard)
- [Configuration](#configuration)
- [CI/CD](#cicd)
- [Tech Stack](#tech-stack)
- [License](#license)

---

## Features

- **Autonomous Screen Control** — Uses Android Accessibility Service to perform taps, swipes, long presses, typing, and navigation actions just like a human
- **Multi-Model AI Vision** — Supports Google Gemini, OpenAI (GPT-4o), NVIDIA NIM, and any OpenAI-compatible custom endpoint
- **ReAct Reasoning Loop** — Think → Act → Observe cycle that analyzes screenshots, understands UI context, and adapts its strategy
- **MCP Remote Control** — External AI clients (Claude Desktop, Cursor, Claude.ai) can control the phone via the Model Context Protocol
- **Smart Stuck Detection** — Automatically detects when the agent is stuck in a loop and presses BACK to recover
- **HUD Overlay** — Floating status overlay shows real-time task progress during execution
- **Clipboard Type Injection** — Smart typing that works in both native Android apps and WebView/Chrome fields
- **Sequence & Macro Actions** — Execute multi-step sequences for complex workflows (open Chrome, navigate URL, type, etc.)
- **Relay Server** — Long-polling relay bridges the phone with remote AI clients over the internet

---

## Architecture

```
┌───────────────────────┐       HTTP Long-Polling       ┌──────────────────────┐
│   V.A.Y.U Android App   │ ◄──────────────────────────► │   Relay Server        │
│   (Kotlin)             │   /api/poll, /register       │   (vayu-relay/)     │
│                        │   /api/response              │   Express + MCP SDK   │
│   - Accessibility Svc  │                              │                      │
│   - HUD Overlay        │                              │   - SSE /sse          │
│   - ReAct Loop         │                              │   - Streamable /mcp   │
│   - Screenshot Capture │                              └──────────┬───────────┘
└───────────────────────┘                                         │
                                                                     │
                                                               MCP Protocol
                                                                     │
                                                    ┌────────────────┴────────────────┐
                                                    │                                  │
                                               ┌────┴────┐                     ┌──────┴──────┐
                                               │ mcp-    │                     │ Claude.ai   │
                                               │ server/ │                     │ (direct)    │
                                               │ (JS v4) │                     └─────────────┘
                                               └─────────┘
```

### Components

| Component | Language | Description |
|-----------|----------|-------------|
| `app/` | Kotlin | Android app with Accessibility Service, AI providers, HUD overlay |
| `vayu-relay/` | JavaScript | Primary relay server with HTTP polling + MCP SSE/Streamable HTTP |
| `mcp-server/` | JavaScript | Local MCP server for Claude Desktop / Cursor / Windsurf (19 tools) |
| `src/` | TypeScript/React | Web dashboard UI (demo mockup with glass morphism design) |

---

## Getting Started

### Prerequisites

- **Android Studio** (Arctic Fox or newer) or CLI with Gradle 8.5+
- **Android device** running API 30+ (Android 11+) — accessibility service requires real devices
- **API Key** from one of the supported providers:
  - Google Gemini (starts with `AIza...`)
  - OpenAI (starts with `sk-...`)
  - NVIDIA NIM (starts with `nvapi-...`)

### Build the Android App

1. **Clone the repository:**
   ```bash
   git clone https://github.com/rinkusharma79346-droid/J.A.R.V.I.S.git
   cd J.A.R.V.I.S
   ```

2. **Create `local.properties`** (or use environment variables):
   ```properties
   sdk.dir=/path/to/android/sdk
   GEMINI_API_KEY=your_gemini_api_key_here
   ```

3. **Build with Gradle:**
   ```bash
   chmod +x gradlew
   ./gradlew assembleDebug
   ```

4. **Install on device:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Configure API Keys

You can set API keys in three ways (in order of priority):

1. **App Settings UI** — Open V.A.Y.U → Settings → Select provider → Enter API key
2. **`local.properties`** — Add `GEMINI_API_KEY=your_key` for build-time injection
3. **Environment Variable** — Set `GEMINI_API_KEY` in your shell or CI environment

> **Security Note:** Never commit API keys to source control. Use environment variables or secure secret management in production.

---

## MCP Remote Control

V.A.Y.U supports the [Model Context Protocol](https://modelcontextprotocol.io/) for remote control from AI assistants.

### Setup with Claude Desktop

1. **Deploy the relay server** (see [Deploy the Relay Server](#deploy-the-relay-server))

2. **Install the MCP server locally:**
   ```bash
   cd mcp-server
   npm install
   ```

3. **Add to Claude Desktop config** (`claude_desktop_config.json`):
   ```json
   {
     "mcpServers": {
       "vayu": {
         "command": "node",
         "args": ["/path/to/J.A.R.V.I.S/mcp-server/index.js"]
       }
     }
   }
   ```

### Setup with Cursor / Windsurf

Add the same MCP server config in your IDE's MCP settings. The local MCP server connects to the relay server which communicates with the phone.

### Deploy the Relay Server

The relay server bridges between MCP clients and the Android device.

```bash
cd vayu-relay
npm install
node server.js
```

**Deploy to Render (free tier):**
```bash
# Already configured via render.yaml
# Just connect the repo to Render and it auto-deploys
```

**Environment Variables for Relay:**
```env
PORT=3000
# Optional: Add RELAY_SECRET for authentication
```

---

## How It Works

### Local ReAct Loop (On-Device)

```
User provides task
        │
        ▼
┌───────────────┐     ┌───────────────┐     ┌───────────────┐
│  CAPTURE      │────►│  THINK (AI)   │────►│  ACT          │
│  Screenshot   │     │  Analyze UI   │     │  Tap/Swipe/   │
│  + UI Tree    │     │  Decide next  │     │  Type/Nav     │
└───────────────┘     └───────────────┘     └───────┬───────┘
        ▲                                          │
        └──────────────────────────────────────────┘
                     Repeat until DONE/FAIL
```

1. **Capture** — Takes a screenshot and parses the UI accessibility tree
2. **Think** — Sends screenshot + UI tree + action history to the AI vision model
3. **Act** — Executes the AI's decision (tap, swipe, type, open app, etc.)
4. **Observe** — Waits for the screen to update, checks for stuck state, loops

### MCP Remote Control (External AI)

External AI clients send commands via MCP tools. The relay server forwards them to the phone, which executes them and returns screenshots + UI trees back.

---

## Supported Actions

| Action | Parameters | Description |
|--------|-----------|-------------|
| `TAP` | `x`, `y` | Tap at screen coordinates |
| `LONG_PRESS` | `x`, `y` | Long press (600ms) at coordinates |
| `SWIPE` | `x`, `y`, `x2`, `y2` | Swipe from start to end point |
| `SCROLL` | `x`, `y`, `x2`, `y2` | Scroll gesture (longer duration) |
| `TYPE` | `x`, `y`, `text` | Focus field at (x,y), then type text |
| `OPEN_APP` | `app` | Open app by name or package name |
| `PRESS_BACK` | — | Go back |
| `PRESS_HOME` | — | Go to home screen |
| `PRESS_RECENTS` | — | Open recent apps |
| `DONE` | `reason` | Signal task completion |
| `FAIL` | `reason` | Signal task failure |

### MCP Tools (19 available)

`vayu_tap`, `vayu_swipe`, `vayu_long_press`, `vayu_type`, `vayu_scroll`, `vayu_press_back`, `vayu_press_home`, `vayu_press_recents`, `vayu_open_app`, `vayu_screenshot`, `vayu_get_ui_tree`, `vayu_sequence`, `vayu_open_chrome_url`, `vayu_status`, `vayu_execute`, `vayu_kill`, `vayu_devices`, `vayu_list_apps`, and more.

---

## Web Dashboard

The `src/` directory contains a React + Vite + Tailwind CSS dashboard with a glass morphism UI design.

```bash
npm install
npm run dev
```

> **Note:** The dashboard currently displays demo/mock data. It serves as a UI reference and design prototype for a future real-time monitoring interface.

---

## Configuration

### App Settings (on device)

| Setting | Description | Default |
|---------|-------------|---------|
| API Provider | Gemini / OpenAI / NVIDIA / Custom | Gemini |
| API Key | Your provider's API key | — |
| Model | AI model to use | gemini-2.0-flash |
| Max Steps | Maximum ReAct loop iterations (10-100) | 50 |
| Action Delay | Delay between actions in ms (200-2000) | 800ms |
| Relay URL | URL of the MCP relay server | https://j-a-r-v-i-s-ktlh.onrender.com |

### Available Models

- **Gemini:** gemini-2.0-flash, gemini-2.0-flash-lite, gemini-1.5-pro, gemini-1.5-flash
- **OpenAI:** gpt-4o, gpt-4o-mini, gpt-4-turbo, gpt-4.1, gpt-4.1-mini, gpt-4.1-nano
- **NVIDIA:** nvidia/llama-3.1-nemotron-70b-instruct, meta/llama-3.1-405b-instruct, google/gemma-2-27b-it, mistralai/mixtral-8x22b-instruct-v0.1
- **Custom:** Any OpenAI-compatible endpoint

---

## CI/CD

### Codemagic (Android APK Builds)

The project includes `codemagic.yaml` for automated APK builds. Set the `GEMINI_API_KEY` variable in your Codemagic project settings (not in the YAML file).

### Render (Relay Server)

The `render.yaml` file configures the relay server for one-click deployment on Render's free tier.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Android App** | Kotlin, Android SDK 34, Accessibility Service, OkHttp, Coroutines |
| **AI Providers** | Google Gemini, OpenAI GPT-4o, NVIDIA NIM |
| **MCP Server** | @modelcontextprotocol/sdk (Node.js) |
| **Relay Server** | Express.js, MCP SSE, MCP Streamable HTTP |
| **Web Dashboard** | React 19, Vite 6, Tailwind CSS 4, Framer Motion |
| **Build** | Gradle 8.5, AGP 8.2.2, ProGuard |
| **CI/CD** | Codemagic, Render |

---

## Star History

<a href="https://star-history.com/#rinkusharma79346-droid/J.A.R.V.I.S&Date">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=rinkusharma79346-droid/J.A.R.V.I.S&type=Date&theme=dark" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=rinkusharma79346-droid/J.A.R.V.I.S&type=Date" />
   <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=rinkusharma79346-droid/J.A.R.V.I.S&type=Date" />
 </picture>
</a>

---

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## Download APK

<div align="center">

| Variant | Link |
|---------|------|
| Debug APK | [📥 Download](https://github.com/rinkusharma79346-droid/J.A.R.V.I.S/releases) |
| Release APK | [📥 Download](https://github.com/rinkusharma79346-droid/J.A.R.V.I.S/releases) |

</div>

---

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**Built with passion by [Rinku Sharma](https://github.com/rinkusharma79346-droid)**

<p><i>"Versatile Agent for Your Universe"</i></p>

<p>If you find V.A.Y.U useful, consider giving it a ⭐ — it helps more than you think!</p>

</div>

## Render Deployment (Important)

This repo now supports **both** relay directories for Render rootDir compatibility:

- `vayu-relay/` (recommended)
- `jarvis-relay/` (legacy compatibility alias)

Use one of these exactly in Render **Root Directory**.

Recommended Render values for the MCP relay:

- **Root Directory:** `vayu-relay` (or `jarvis-relay` if your existing service already uses that legacy name)
- **Build Command:** `npm install`
- **Start Command:** `node server.js`

Deploy check after Render finishes:

```bash
curl https://<your-render-service>.onrender.com/api/call
```

That GET request should return a JSON usage message. Actual tool execution must use `POST /api/call` (or alias `POST /api/tools/call`) with a JSON body such as:

```bash
curl -X POST https://<your-render-service>.onrender.com/api/call \
  -H "Content-Type: application/json" \
  -d '{"tool":"vayu_read_screen"}'
```

If your Render service accidentally has **Root Directory blank**, this repo now also includes a root `npm start` fallback that launches the same relay server.

