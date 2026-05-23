# CCWEAROS

> Wear OS app that lets you talk to **Claude Code** from your wrist. Live status, token usage, permission Allow/Deny, and voice prompts — all bridged through Firebase Realtime Database.

```
   ┌──────────────────┐        ┌────────────┐        ┌────────────────┐
   │  Galaxy Watch 8  │ ◀────▶ │  Firebase  │ ◀────▶ │  macOS daemon  │
   │  (Wear OS, Kt)   │  RTDB  │  Realtime  │  Admin │  (Node + TS)   │
   └──────────────────┘        └────────────┘        └────────┬───────┘
                                                              │
                                                       pty / claude -p
                                                              │
                                                              ▼
                                                     ┌────────────────┐
                                                     │  Claude Code   │
                                                     │      CLI       │
                                                     └────────────────┘
```

## What it does

- **Glance** at your wrist to see what Claude is doing (`$ running`, "✻ Crunching…", current task, live token count, monthly cost).
- **Tap Allow / Deny** when Claude asks for permission to fetch a URL, edit a file, run a bash command — no need to switch to your terminal.
- **Speak a new task** by tapping "ask claude" on the watch. The daemon on your Mac runs `claude -p <text>` and streams the answer back to the watch.
- **Continuity** — consecutive voice prompts use `--continue` so Claude remembers the prior turn. Say "olvida todo" / "new chat" to reset.
- **Notifies on completion** — when a task finishes, the watch vibrates and auto-navigates to the response page (smart guard: only if you were on the Command or Metrics page — never interrupts you when you're reading Sessions or Followups).

## Watch pages

The watch UI is a 5-page horizontal pager. Pages that don't apply hide themselves automatically (Response and Followups appear only when there's a result; Sessions appears only when the wrapper has found Claude Code sessions on your Mac).

| #   | Page          | What you see                                                                                                                                                                                                 |
| --- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 0   | **Command**   | Current status (`$ idle` / `$ running` / `$ awaiting permission`), activity verb (`✻ Crunching…`), task title, and the big CTA — `ask claude` when fresh, `continuar` mid-thread, `✗ detener` while running. |
| 1   | **Metrics**   | Tokens used today, model + context window, session / weekly / monthly percentages, current monthly cost, reset times.                                                                                        |
| 2   | **Response**  | Claude's last answer. `$ tl;dr` headline (adaptive font size) + scrollable body with inline markdown, a thin scroll-position bar on the right, and a fade overlay above the page dots.                       |
| 3   | **Followups** | 2–3 tappable chips with Claude's suggested next prompts (or a bilingual fallback if Claude omitted them), plus a `↻ nueva conversación` reset button that prepends the reset phrase.                         |
| 4   | **Sessions**  | Recent Claude Code sessions on your Mac, grouped by project, sorted newest-first. Tap any non-active session to confirm-and-resume it in a new Terminal window with full context loaded.                     |

Long-press the `✗ detener` button (Page 0, during a running task) to force-reset the UI when the wrapper appears dead — writes `IDLE` directly to RTDB so you're not stuck in phantom-RUNNING.

## Why

Long Claude Code sessions involve a lot of "wait for thinking, accept permission, glance at output". Doing that from a watch turns dead time at the screen into ambient awareness — you can be away from the keyboard and still nudge Claude along.

## Repo layout

```
wrapper/                  # Node.js + TypeScript bridge
  src/
    index.ts              # entry point (interactive OR daemon mode)
    claude-runner.ts      # spawns claude in a pty (interactive mode)
    claude-oneshot.ts     # runs `claude -p` and parses stream-json (daemon)
    parser.ts             # ANSI strip + token/permission/activity/status extraction
    firebase.ts           # Admin SDK helpers (setStatus, sendFcmWake, etc.)
    metrics-store.ts      # rolling-window token persistence
    types/schema.ts       # source of truth for the RTDB shape
  scripts/
    verify-bridge.ts      # smoke test: write IDLE, read back
    demo-loop.ts          # live IDLE→RUNNING→PROMPT→IDLE demo
    send-prompt.ts        # test the voice flow without a microphone
    reset-rtdb.ts         # wipe stale Firebase state
    replay-fixture.ts     # tune regex against captured stdout

watch/                    # Wear OS / Jetpack Compose Material3 app
  app/src/main/java/.../
    presentation/         # MainActivity, WearApp, screens, ClaudeTheme
    data/                 # RtdbModels, Repository, FCM service

scripts/
  install-launchagent.sh  # macOS LaunchAgent installer for the daemon
  env.sh                  # sourceable shell helper (JAVA_HOME, ANDROID_HOME, adb)

firebase-rules.json       # RTDB security rules — pinned to your watch UIDs
```

## Architecture

The wrapper has **two modes**:

- **Interactive** (`npm start`) — spawns Claude in a pseudo-TTY, you use Claude in your terminal as usual. The wrapper mirrors stdout to your terminal AND parses it for tokens, activity, permission prompts, the status line. The watch shows what's happening in real time; you tap Allow/Deny on the watch instead of typing in the terminal.

- **Daemon** (`CCWEAROS_MODE=daemon`, auto-started by a macOS LaunchAgent) — runs in background forever. Listens for prompts from the watch on `/prompt`. When a new prompt arrives, runs `claude -p <text> --output-format=stream-json --verbose`, parses the JSON event stream, and streams the answer + token usage + model info back to the watch.

Both modes share the same Firebase RTDB schema and the same watch UI.

## Firebase schema

| Path                | Who writes | Who reads        | Contents                                                             |
| ------------------- | ---------- | ---------------- | -------------------------------------------------------------------- |
| `/status`           | wrapper    | watch            | `"IDLE" \| "RUNNING" \| "AWAITING_PERMISSION" \| "OFFLINE"`          |
| `/metrics`          | wrapper    | watch            | Rolling-window token totals (day / week / month)                     |
| `/permissionPrompt` | wrapper    | watch            | Human-readable prompt text                                           |
| `/activity`         | wrapper    | watch            | Spinner verb ("Crunching…", "Worked for 33s")                        |
| `/task`             | wrapper    | watch            | Current task description (from the terminal's OSC title)             |
| `/response`         | wrapper    | watch            | Last ~1.5KB of Claude's response (markdown)                          |
| `/claudeStatus`     | wrapper    | watch            | Parsed model, contextSize, monthlyCost, reset times                  |
| `/fcmToken`         | watch      | wrapper          | Watch's FCM token (so the wrapper can wake the watch out of ambient) |
| `/command`          | watch      | wrapper          | `{text, issuedAt}` — Allow/Deny response (`"1\r"` / `""`)            |
| `/prompt`           | watch      | wrapper (daemon) | `{text, issuedAt}` — new voice prompt to run                         |

`/command` and `/prompt` write `issuedAt` as `ServerValue.TIMESTAMP` — using `System.currentTimeMillis()` made every command look "stale" because the Wear OS emulator's clock was hours behind the Mac's.

## Setup

### Prerequisites

- macOS (the wrapper runs there)
- [Claude Code](https://docs.claude.com/en/docs/claude-code/overview) installed (`claude --version` should work)
- Free [Firebase](https://console.firebase.google.com) account
- [Android Studio](https://developer.android.com/studio) + JDK 17+ (Studio installs a bundled JDK; if you also need one on PATH: `brew install openjdk@21`)
- A Wear OS API 30+ emulator or a real Wear OS 4+ watch

### 1 — Firebase project

1. Console → **Add project** → name it whatever you want (the repo uses `ccwearos` internally; substitute everywhere you see that name)
2. **Realtime Database** → **Create Database** → any region → start in Locked mode
3. **Authentication** → **Get started** → **Sign-in method** → enable **Anonymous**
4. **Project settings** → **Service accounts** → **Generate new private key** → save the JSON locally (this is the Admin SDK key; **do not commit it**)
5. **Project settings** → register an Android app with package `com.caamano.ccwearos` (or whatever you renamed it to) → download `google-services.json` → drop it at `watch/app/google-services.json`

### 2 — Wrapper

```bash
cd wrapper
npm install
mkdir -p secrets && mv ~/Downloads/<project>-firebase-adminsdk-*.json secrets/firebase-admin-key.json
cp .env.example .env
# Edit .env: set FIREBASE_DB_URL to your Realtime DB URL
```

Verify the bridge works (writes `/status=IDLE`, reads it back):

```bash
npm run verify
```

### 3 — Watch (emulator first)

```bash
cd ../watch
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.caamano.ccwearos/.presentation.MainActivity
```

On first launch you should see `$ offline` (the daemon isn't running yet). The next steps fix that.

### 4 — Pin Firebase rules to your watch's UID

Each Wear OS device gets a stable anonymous-auth UID. Capture it:

```bash
adb logcat -d | grep "Notifying id token"
# → user ( <UID> )
```

Open `firebase-rules.json`, replace `EMULATOR_UID_HERE` with that UID. (Repeat with `REAL_WATCH_UID_HERE` once you've installed on a real watch — see "Deploying to a real Galaxy Watch 8" below.) Paste the JSON into Firebase Console → Realtime Database → Rules → **Publicar / Publish**.

### 5 — Daemon (auto-start at login)

```bash
bash scripts/install-launchagent.sh
tail -f ~/Library/Logs/ccwearos.log
# Expect: [ccwearos] Daemon online. DB: ...
```

Now your Mac always runs the daemon. To stop or uninstall:

```bash
bash scripts/install-launchagent.sh stop
bash scripts/install-launchagent.sh uninstall
```

### 6 — Try the voice flow

If your emulator has no mic, simulate it:

```bash
cd wrapper
npx tsx scripts/send-prompt.ts "explain this project in one sentence"
```

If you're on a real watch with a mic, tap **ask claude** on the watch, allow the mic permission once, and speak. You should see the watch flip to `$ running`, the response stream into the `$ output` section, then back to `$ idle`.

## Deploying to a real Galaxy Watch 8

1. **Watch: enable Developer Mode** — Settings → About watch → Software → tap "Software version" 7 times.
2. **Watch: enable Wireless Debugging** — Settings → Developer options → toggle **ADB debugging** + **Debug over Wi-Fi** ON.
3. **Watch: pair** — In Wireless debugging, tap "Pair new device". Note the IP, port, and 6-digit code.
4. **Mac: pair + connect**:
   ```bash
   adb pair <IP>:<PAIRING_PORT> <CODE>
   adb connect <IP>:<CONNECTION_PORT>     # second port shown on watch's main wireless-debug screen
   ```
5. **Mac: install + launch**:
   ```bash
   adb -s <IP>:<CONNECTION_PORT> install -r watch/app/build/outputs/apk/debug/app-debug.apk
   adb -s <IP>:<CONNECTION_PORT> shell am start -n com.caamano.ccwearos/.presentation.MainActivity
   ```
6. **Capture the watch's UID** from logcat and add to `firebase-rules.json` as the second allowed UID. Republish.
7. **Grant mic permission** the first time you tap "ask claude" — Wear OS will prompt.

## Two design choices worth knowing about

**`node-pty` spawn-helper +x bit.** npm installs node-pty with a `prebuilds/<arch>/spawn-helper` binary, but the install loses its executable bit. Every `posix_spawnp` fails with no useful error. The fix is a postinstall script that runs `chmod +x` on it — see `wrapper/package.json`.

**`/bin/sh -c 'exec claude'` wrapper.** node-pty's `posix_spawnp` doesn't handle some Bun-compiled binaries (Claude Code is one). Spawning `sh` and letting it `exec` the target works around it cleanly.

**Server timestamps everywhere.** Wear OS emulators drift hours behind real-world time. `System.currentTimeMillis()` from the watch made every command look stale to the wrapper's age check. Switching to Firebase's `ServerValue.TIMESTAMP` makes the bug impossible.

**Stream-json over TUI parsing.** Daemon mode uses `claude -p --output-format=stream-json --verbose`, which gives structured events with `model`, `total_cost_usd`, `usage.input_tokens`, `modelUsage[].contextWindow`, `rate_limit_event.resetsAt`. Way more reliable than parsing the interactive TUI.

**AnimatedContent needs a `contentKey` for status flips.** The watch's top-level `AnimatedContent` switches between `PermissionScreen`, `OfflineScreen` and `DashboardScreen` based on status. If you key it on `status` directly, every `RUNNING ↔ IDLE` transition (i.e., every task completion) re-creates the dashboard composable — `rememberPagerState` resets to page 0 and any `LaunchedEffect` subscribers (notably the `SharedFlow` that drives the task-completion haptic + auto-nav to the response page) get cancelled mid-emission. Pass a `contentKey` lambda that collapses dashboard-eligible statuses under a single key so the dashboard instance persists across normal flips.

**Reset critical flags BEFORE awaits in `finally`.** The wrapper's voice-run finally block clears six pieces of state in sequence; the `busy = false` reset used to sit at the bottom. A transient Firebase OAuth token-refresh failure could throw on any of the earlier awaits, exit the finally early and leave `busy = true` forever — every subsequent voice prompt then dropped silently as "Busy" until the daemon was restarted. Put the flag reset first; wrap the rest in its own try/catch.

## Limitations

- The wrapper has to run on your Mac. If the Mac is off, the watch shows OFFLINE — there's no cloud relay.
- Daemon mode (voice flow) uses `claude -p` which is non-interactive; Claude auto-allows tool use during these runs. For permission-gated work, run interactive mode (`npm start`).
- Markdown rendering on the watch is inline-only (bold / italic / code). Block markdown renders as plain text.
- Tables get flattened to `cell · cell · cell` rows — multi-line table cells lose their column structure.

## Tech stack

- **Wrapper**: Node.js 22, TypeScript 5.6 strict, `firebase-admin` 13, `node-pty` 1.1, Vitest
- **Watch**: Kotlin 2.2, Wear Compose Material3 1.5, Firebase BOM 33.7 (database / auth / messaging), AGP 9.2, JDK 21
- **Infra**: Firebase Realtime Database, Firebase Cloud Messaging, macOS LaunchAgents

## License

MIT — see [LICENSE](LICENSE).
