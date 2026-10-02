# CCWEAROS — Wear OS Two-Way AI Controller

> **Heads-up for human readers:** this file is the working reference auto-loaded by [Claude Code](https://docs.claude.com/en/docs/claude-code) when working inside this repo. If you arrived here as a human looking for usage docs, start with **[README.md](README.md)** — this file is a deep-dive for contributors and the AI.

Galaxy Watch 8 ↔ Firebase Realtime DB ↔ macOS Node.js wrapper ↔ Claude Code CLI.

The watch shows live status / activity / task / token usage / Claude's response, lets you tap-allow permission prompts, and (with a real watch microphone) lets you start new Claude tasks by voice. The Mac runs a daemon that auto-starts at login.

## Layout

- `/wrapper` — Node.js + TypeScript bridge. Two modes:
  - **Interactive** (`npm start`): spawns Claude in a pty, you use Claude in your terminal as normal, watch monitors + responds to permission prompts.
  - **Daemon** (`CCWEAROS_MODE=daemon`): listens for prompts from the watch, runs `claude -p <text> --output-format=stream-json --verbose`, streams the answer to `/response`. Auto-started by LaunchAgent.
- `/wrapper/src/` — library code (importable, side-effect-free where possible):
  - `firebase.ts` — RTDB helpers including `registerCrashCleanup` (onDisconnect), `clearStaleState`, `appendAuditEntry` (transaction).
  - `claude-runner.ts` — interactive pty runner used by `npm start` + `cc`. Accepts `extraArgs` for `--resume` / `--permission-mode`.
  - `claude-voice.ts` — voice-mode runner used by daemon for `claude -p` one-shots.
  - `parser.ts` — ~40 regex extractors (tokens, status line, response, followups, TL;DR, OSC titles).
  - `sessions-scanner.ts` — 15s scan of `~/.claude/sessions` + `~/.claude/projects/*/*.jsonl` → `/recentSessions`.
  - `share-args.ts`, `takeover-utils.ts`, `sh-escape.ts`, `pid-utils.ts` — pure, tested utilities used by the takeover flow.
  - `types/schema.ts` — TS source of truth for every RTDB path.
- `/wrapper/scripts/` — entry points:
  - `share.ts` (the `cc` alias) — wrapper-pty session under `kind="wrapper-pty"`.
  - `hooks/pre-tool-use.ts` — PreToolUse hook for `/ccwearos` mid-session bridging.
  - `hooks/enable-share.ts`, `disable-share.ts`, `enable-takeover.ts` — slash command entry points.
  - `hooks/_helpers.ts` — shared session detection (`detectSessionId`, `detectSessionIdDetailed`, `detectPermissionMode`).
  - `install-hooks.ts` — idempotent installer for the slash commands + PreToolUse entry.
  - `audit.ts` — CLI viewer for `/auditLog`.
- `/wrapper/secrets/firebase-admin-key.json` — Admin SDK key, gitignored.
- `/wrapper/fixtures/` — synthetic Claude Code output for parser tuning; `captures/` is gitignored.
- `/wrapper/templates/` — slash command markdown files installed to `~/.claude/commands/`.
- `/watch` — Wear OS / Jetpack Compose Material3 client. Pixel mascot + terminal aesthetic.
- `/firebase-rules.json` — RTDB security rules. Pinned to watch UIDs.
- `/scripts/install-launchagent.sh` — installer for the daemon LaunchAgent.
- `/scripts/env.sh` — sourceable shell helper (JAVA_HOME, ANDROID_HOME, adb on PATH).

## Firebase RTDB Schema

Source of truth: `wrapper/src/types/schema.ts`. Watch-side Kotlin mirror in `watch/.../data/RtdbModels.kt`.

| Path                | Who writes | Who reads        | Notes                                                                                                                                                                                                   |
| ------------------- | ---------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/status`           | wrapper    | watch            | `"IDLE" \| "RUNNING" \| "AWAITING_PERMISSION" \| "OFFLINE"`                                                                                                                                             |
| `/metrics`          | wrapper    | watch            | Rolling-window token totals (day/week/month)                                                                                                                                                            |
| `/command`          | watch      | wrapper          | `{text, issuedAt, promptId?}`. Only allow `"1\r"` / deny ESC / stop `"\x03"` pass `checkCommand` (`src/command-guard.ts`); answers must echo the current `/permissionPromptId`. |
| `/prompt`           | watch      | wrapper (daemon) | `{text, issuedAt}` — new voice prompt to run                                                                                                                                                            |
| `/permissionPrompt` | wrapper    | watch            | Human-readable prompt text, e.g. `Bash: git push origin main` + description (`PermissionPromptTracker` in `parser.ts`) |
| `/permissionPromptId` | wrapper  | watch            | Random one-time id minted by `publishPermissionPrompt` with every prompt, cleared with it. The watch echoes it in `/command.promptId` so stale, replayed or double taps can't answer another prompt. |
| `/activity`         | wrapper    | watch            | Spinner verb ("Crunching…", "Worked for 33s")                                                                                                                                                           |
| `/task`             | wrapper    | watch            | Current task description (from OSC title)                                                                                                                                                               |
| `/response`         | wrapper    | watch            | Last ~1.5KB of Claude's response (markdown)                                                                                                                                                             |
| `/headline`         | wrapper    | watch            | TL;DR one-liner extracted from response (info runs)                                                                                                                                                     |
| `/followups`        | wrapper    | watch            | 2-3 contextual chips Claude suggested at end of response (end of Resultado)                                                                                                                                       |
| `/taskKind`         | wrapper    | watch            | `"action" \| "info"` — drives the Resultado layout branch                                                                                                                                                      |
| `/toolEvents`       | wrapper    | watch            | Up to 12 tool invocations observed during the run                                                                                                                                                       |
| `/claudeStatus`     | wrapper    | watch            | Parsed Claude status line: model, contextSize, monthlyCost, reset times                                                                                                                                 |
| `/sharedSession`    | wrapper    | watch            | Active `cc`/share.ts session metadata. Gates voice prompts + the Inicio CTA.                                                                                                                                |
| `/recentSessions`   | wrapper    | watch            | Mac-wide Claude session snapshot. Drives Page 5 grouped-by-project list.                                                                                                                                |
| `/auditLog`         | wrapper    | (CLI only)       | Rolling 20-entry log of every permission decision; viewable via `scripts/audit.ts`. Written via `ref.transaction()` so voice + cc + hook writes don't lose entries.                                     |
| `/claimRequest`     | watch      | wrapper (daemon) | `{sessionId, cwd, issuedAt}` — Sprint 4n. Watch writes when user taps a session row on Page 5 and confirms. Daemon's `watchClaimRequest` handler validates + spawns `cc --resume <id>` in new Terminal. |
| `/claimResult`      | wrapper    | watch            | `{ok, reason?, sessionId, ts}` — daemon's response to the most recent claim. Watch shows banner; auto-dismisses after 4s.                                                                               |
| `/blocker`          | wrapper    | watch            | `{kind: trust\|login\|crash\|timeout\|other, hint, cwd?, ts}`: something only the Mac can fix. Watch shows BlockedScreen ("Claude necesita tu Mac"). Cleared at run start. |
| `/outcome`          | wrapper    | watch            | `{ok, exitCode, ts}`: real exit status of the last voice run. Drives ✓/✗ and the done moment (never text regex). Cleared at run start. |
| `/conversationActive` | wrapper  | watch            | `true` while voice prompts continue a thread (`--continue`); `false` at daemon start / after reset. Drives "Seguir" vs "Preguntar". Survives crash cleanup. |
| `/fcmToken`         | watch      | wrapper          | Watch's FCM registration token (for wake-ups)                                                                                                                                                           |

Both `/command` and `/prompt` use Firebase `ServerValue.TIMESTAMP` for `issuedAt` to avoid clock-skew bugs on emulators.

**Server-side crash cleanup.** On startup every wrapper entry point (daemon, `npm start`, `cc`) registers `onDisconnect()` handlers via `registerCrashCleanup()` in `src/firebase.ts`. Firebase clears `/status`, `/permissionPrompt`, `/activity`, `/task`, `/headline`, `/taskKind`, `/toolEvents`, `/followups`, `/command` (and `/sharedSession` for `cc`) the moment our TCP connection drops — the only mechanism that survives `kill -9`, OOM, or Mac sleep. On clean shutdown we explicitly `clearCrashCleanup()` to avoid racing ourselves.

## ¿Cuál usar: `cc`, `/ccwearos`, o `/ccwearos-takeover`?

Cuatro formas de que el reloj reciba permission prompts. Resumen rápido:

| Si quieres...                                                                       | Usa                                | Por qué                                                                                                                                                                                                                                  |
| ----------------------------------------------------------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Empezar nueva sesión y poder irte del Mac**                                       | `cc` (alias) en cualquier Terminal | Wrapper es dueño del pty desde el arranque. Tap watch = autoriza directo, sin Terminal prompt extra.                                                                                                                                     |
| **Estoy en una sesión activa y me quiero ir AHORA mismo del Mac**                   | `/ccwearos-takeover` slash         | Abre nueva Terminal con `cc --resume <id> --permission-mode default`. La sesión continúa intacta en la ventana nueva y cada permiso llega al reloj. La vieja Terminal queda read-only; ciérrala cuando vuelvas. **Path canónico para "me voy".** |
| **Monitorear desde el reloj sin perder la Terminal actual** (acepto double-confirm) | `/ccwearos` slash                  | Hook ya instalado en `~/.claude/settings.json`. Puede haber doble confirmación (reloj + Terminal); `enable-share.ts` te avisa.                                                                                              |
| **Preguntar algo nuevo por voz desde el reloj**                                     | Inicio, botón "Preguntar"          | Daemon spawn `claude -p` para esa pregunta.                                                                                                                                                                                              |

**Setup `cc` (una sola vez):**

```bash
echo "alias cc='npx tsx ~/projects/CCWEAROS/wrapper/scripts/share.ts'" >> ~/.zshrc
source ~/.zshrc
```

Luego `cc` en lugar de `claude` cuando vas a salir del Mac.

**Setup `/ccwearos` (una sola vez):**

```bash
cd ~/projects/CCWEAROS/wrapper && npx tsx scripts/install-hooks.ts
```

## Sprint History

Full sprint-by-sprint history (Sprints 0 → 4q) moved to **[docs/CHANGELOG.md](docs/CHANGELOG.md)** to keep this file focused on current-state reference. The changelog still uses the same `- [x] Sprint NX — <Title>. <Body>.` format the project has used since Sprint 0.

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
npm test                                 # vitest unit tests (167 on 2026-10-01; needs FIREBASE_DB_URL in .env)
npm run typecheck                        # tsc --noEmit
npm run verify                           # smoke test: write IDLE, read back
npm run demo                             # live IDLE→RUNNING→PROMPT→IDLE demo
npm run replay -- fixtures/<file>        # parser tuning against captured stdout

# Test the daemon prompt flow without a microphone:
npx tsx scripts/send-prompt.ts "explain this project in one sentence"

# Inspect the rolling /auditLog (20 most-recent permission decisions):
npx tsx scripts/audit.ts

# Wipe stale Firebase state between sessions:
npx tsx scripts/reset-rtdb.ts
```

## Real Galaxy Watch 8 deploy

Live on a physical Galaxy Watch 8 since Sprint 4e; wireless adb port rotates after sleep so reconnecting is the first thing to do.

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

6. **First-launch on watch**: allow notifications when asked (needed for the "Permisos" notification with Permitir/Rechazar when the screen is off). Voice uses the system recognizer, so no mic permission.

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
- **Prompt ids (2026-10-01).** Every permission prompt goes out via `publishPermissionPrompt` (never a bare `/permissionPrompt` write) and every `/command` consumer runs `checkCommand(cmd, activePromptId)`. Clearing a prompt clears its id. Watch writers read `/permissionPromptId` at tap time and drop the tap if it changed.
- **Takeover/claim use `--permission-mode default`, never `dontAsk`.** `dontAsk` auto-denies every tool not pre-allowed (code.claude.com/docs/en/permissions), so the watch would never see a prompt.
- `/command` and `/prompt` MUST include `issuedAt`; wrapper drops anything older than `COMMAND_MAX_AGE_SECONDS` (default 60s). `pre-tool-use.ts:pollForCommand` enforces this against `pollStartedAt` so a stale entry from a previous prompt isn't consumed.
- `/metrics` writes debounced to `METRICS_DEBOUNCE_MS` (default 5000ms)
- `/response` writes debounced to 5s (interactive) / 800ms (daemon)
- `node-pty`'s `posix_spawnp` fails on Bun-compiled binaries (claude is one) → wrap target in `/bin/sh -c 'exec <claude>'`
- `node-pty/prebuilds/<arch>/spawn-helper` loses its +x bit during npm install → handled by `package.json` postinstall hook
- Watch writes `issuedAt` as `ServerValue.TIMESTAMP` (not `System.currentTimeMillis()`) to immunise against device clock skew
- **Crash cleanup (Sprint 4l invariant).** Every wrapper entry point — `runInteractive`, `runDaemon`, `share.ts`, `pre-tool-use.ts` — MUST:
  1. `await registerCrashCleanup({ uiSurfaces: true, sharedSession?: true })` after `initFirebase()` (cc owns sharedSession; the others don't).
  2. On clean shutdown call `clearStaleState("OFFLINE")` then `clearCrashCleanup()` BEFORE `process.exit()`.
  3. The hook (`pre-tool-use.ts`) also installs `SIGTERM` / `SIGINT` / `uncaughtException` handlers that run the same cleanup — otherwise a host-killed hook leaves `/permissionPrompt` set forever.
- **Awaited audit writes.** Any `appendAuditEntry()` call immediately before `process.exit()` MUST be `await`-ed. `void appendAuditEntry(...)` + immediate exit drops the RTDB write before the request leaves the socket. Drops are silent.
- **`isPidAlive` lives in ONE place.** `src/pid-utils.ts`. Don't duplicate; every caller imports. The guard against `pid <= 1` is essential because `process.kill(0, 0)` probes the calling process group and would always return true.
- **AppleScript escape is defense in depth.** `aplEscape` strips control chars + U+2028/U+2029 so a cwd with a newline can't break out of `do script "..."`. Tests in `src/takeover-utils.test.ts` cover the attack pattern.
- **Wear OS background restriction (Sprint 4m).** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **STUBBED** on Wear OS — the Settings activity that handles the intent on phones is replaced with `com.google.android.apps.wearable.settings/FakeSettingsActivity` on watches. Calling `startActivity(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)` silently no-ops. To keep a Wear app alive in background you MUST use a `foregroundServiceType="dataSync"` (or appropriate type) service with `startForeground()` — that's the only mechanism Samsung Freecess respects. See `data/CcwearosForegroundService.kt`.

## Conversation continuity (Camino C-bis)

Voice prompts auto-continue across runs: the daemon passes `--continue` to `claude` on every prompt after the first, unless the voice text matches a `RESET_PHRASES` entry ("nueva conversación", "olvida todo", `/new`, etc. in `wrapper/src/index.ts`). It publishes the state as `/conversationActive`, so the watch's primary button reads **"Seguir"** in a thread and **"Preguntar"** otherwise, even after the watch process was killed.

Voice runs execute in a dedicated folder, `CCWEAROS_VOICE_CWD` (default `~/.ccwearos/voice`, `src/config.ts`), never inside this repo. `--continue` continuity is per-cwd. Claude Code never saves trust for the home directory, which is why it isn't the default. **One-time setup:** `cd ~/.ccwearos/voice && claude`, choose "Yes, I trust this folder", `/exit`. Until then every voice run hits the trust dialog; the runner never answers it (kills the run, publishes `/blocker kind=trust`).

The end of the Resultado page shows Claude's own 2-3 chips (the `Sugerencias:` / `Followups:` block parsed by `extractFollowups`), then **Hablar/Seguir** and **Nueva conversación**. No fake fallback chips. "Nueva conversación" asks "¿Empezar de cero?" and then `askWithReset()` prepends "nueva conversación, " so the wrapper's `isResetPrompt` trips.

## Shared sessions + Page 5 (Camino D)

Two ways the watch sees Mac sessions:

1. **Wrapper-bridged via `cc` alias** (you don't lose your Terminal). Install the alias:

   ```bash
   echo "alias cc='npx tsx ~/projects/CCWEAROS/wrapper/scripts/share.ts'" >> ~/.zshrc
   source ~/.zshrc
   ```

   Then in any directory, type `cc` instead of `claude`. The wrapper script spawns Claude in a pty, mirrors output to your Terminal AND to RTDB. The watch sees `/status`, `/permissionPrompt`, `/response` etc. live, and Allow/Deny taps route back into the pty via `/command`. Your Terminal stays alive; come back to your Mac and the conversation is right where you left it.

   While `cc` is running, `/sharedSession` is non-null. The daemon refuses voice prompts (the Inicio CTA hides behind a "sesión compartida" block) to avoid two pty's clobbering RTDB. On clean exit (Ctrl+C / `/exit`) `/sharedSession` is cleared.

2. **Read-only via sessions scanner.** Every 15s the wrapper scans `~/.claude/sessions/*.json` (active PIDs) + `~/.claude/projects/*/*.jsonl` (recent transcripts by mtime) and publishes a snapshot to `/recentSessions`. Page 5 lists them grouped by project, marking active processes with a green dot and the `cc`-shared one with a coral dot. No tap actions in V1 — claiming or resuming arbitrary sessions from the watch is Tier 2.

3. **Mid-session handoff via `/ccwearos` + PreToolUse hook (Camino E-2).** For when you started Claude normally and only now decide you need watch monitoring. One-time setup:

   ```bash
   cd ~/projects/CCWEAROS/wrapper
   npx tsx scripts/install-hooks.ts
   ```

   This writes a PreToolUse hook to `~/.claude/settings.json` and copies the `/ccwearos`, `/ccwearos-off`, and `/ccwearos-takeover` slash commands into `~/.claude/commands/`. The hook self-skips unless `/sharedSession.kind === "hook"` matches the current session.

   Usage inside any Claude Code session:
   - `/ccwearos` — marks this session as bridged. The hook now publishes every pending tool to `/permissionPrompt` and waits up to 55s for the watch's Allow/Deny. If the watch doesn't answer, the hook returns `ask` and Claude falls back to its normal Terminal permission prompt.
   - `/ccwearos-off` — clears the bridge. Subsequent tool calls go through Claude's default flow.

   While `kind="hook"` is active, the daemon's `watchCommands` handler YIELDS — it sees the watch's `/command` write but doesn't consume it, so the hook gets the reply. Voice prompts (Inicio) are still gated off.

   Watch's Inicio SharedSessionBlock text differentiates the two kinds:
   - `kind="wrapper-pty"` (cc / takeover) → "📟 sesión compartida · activa en tu Mac · cc"
   - `kind="hook"` (/ccwearos) → "📟 puente activo · permisos vienen al reloj"

4. **Auto-handoff via `/ccwearos-takeover` (Camino E-3).** When you're mid-session and decide to leave the Mac: this slash command opens a **new Terminal window** (Terminal.app, or iTerm.app per `$TERM_PROGRAM`) running `cc --resume <sessionId> --permission-mode default`. The original session is resumed under wrapper-pty control; every permission prompt in its pty is mirrored to the watch and answered there. The OLD window is left read-only — you can close it whenever.

   The slash command runs `wrapper/scripts/hooks/enable-takeover.ts`, which:
   - Detects current `sessionId` via `_helpers.detectSessionId` (refuses if it can't pin one down).
   - Refuses if another `wrapper-pty` session is alive.
   - Soft-locks `/sharedSession.kind="wrapper-pty"` immediately so the OLD Terminal's `PreToolUse` hook bails on next fire (`kind !== "hook"` → pass-through).
   - Spawns the new window via `osascript` (`-e ...` style, escaped through `shSingleQuote` + `aplEscape` helpers in `src/takeover-utils.ts`).
   - Logs an audit entry (`kind: "hook"`, `tool: "(takeover)"`).

   If `osascript` fails (e.g., macOS Automation permissions not granted), the placeholder is rolled back and a manual fallback (`cd <cwd> && cc --resume <id>`) is printed.

## Watch UI affordances

Spanish only (es-CO forced via `LocaleManager` + `res/xml/locales_config.xml`; there is no `values-en`). Dictation uses `EXTRA_LANGUAGE=es-CO`. Feedback contract in `presentation/ui/Motion.kt`: every action answers ¿qué hice? / ¿qué está pasando? / ¿qué puedo hacer? / ¿salió bien? visually and with a distinct haptic (`Haptics.kt`, rate-gated).

- **Pager, 3 fixed pages** (`DashboardScreen.kt`), with continuity transitions (`home/Continuity.kt`: blur + stretch following the swipe live, iPhone Duo-inspired, blur only while moving, API 31+):
  - **Inicio** (`home/HomePage.kt`): pixel mascot (state machine in `ui/PixelMascot.kt`), state word, ONE primary action per state (Preguntar/Seguir → Enviando "Le pedí: …" + Cancelar → Trabajando with live tool line + elapsed timer + Detener → done hop + ring + auto-slide to Resultado). 15s pickup timeout → "Tu Mac no tomó la pregunta" + Reintentar. "¿Sigue ahí?" + Reiniciar estado after 3 quiet minutes (long-press Detener still force-resets). Metrics row at the bottom opens a gauges dialog.
  - **Resultado** (`result/ResultPage.kt`): TL;DR card, ✓ Hecho / ✗ Falló from `/outcome`, markdown blocks (`MarkdownBlocks.kt`: headings, lists, code, quotes, tables as cards), tool trail, chips. `ResponseSanitizer` treats TUI junk as blocked, never renders it.
  - **Sesiones** (`home/SessionsPage.kt`): Mac sessions grouped by project, tap to claim.
- **Overlays** above the pager (`routeOverlay` in `home/HomeModel.kt`): PermissionScreen; BlockedScreen (`MAC_OFFLINE`, `WATCH_OFFLINE` banner, `CLAUDE_CRASHED`, `NEEDS_MAC`, `NO_DICTATION`), dismissed locally only.
- **PermissionScreen (v2).** Full command in a mono box, coral `EdgeButton` "Permitir" + outlined "Rechazar". Risky commands (`classifyRisk` in `presentation/permission/PermissionPrompt.kt`) need a 1.2s press-and-hold. Buttons disable when offline or already answered (`AnswerGate`, shared with notification + tile). `BackHandler` swallows swipe-back.
- **Notifications** (`notifications/`): "Claude pide permiso" (Permitir/Rechazar; risky → only Abrir), "Claude terminó", "Claude necesita tu Mac". Not shown while the app is visible.
- **Tile + complication.** States Listo/Trabajando/Permiso/Necesita tu Mac/Hecho; Permitir/Rechazar on the tile only for non-risky prompts that fit fully. Deep links via `notifications/DeepLinks.kt` (`action=voice` opens dictation, `action=result` opens Resultado).
- **ClaimResultBanner** stays until tapped/swiped.

## Known limitations

- The pty parser can't rebuild characters a partial terminal redraw skipped (rare `luism guelcaamano`-style gaps); the next full redraw wins. A real fix needs a terminal emulator (`@xterm/headless`).
- Tables render as one card per row ("Columna: valor"); more than 4 columns shows "Tabla: ábrela en tu Mac".
- Action runs (tool-heavy) often skip the `Followups:` block; Resultado then shows only Hablar / Nueva conversación.
- ~~Phantom "wrapper not reachable" on wake~~ — **resolved in Sprint 4m** by the foreground service (process stays alive across screen-off) + `SharingStarted.Eagerly` on routing flows (listener never disconnects) + Firebase disk persistence (cold start hits cache before network). The trade-off is a persistent ongoing notification in the watch's panel and ~2-3%/day extra battery from the always-on listener; both judged worthwhile.
- `/ccwearos-takeover` cannot resume the SAME session that invoked it (Claude rejects concurrent access to a locked sessionId). The script self-detects this when `detectSessionIdDetailed` returns `source === "session-file"` with `ownerPid === process.ppid` and refuses upfront. The weaker `jsonl-mtime` fallback emits a warning and proceeds — if the new window closes silently, that's the cause.
- `cc` resume vs. self-takeover: the user must close the OLD Terminal window before the lock-bound `cc --resume` can take over; the takeover script does NOT kill the parent Claude. Manual coordination is the V1 contract.

## Prompt prefix (wrapper)

`buildPromptPrefix` in `wrapper/src/index.ts` wraps every voice prompt with three concatenated chunks (joined by `·` on a single line — the Claude Code TUI treats embedded `\n` as in-box newlines, not submit):

1. **Context note** — tells Claude he IS running on the user's macOS via pty with Bash. Without this, imperative voice commands like "abre Final Cut Pro" trigger a "I have no desktop access" refusal even though the Bash tool can run `open -a "Final Cut Pro"`. Bilingual (es/en) per the prompt language heuristic.
2. **Response format directive** — primera línea `**TL;DR:**` (≤18 palabras) + opcionalmente detalles. Only when no tools are needed.
3. **Followups directive** — ALWAYS end with `Sugerencias:` / `Followups:` + 2-3 short bullets. Parsed by `extractFollowups()` and surfaced as chips at the end of Resultado.

The prefix is appended with `PROMPT_END_MARKER` so the parser can slice off everything before Claude's actual response.

## Toolchain on this Mac

Installed via `brew --cask`: `android-studio`, `android-platform-tools` (gives `adb` standalone). JDK via `brew install openjdk@21` (formula, not cask — temurin cask needs sudo). Source `scripts/env.sh` to put everything on PATH for any shell.
