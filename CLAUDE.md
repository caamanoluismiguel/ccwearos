# CCWEAROS — Wear OS Two-Way AI Controller

Galaxy Watch 8 ↔ Firebase Realtime DB ↔ macOS Node.js wrapper ↔ Claude Code CLI.

The watch shows live status / activity / task / token usage / Claude's response, lets you tap-allow permission prompts, and (with a real watch microphone) lets you start new Claude tasks by voice. The Mac runs a daemon that auto-starts at login.

## Layout

- `/wrapper` — Node.js + TypeScript bridge. Two modes:
  - **Interactive** (`npm start`): spawns Claude in a pty, you use Claude in your terminal as normal, watch monitors + responds to permission prompts.
  - **Daemon** (`CCWEAROS_MODE=daemon`): listens for prompts from the watch, runs `claude -p <text> --output-format=stream-json --verbose`, streams the answer to `/response`. Auto-started by LaunchAgent.
- `/wrapper/secrets/firebase-admin-key.json` — Admin SDK key, gitignored.
- `/wrapper/fixtures/` — synthetic Claude Code output for parser tuning; `captures/` is gitignored.
- `/watch` — Wear OS / Jetpack Compose Material3 client. Pixel mascot + terminal aesthetic.
- `/firebase-rules.json` — RTDB security rules. Pinned to watch UIDs.
- `/scripts/install-launchagent.sh` — installer for the daemon LaunchAgent.
- `/scripts/env.sh` — sourceable shell helper (JAVA_HOME, ANDROID_HOME, adb on PATH).

## Firebase RTDB Schema

Source of truth: `wrapper/src/types/schema.ts`. Watch-side Kotlin mirror in `watch/.../data/RtdbModels.kt`.

| Path                | Who writes | Who reads        | Notes                                                                   |
| ------------------- | ---------- | ---------------- | ----------------------------------------------------------------------- |
| `/status`           | wrapper    | watch            | `"IDLE" \| "RUNNING" \| "AWAITING_PERMISSION" \| "OFFLINE"`             |
| `/metrics`          | wrapper    | watch            | Rolling-window token totals (day/week/month)                            |
| `/command`          | watch      | wrapper          | `{text, issuedAt}` — permission responses (`"1\r"` / `""`)              |
| `/prompt`           | watch      | wrapper (daemon) | `{text, issuedAt}` — new voice prompt to run                            |
| `/permissionPrompt` | wrapper    | watch            | Human-readable prompt text                                              |
| `/activity`         | wrapper    | watch            | Spinner verb ("Crunching…", "Worked for 33s")                           |
| `/task`             | wrapper    | watch            | Current task description (from OSC title)                               |
| `/response`         | wrapper    | watch            | Last ~1.5KB of Claude's response (markdown)                             |
| `/claudeStatus`     | wrapper    | watch            | Parsed Claude status line: model, contextSize, monthlyCost, reset times |
| `/fcmToken`         | watch      | wrapper          | Watch's FCM registration token (for wake-ups)                           |

Both `/command` and `/prompt` use Firebase `ServerValue.TIMESTAMP` for `issuedAt` to avoid clock-skew bugs on emulators.

## Sprint Tracker

- [x] Sprint 0 — Scaffold
- [x] Sprint 1 — Connection (verified end-to-end)
- [x] Sprint 2 — Read (tokens, activity, task, claude status line via stream-json)
- [x] Sprint 3 — Write (permission Allow/Deny, voice prompts)
- [x] Sprint 4a — Apple-minimalist UI, haptics, mascot, inline markdown
- [x] Sprint 4b — Daemon mode + LaunchAgent
- [x] Sprint 4c — RTDB rules pinned to watch UIDs
- [x] Sprint 4d — FCM wake-up (code shipped; tested on real watch when deployed)
- [ ] Sprint 4e — Deploy to physical Galaxy Watch 8 (see "Real watch deploy" below)

## Wrapper modes

### Interactive (`npm start`)

```bash
cd ~/projects/CCWEAROS/wrapper
CCWEAROS_CAPTURE_FILE=fixtures/captures/session-$(date +%Y%m%d-%H%M%S).log npm start
```

Spawns Claude in your terminal via `sh -c 'exec claude'` inside a pty. You use Claude as normal. The wrapper mirrors stdout to its own terminal AND parses for tokens / activity / permission / claude status. When Claude asks for permission, watch shows the prompt — tap Allow on watch sends `"1\r"` (selects "1. Yes"), tap Deny sends `""` (Escape).

### Daemon (auto-started by LaunchAgent)

```bash
bash ~/projects/CCWEAROS/scripts/install-launchagent.sh        # install + start
bash ~/projects/CCWEAROS/scripts/install-launchagent.sh stop   # stop
bash ~/projects/CCWEAROS/scripts/install-launchagent.sh uninstall
```

Runs the wrapper with `CCWEAROS_MODE=daemon`. Doesn't spawn Claude eagerly — listens for `/prompt` writes from the watch and runs `claude -p <text> --output-format=stream-json --verbose` per request. Streams the parsed response + cost + model info to Firebase.

Logs:

```bash
tail -f ~/Library/Logs/ccwearos.log     # stdout
tail -f ~/Library/Logs/ccwearos.err.log # stderr
```

## Scripts

```bash
cd ~/projects/CCWEAROS/wrapper

npm start                                # interactive mode
npm test                                 # 19/19 vitest unit tests
npm run typecheck                        # tsc --noEmit
npm run verify                           # smoke test: write IDLE, read back
npm run demo                             # live IDLE→RUNNING→PROMPT→IDLE demo
npm run replay -- fixtures/<file>        # parser tuning against captured stdout

# Test the daemon prompt flow without a microphone:
npx tsx scripts/send-prompt.ts "explain this project in one sentence"

# Wipe stale Firebase state between sessions:
npx tsx scripts/reset-rtdb.ts
```

## Real Galaxy Watch 8 deploy

(Status: pending. Steps tested against the API 36 emulator; physical watch requires the user to enable developer mode.)

1. **Watch: enable Developer Mode**
   - Settings → About watch → Software → tap "Software version" **7 times**
   - You'll see "Developer mode turned on"

2. **Watch: enable Wireless Debugging**
   - Settings → Developer options
   - Toggle **ADB debugging** ON
   - Toggle **Debug over Wi-Fi** ON
   - Tap **Wireless debugging** → "Pair new device"
   - Note the IP, port, and 6-digit pairing code

3. **Mac: pair + connect**

   ```bash
   source ~/projects/CCWEAROS/scripts/env.sh
   adb pair <WATCH_IP>:<PAIRING_PORT>
   # paste the 6-digit code when prompted
   adb connect <WATCH_IP>:<CONNECTION_PORT>  # second port shown on watch
   adb devices                                # confirm watch shows up
   ```

4. **Mac: install the APK**

   ```bash
   cd ~/projects/CCWEAROS/watch
   ./gradlew assembleDebug
   adb -s <WATCH_IP>:<CONNECTION_PORT> install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s <WATCH_IP>:<CONNECTION_PORT> shell am start -n com.caamano.ccwearos/.presentation.MainActivity
   ```

5. **Mac: capture the real watch's anonymous UID**

   ```bash
   adb -s <WATCH_IP>:<CONNECTION_PORT> logcat -d | grep "Notifying id token"
   ```

   Copy the UID and replace `REPLACE_WITH_REAL_WATCH_UID` in `/firebase-rules.json`, then republish in Firebase Console.

6. **First-launch on watch**: grant the mic permission when prompted (Wear OS asks for RECORD_AUDIO the first time you tap "ask claude").

## FCM wake-up (Sprint 4d)

In **interactive** mode, when Claude asks for permission the wrapper writes `/status="AWAITING_PERMISSION"` AND sends a high-priority FCM data message to the watch's registered token. This wakes the watch out of ambient/doze so you actually see the haptic + permission screen even if the display was off.

How it works:

- Watch `MainActivity.registerFcmToken()` writes the current token to `/fcmToken` on every launch
- Watch `CcwearosMessagingService.onNewToken` updates `/fcmToken` whenever FCM rotates the token
- Wrapper `firebase.sendFcmWake("permission")` reads `/fcmToken`, sends via Admin SDK `messaging().send()` with `android.priority="high"` + `ttl=60s`
- Watch receives the data message in `onMessageReceived` — Wear OS treats high-priority data messages as wake events

Daemon mode (voice flow) doesn't need FCM — you're already looking at the watch when you tap "ask claude".

## Watch UIDs

The RTDB rules in `/firebase-rules.json` pin all reads + the `/command` and `/prompt` writes to known anonymous-auth UIDs.

| Device                     | UID                   |
| -------------------------- | --------------------- |
| Wear OS emulator (current) | `EMULATOR_UID_HERE`   |
| Real Galaxy Watch 8        | `REAL_WATCH_UID_HERE` |

After adding a new UID to rules, paste the JSON into Firebase Console → Realtime Database → Rules → Publish.

## Hard rules

- Wrapper is ESM TypeScript strict (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`)
- Never commit `secrets/`, `.env`, or `google-services.json`
- `/command` and `/prompt` MUST include `issuedAt`; wrapper drops anything older than `COMMAND_MAX_AGE_SECONDS` (default 60s)
- `/metrics` writes debounced to `METRICS_DEBOUNCE_MS` (default 5000ms)
- `/response` writes debounced to 5s (interactive) / 800ms (daemon)
- `node-pty`'s `posix_spawnp` fails on Bun-compiled binaries (claude is one) → wrap target in `/bin/sh -c 'exec <claude>'`
- `node-pty/prebuilds/<arch>/spawn-helper` loses its +x bit during npm install → handled by `package.json` postinstall hook
- Watch writes `issuedAt` as `ServerValue.TIMESTAMP` (not `System.currentTimeMillis()`) to immunise against device clock skew

## Known limitations

- Daemon's `-p` mode is one-shot — Claude doesn't keep session state between voice prompts. For continued conversation, run interactive mode.
- Permission prompts are interactive-mode only (`-p` auto-allows). Voice prompts that need permission will run with default permissions.
- Markdown rendering on watch is inline only (bold/italic/code). Block markdown (lists, headings, code blocks) renders as plain text.
- Tables are flattened to `cell1 · cell2 · cell3` rows — multi-line table cells lose column association.

## Toolchain on this Mac

Installed via `brew --cask`: `android-studio`, `android-platform-tools` (gives `adb` standalone). JDK via `brew install openjdk@21` (formula, not cask — temurin cask needs sudo). Source `scripts/env.sh` to put everything on PATH for any shell.
