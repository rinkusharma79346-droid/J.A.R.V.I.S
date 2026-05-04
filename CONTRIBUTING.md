# Contributing to V.A.Y.U Agent

First off, thank you for considering contributing to V.A.Y.U! It's people like you that make open-source awesome.

## Ways to Contribute

- **Bug Reports** — Found a bug? [Open an issue](https://github.com/rinkusharma79346-droid/J.A.R.V.I.S/issues/new) with steps to reproduce
- **Feature Requests** — Have an idea? Suggest it in [Discussions](https://github.com/rinkusharma79346-droid/J.A.R.V.I.S/discussions)
- **Pull Requests** — Code fixes, new features, documentation improvements — all welcome
- **Spread the Word** — Star the repo, share on social media, tell your friends

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android device running API 30+ (Android 11+)
- Node.js 18+ (for MCP server and relay server)
- An API key (Gemini, OpenAI, or NVIDIA)

### Development Setup

1. **Fork** the repository
2. **Clone** your fork:
   ```bash
   git clone https://github.com/your-username/J.A.R.V.I.S.git
   cd J.A.R.V.I.S
   ```
3. **Create a branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```
4. **Create `local.properties`:**
   ```properties
   sdk.dir=/path/to/android/sdk
   GEMINI_API_KEY=your_key_here
   ```
5. **Build and test:**
   ```bash
   ./gradlew assembleDebug
   ```
6. **Commit and push:**
   ```bash
   git add .
   git commit -m "feat: your feature description"
   git push origin feature/your-feature-name
   ```
7. **Open a Pull Request**

### Code Style

- **Kotlin:** Follow Android Kotlin style guide. Use meaningful variable names.
- **JavaScript:** Standard style. Use `const`/`let`, avoid `var`.
- **Comments:** Add comments for complex logic. V.A.Y.U-specific terms should be documented.

## Project Structure

```
J.A.R.V.I.S/
├── app/                    # Android app (Kotlin)
│   └── src/main/java/...   # Main source code
├── vayu-relay/           # Primary relay server (Node.js)
├── mcp-server/             # MCP server for Claude Desktop (Node.js)
├── src/                    # Web dashboard (React)
├── vayu-mcp-server/      # Older MCP server prototype (TypeScript)
└── relay-server/           # Older relay server (deprecated)
```

## Reporting Bugs

When filing a bug report, please include:

1. **Android device** and **OS version**
2. **V.A.Y.U version** (check About in Settings)
3. **API provider and model** you're using
4. **Steps to reproduce** the issue
5. **Expected vs actual behavior**
6. **Logcat output** if available (`adb logcat -s VayuService`)

## Pull Request Guidelines

- Keep PRs focused on a single concern
- Include a clear description of what the PR does
- Test on a real device (emulator doesn't support accessibility well)
- Don't commit API keys or secrets
- Update documentation if you change behavior

## Ideas That Need Help

These are features we'd love to see contributions for:

- [ ] **iOS equivalent** using Accessibility API / Shortcuts automation
- [ ] **Voice input** for task commands (speech-to-text)
- [ ] **Task templates** — pre-built automation workflows
- [ ] **Authentication** on the relay server
- [ ] **Real-time web dashboard** connected to the relay
- [ ] **Computer use mode** — control a desktop via VNC + vision
- [ ] **Multi-device support** — control multiple phones from one MCP client
- [ ] **Action recording** — record and replay user gestures

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
