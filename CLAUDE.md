# CCWEAROS — Wear OS Two-Way AI Controller

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
| `/command`          | watch      | wrapper          | `{text, issuedAt}` — permission responses (`"1\r"` / `""`)                                                                                                                                              |
| `/prompt`           | watch      | wrapper (daemon) | `{text, issuedAt}` — new voice prompt to run                                                                                                                                                            |
| `/permissionPrompt` | wrapper    | watch            | Human-readable prompt text                                                                                                                                                                              |
| `/activity`         | wrapper    | watch            | Spinner verb ("Crunching…", "Worked for 33s")                                                                                                                                                           |
| `/task`             | wrapper    | watch            | Current task description (from OSC title)                                                                                                                                                               |
| `/response`         | wrapper    | watch            | Last ~1.5KB of Claude's response (markdown)                                                                                                                                                             |
| `/headline`         | wrapper    | watch            | TL;DR one-liner extracted from response (info runs)                                                                                                                                                     |
| `/followups`        | wrapper    | watch            | 2-3 contextual chips Claude suggested at end of response (Page 4)                                                                                                                                       |
| `/taskKind`         | wrapper    | watch            | `"action" \| "info"` — drives Page 3 layout branch                                                                                                                                                      |
| `/toolEvents`       | wrapper    | watch            | Up to 12 tool invocations observed during the run                                                                                                                                                       |
| `/claudeStatus`     | wrapper    | watch            | Parsed Claude status line: model, contextSize, monthlyCost, reset times                                                                                                                                 |
| `/sharedSession`    | wrapper    | watch            | Active `cc`/share.ts session metadata. Gates voice prompts + Page 0 CTA.                                                                                                                                |
| `/recentSessions`   | wrapper    | watch            | Mac-wide Claude session snapshot. Drives Page 5 grouped-by-project list.                                                                                                                                |
| `/auditLog`         | wrapper    | (CLI only)       | Rolling 20-entry log of every permission decision; viewable via `scripts/audit.ts`. Written via `ref.transaction()` so voice + cc + hook writes don't lose entries.                                     |
| `/claimRequest`     | watch      | wrapper (daemon) | `{sessionId, cwd, issuedAt}` — Sprint 4n. Watch writes when user taps a session row on Page 5 and confirms. Daemon's `watchClaimRequest` handler validates + spawns `cc --resume <id>` in new Terminal. |
| `/claimResult`      | wrapper    | watch            | `{ok, reason?, sessionId, ts}` — daemon's response to the most recent claim. Watch shows banner; auto-dismisses after 4s.                                                                               |
| `/fcmToken`         | watch      | wrapper          | Watch's FCM registration token (for wake-ups)                                                                                                                                                           |

Both `/command` and `/prompt` use Firebase `ServerValue.TIMESTAMP` for `issuedAt` to avoid clock-skew bugs on emulators.

**Server-side crash cleanup.** On startup every wrapper entry point (daemon, `npm start`, `cc`) registers `onDisconnect()` handlers via `registerCrashCleanup()` in `src/firebase.ts`. Firebase clears `/status`, `/permissionPrompt`, `/activity`, `/task`, `/headline`, `/taskKind`, `/toolEvents`, `/followups`, `/command` (and `/sharedSession` for `cc`) the moment our TCP connection drops — the only mechanism that survives `kill -9`, OOM, or Mac sleep. On clean shutdown we explicitly `clearCrashCleanup()` to avoid racing ourselves.

## ¿Cuál usar: `cc`, `/ccwearos`, o `/ccwearos-takeover`?

Cuatro formas de que el reloj reciba permission prompts. Resumen rápido:

| Si quieres...                                                                       | Usá                                | Por qué                                                                                                                                                                                                                                  |
| ----------------------------------------------------------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Empezar nueva sesión y poder irte del Mac**                                       | `cc` (alias) en cualquier Terminal | Wrapper es dueño del pty desde el arranque. Tap watch = autoriza directo, sin Terminal prompt extra.                                                                                                                                     |
| **Estoy en una sesión activa y me quiero ir AHORA mismo del Mac**                   | `/ccwearos-takeover` slash         | Abre nueva Terminal con `cc --resume <id> --permission-mode dontAsk`. La sesión continúa intacta en la ventana nueva; el reloj decide solo. La vieja Terminal queda read-only — cerrala cuando vuelvas. **Path canónico para "me voy".** |
| **Monitorear desde el reloj sin perder la Terminal actual** (acepto double-confirm) | `/ccwearos` slash                  | Hook ya instalado en `~/.claude/settings.json`. Si tu `defaultMode` no es `dontAsk`, vivirás un double-confirm; `enable-share.ts` te avisa.                                                                                              |
| **Preguntar algo nuevo por voz desde el reloj**                                     | Page 0 botón "ask claude"          | Daemon spawn `claude -p` para esa pregunta.                                                                                                                                                                                              |

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

## Sprint Tracker

- [x] Sprint 0 — Scaffold
- [x] Sprint 1 — Connection (verified end-to-end)
- [x] Sprint 2 — Read (tokens, activity, task, claude status line via stream-json)
- [x] Sprint 3 — Write (permission Allow/Deny, voice prompts)
- [x] Sprint 4a — Apple-minimalist UI, haptics, mascot, inline markdown
- [x] Sprint 4b — Daemon mode + LaunchAgent
- [x] Sprint 4c — RTDB rules pinned to watch UIDs
- [x] Sprint 4d — FCM wake-up (code shipped; tested on real watch when deployed)
- [x] Sprint 4e — Deployed to physical Galaxy Watch 8 (real watch UID pinned in `firebase-rules.json`; user must publish rules in Firebase Console)
- [x] Sprint 4f — Page 3 response cleanup (Camino B): marker-slice extraction + ANSI hardening + post-answer noise filters
- [x] Sprint 4g — Page 4 "¿Y ahora qué?" + dual-label CTA (Camino C-bis): Claude-suggested follow-up chips + explicit reset button + per-app-session freshness flag
- [x] Sprint 4h — Polish round (real-watch testing): hide stale `/task` on Page 0 when wrapper is IDLE, filter Claude's meta-formatting OSC titles ("Setup response template format" etc.), and add macOS-context note to prompt prefix so Claude actually runs `open -a <app>` instead of refusing with "I have no desktop access"
- [x] Sprint 4i — Page 5 "Sesiones del Mac" + shared sessions (Camino D): wrapper publishes `/recentSessions` (scan of `~/.claude/sessions` + `~/.claude/projects`) every 15s; new `cc` alias script (`scripts/share.ts`) lets the user start a Claude session from any Terminal directory while the watch sees state and can answer permissions; daemon refuses voice prompts while `/sharedSession` is alive; Page 5 lists sessions grouped by project (read-only V1)
- [x] Sprint 4j — `/ccwearos` slash command + PreToolUse hook (Camino E-2): mid-session handoff for Claude Code sessions already running in any Terminal. User runs `/ccwearos` to mark the current session as bridged; from then on every tool call goes through a PreToolUse hook that publishes the pending tool to `/permissionPrompt` and blocks waiting on `/command` (Allow/Deny from the watch). Terminal stays alive throughout. Installer at `wrapper/scripts/install-hooks.ts` (re-runnable). Daemon yields `/command` ownership when `sharedSession.kind === "hook"`.
- [x] Sprint 4k — Tier 1 post-audit: `✗ detener` cancel button on Page 0 (SIGINT via `/command`), bilingual fallback chips on Page 3 when Claude omits `Followups:`, `defaultMode` warning in `enable-share.ts` for `acceptEdits` users, rolling `/auditLog` (20 entries) of every permission decision viewable via `scripts/audit.ts`, and `cc` vs `/ccwearos` canonical docs.
- [x] Sprint 4l — `/ccwearos-takeover` + crash-cleanup foundation + audit-driven hardening.
  - **Takeover slash command:** spawns a new Terminal window (Terminal.app, or iTerm.app per `$TERM_PROGRAM`, via `osascript`) running `cc --resume <id> --permission-mode dontAsk`. Old Terminal becomes read-only; watch is the sole permission gate in the new one. Solves the `acceptEdits` double-confirm trap.
  - **Pure utility modules** (testable in isolation): `src/sh-escape.ts` (POSIX single-quote, used by claude-runner + takeover), `src/share-args.ts` (--resume parser with strict hex-dash regex), `src/takeover-utils.ts` (aplEscape + pickLauncher + buildShellCommand), `src/pid-utils.ts` (hardened isPidAlive with `pid > 1` + integer guard). Total: 72 passing tests (+ 38 net new since Sprint 4k).
  - **Crash-cleanup foundation:** `firebase.ts:registerCrashCleanup()` uses Firebase server-side `onDisconnect()` so RTDB clears UI surfaces when wrapper TCP drops — the only mechanism that survives `kill -9`. `firebase.ts:clearStaleState(finalStatus)` atomic 12-path `update()` replaces every "set OFFLINE and pray" shutdown. Both daemon + `cc` + interactive now register on startup and clean on shutdown.
  - **Hook robustness** (`pre-tool-use.ts`): SIGTERM / SIGINT / uncaughtException handlers run cleanup before exit so a host-killed hook no longer leaves `/permissionPrompt` set forever. `pollForCommand` checks `cmd.issuedAt` against `pollStartedAt` to ignore stale entries. `appendAuditEntry` calls before every `process.exit` are now `await`-ed (was being dropped).
  - **Audit-log race fix:** `appendAuditEntry` now uses `ref.transaction()` instead of read-modify-write, so voice + cc + hook concurrent writes don't lose entries.
  - **Daemon yields for `wrapper-pty` too** (mirrors existing `kind === "hook"` yield): closes a race where daemon's `/command` listener would clear cc's permission tap before cc's own listener saw it.
  - **Watch fixes:** `PermissionScreen` haptic debounce (120ms) + skip-on-null (no phantom buzz on Firebase reconnect); `BackHandler` swallows accidental swipe-back; `StopButton` long-press → `forceResetUi()` writes IDLE/null directly to RTDB (recovery when wrapper is dead and SIGINT goes nowhere); FollowupChip + reset button bumped from 38/40dp to 48dp (Wear OS spec).
  - **Security hardening in takeover:** `aplEscape` scrubs control chars (`\x00-\x1f`, `\x7f`, U+2028, U+2029) — closes AppleScript-injection-via-cwd vector. `enable-takeover.ts` wraps post-placeholder block in `try/catch` that restores `previousShared` on any failure. Switched placeholder pid from `process.ppid` to `process.pid` so the soft-lock self-releases on script exit. 30s timeout on osascript.
- [x] Sprint 4m — Galaxy Watch foreground service + onDisconnect race fix.
  - **Watch foreground service** (`CcwearosForegroundService`, `foregroundServiceType="dataSync"`): Samsung Freecess was freezing the app ~10s after screen-off, killing the Firebase listener and leaving the watch stuck on phantom "wrapper not reachable" on every wake. Samsung's own developer docs (2026-04-23) confirm a foreground service is the only supported way to opt out of that freeze. Service does minimal work: `keepSynced(true)` on `/status` + `/sharedSession` + `/permissionPrompt` so Firebase keeps the local cache hot even without UI collectors. Shows a low-importance ongoing notification "📟 CCWEAROS · conectado al wrapper" — non-dismissable per OS contract, but discreet (LOW priority channel, no sound / vibe / badge).
  - **`CcwearosApplication`** added: enables Firebase RTDB `setPersistenceEnabled(true)` (disk cache survives process death → wake shows real state instantly) and creates the foreground notification channel BEFORE any service tries to use it.
  - **`SharingStarted.Eagerly`** on the three routing-critical StateFlows (status / sharedSession / permissionPrompt) — combined with the foreground service keeping the process alive, the listener literally never disconnects. The other 9 flows stay on `WhileSubscribed(5_000)` (battery-friendly; stale on wake is fine for non-routing surfaces).
  - **Wrapper crash-cleanup race fix.** Bug observed 2026-05-19: daemon shutdown writes IDLE, then `clearCrashCleanup()` sends an "onDisconnect cancel" to Firebase, then `process.exit()` closes the TCP — but the cancel hadn't been ACK'd by the server, so the (uncanceled) onDisconnect fired AFTER and overwrote with OFFLINE. New daemon restarted and wrote IDLE → racing onDisconnect fired AFTER again → /status stuck on OFFLINE for a healthy daemon. Fix: 250ms grace delay after `clearCrashCleanup()` before `process.exit()` so the cancel reaches the server. Belt-and-suspenders: every wrapper entry point also re-asserts `setStatus("IDLE")` 8s after startup to overwrite any racing stale onDisconnect from a previous daemon that was kill -9'd (cleanup never ran).
  - **Dead-end discovery:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is stubbed on Wear OS — the Settings activity that handles the intent on Android phones is replaced with `FakeSettingsActivity` on watches that silently no-ops. Recorded in Hard rules so we don't try this path again.
- [x] Sprint 4n — Tap-to-claim en Page 5: la sesión deja de ser read-only.
  - **Watch flow:** tap en una row de Page 5 que no esté `active` ni sea la `shared` actual → `ConfirmClaimDialog` overlay (mismo patrón visual que `PermissionScreen` — BackHandler, pill buttons 48dp, 120ms debounced haptic) → user tap "resumir" → `vm.confirmClaim()` escribe `/claimRequest {sessionId, cwd, issuedAt: ServerValue.TIMESTAMP}`. Tap "cancelar" o swipe-back → dialog se cierra sin escribir nada.
  - **Daemon flow:** `watchClaimRequest` listener consume el path. `handleClaimRequest` (en `src/claim-handler.ts`, pure-ish e inyectable) corre el pipeline de validación: regex sessionId (defense in depth), `issuedAt` age vs `commandMaxAgeSeconds`, `cwd` non-empty, `claimBusy` single-flight gate, `/sharedSession.kind === "wrapper-pty" && isPidAlive` block. Si todo pasa → spawn osascript reusando `buildShellCommand` + `pickLauncher` de Sprint 4l con `--permission-mode dontAsk` (watch es el sole gate). Resultado en `/claimResult {ok, reason?, sessionId, ts}`.
  - **Watch feedback:** `ClaimResultBanner` se renderea cuando `claimResult` es fresco (<10s). Verde + "✓ sesión abierta en tu Mac" en éxito, rojo + reason en fallo. Auto-dismiss vía `LaunchedEffect` con `delay(4000)`. Banner sits above DashboardScreen pero por debajo de `ConfirmClaimDialog` para que un nuevo confirm tape la banner anterior.
  - **Tests:** 14 nuevos casos en `src/claim-handler.test.ts` cubriendo: happy path, malformed sessionId (shell-meta + too short), empty cwd, stale claim drop, sharedSession-locked refusal, busy gate, osascript non-zero, SIGTERM timeout, busy-flag release on success/failure, cwd forwarded correctly. Total: 86/86 tests passing.
  - **Schema:** `PendingClaim` y `ClaimResult` en `wrapper/src/types/schema.ts` + `/claimRequest` y `/claimResult` paths. Watch mirror en `watch/.../data/RtdbModels.kt`. `SESSION_ID_RE` ahora exportado desde `share-args.ts` (compartido entre takeover CLI y daemon handler).
  - **Audit log:** cada claim escribe entry `{kind: "voice", tool: "(watch-claim)", args: "-> <launcher>.app · session=...", decision: allow|deny, source: "watch"}` — reusa el `AuditEntry.kind = "voice"` union sin schema-bump.
  - **Hotfix (2026-05-20) — scanner cwd vs resume cwd.** El scanner publicaba `RecentSession.cwd = meta.cwd ?? unsanitizeCwd(c.dirName)` — prefería el `cwd` extraído de los turnos del `.jsonl`. Claude Code usa el cwd del PRIMER turno como ruta de storage del archivo. Si el usuario hizo `cd` mid-session, `meta.cwd` (último turno observado) divergía del storage dir, y `cc --resume <id>` ejecutado en `meta.cwd` abortaba con "No conversation found with session ID". Fix en `src/sessions-scanner.ts`: `cwd` siempre desde `unsanitizeCwd(c.dirName)` (la ruta donde `claude --resume` busca); `projectName` derivado de `basename(meta.cwd ?? cwd)` (display amigable mantenido). Bug observado con sesión `926bea49` storada bajo `~/` pero con turnos cd-eados a `~/projects/isthmus-norte`.
  - **Firebase rules:** nuevas entradas para `/claimRequest` (watch-write con shape validation `{sessionId, cwd, issuedAt}`) y `/claimResult` (watch-write SOLO para nullear post-banner — el daemon es el único que escribe payloads). Sin estas, el watch escribe en silencio y el daemon nunca ve la request. Push vía Console o REST endpoint (`/.settings/rules.json`) usando el service account del wrapper — el script ad-hoc strippea las keys `_comment_*` que Firebase rechaza.
- [x] Sprint 4o — Runtime heartbeat (`src/index.ts:runDaemon`). El defensive 8s setTimeout de Sprint 4m solo cubre el race del restart; no cubre **TCP blips runtime** — observado 3 veces en 2 días. Cuando el WiFi del Mac titubea por 5+s, el server-side `onDisconnect` dispara OFFLINE; el Firebase SDK reconecta automático pero el daemon no re-asserts IDLE. Fix: `setInterval(30s)` corre `db().ref("/status").transaction(c => c === "OFFLINE" \|\| c === null ? "IDLE" : undefined)`. Transaction obligatorio — un `setStatus` plano racearía contra escrituras concurrentes "RUNNING" desde el voice handler. Guards: skipea si `busy === true` (voice run activo) o `sharedSession !== null` (cc/hook owns status). `clearInterval(heartbeat)` corre PRIMERO en shutdown — antes de `clearStaleState("OFFLINE")` — para evitar que el tick re-asserte IDLE encima del OFFLINE de salida. `setInterval(...).unref()` así no bloquea el exit del proceso.

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
npm test                                 # 72/72 vitest unit tests
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

| Device                     | UID                            |
| -------------------------- | ------------------------------ |
| Wear OS emulator (current) | `EMULATOR_UID_HERE` |
| Real Galaxy Watch 8        | `REAL_WATCH_UID_HERE` |

After adding a new UID to rules, paste the JSON into Firebase Console → Realtime Database → Rules → Publish.

## Hard rules

- Wrapper is ESM TypeScript strict (`noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`)
- Never commit `secrets/`, `.env`, or `google-services.json`
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

Voice prompts auto-continue across runs: the daemon tracks `hasPriorSession` in memory and passes `--continue` to `claude` on every prompt after the first, unless the user's voice text matches a `RESET_PHRASES` entry ("nueva conversación", "olvida todo", `/new`, etc. — see `wrapper/src/index.ts`).

Page 0's CTA reflects this:

- Cold app open / post-reset → **"ask claude"** (sentInSession=false in the watch ViewModel)
- After a normal `sendPrompt` round-trip → **"continuar"** (sentInSession=true)

Page 4 surfaces 2-3 contextual chips that Claude itself generates at the end of every textual answer (the `Sugerencias:` / `Followups:` bullet block, parsed by `extractFollowups`). Tap a chip = sends that exact text as the next prompt; wrapper continues the thread.

The explicit reset path is the `↻ nueva conversación` button on Page 4 — the watch's `askWithReset()` prepends "nueva conversación, " before writing `/prompt`, which trips the wrapper's `isResetPrompt` detection.

## Shared sessions + Page 5 (Camino D)

Two ways the watch sees Mac sessions:

1. **Wrapper-bridged via `cc` alias** (you don't lose your Terminal). Install the alias:

   ```bash
   echo "alias cc='npx tsx ~/projects/CCWEAROS/wrapper/scripts/share.ts'" >> ~/.zshrc
   source ~/.zshrc
   ```

   Then in any directory, type `cc` instead of `claude`. The wrapper script spawns Claude in a pty, mirrors output to your Terminal AND to RTDB. The watch sees `/status`, `/permissionPrompt`, `/response` etc. live, and Allow/Deny taps route back into the pty via `/command`. Your Terminal stays alive; come back to your Mac and the conversation is right where you left it.

   While `cc` is running, `/sharedSession` is non-null. The daemon refuses voice prompts (Page 0's CTA hides behind a "📟 sesión compartida" block) to avoid two pty's clobbering RTDB. On clean exit (Ctrl+C / `/exit`) `/sharedSession` is cleared.

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

   While `kind="hook"` is active, the daemon's `watchCommands` handler YIELDS — it sees the watch's `/command` write but doesn't consume it, so the hook gets the reply. Voice prompts (Page 1 of the watch) are still gated off.

   Watch's Page 0 SharedSessionBlock text differentiates the two kinds:
   - `kind="wrapper-pty"` (cc / takeover) → "📟 sesión compartida · activa en tu Mac · cc"
   - `kind="hook"` (/ccwearos) → "📟 puente activo · permisos vienen al reloj"

4. **Auto-handoff via `/ccwearos-takeover` (Camino E-3).** When you're mid-session and decide to leave the Mac: this slash command opens a **new Terminal window** (Terminal.app, or iTerm.app per `$TERM_PROGRAM`) running `cc --resume <sessionId> --permission-mode dontAsk`. The original session is resumed under wrapper-pty control with the watch as the sole permission gate (no Terminal double-confirm). The OLD window is left read-only — you can close it whenever.

   The slash command runs `wrapper/scripts/hooks/enable-takeover.ts`, which:
   - Detects current `sessionId` via `_helpers.detectSessionId` (refuses if it can't pin one down).
   - Refuses if another `wrapper-pty` session is alive.
   - Soft-locks `/sharedSession.kind="wrapper-pty"` immediately so the OLD Terminal's `PreToolUse` hook bails on next fire (`kind !== "hook"` → pass-through).
   - Spawns the new window via `osascript` (`-e ...` style, escaped through `shSingleQuote` + `aplEscape` helpers in `src/takeover-utils.ts`).
   - Logs an audit entry (`kind: "hook"`, `tool: "(takeover)"`).

   If `osascript` fails (e.g., macOS Automation permissions not granted), the placeholder is rolled back and a manual fallback (`cd <cwd> && cc --resume <id>`) is printed.

## Watch UI affordances

- **Page 0 — Command.** Shows brand + status + live activity. Bottom slot:
  - `IDLE` + no shared session → `AskRow` (voice input button).
  - `RUNNING` → `StopButton`. **Tap = SIGINT** (sends `` via `/command`; wrapper kills runner). **Long-press ≥500ms = force-reset** (writes `IDLE` / `null` directly to RTDB via `forceResetUi`). The long-press is the recovery affordance when the wrapper is dead and SIGINT goes nowhere.
  - `AWAITING_PERMISSION` → routes to `PermissionScreen` overlay.
  - `OFFLINE` → routes to `OfflineScreen`.
  - `sharedSession != null` → `SharedSessionBlock` (text differs by `kind`).
- **Page 1 — Metrics.** Token totals + Claude status line (model, contextSize, monthlyCost, resets).
- **Page 2 — Response** (only when `hasResult`). Branches on `taskKind`: `action` → ✓/✗ confirmation card + tool breadcrumbs; `info` → scrollable markdown + TL;DR headline.
- **Page 3 — Followups** (only when `hasResult`). Tappable chips from Claude's `Sugerencias:` / `Followups:` block, with bilingual hardcoded fallback ("Más detalles" / "Otra cosa" / "Deshacer") when Claude omits them. Reset button at bottom.
- **Page 4 / Sessions** (only when `recentSessions` non-empty). Grouped by project. Coral dot = `cc`-shared; green dot = active; dim = historical. Read-only in V1.
- **PermissionScreen overlay.** Big pill buttons (`allow` green, `deny` red). 120ms haptic debounce so reconnect bursts don't double-buzz. `BackHandler` swallows accidental swipe-back — modal must be explicitly answered.

## Known limitations

- Markdown rendering on watch is inline only (bold/italic/code). Block markdown (lists, headings, code blocks) renders as plain text.
- Tables are flattened to `cell1 · cell2 · cell3` rows — multi-line table cells lose column association.
- Action runs (tool-heavy) often skip the `Followups:` block — Page 4 falls back to bilingual hardcoded chips ("Más detalles" / "Otra cosa" / "Deshacer") via the Sprint 4k fallback path. Real Claude-generated chips are preferred when present.
- ~~Phantom "wrapper not reachable" on wake~~ — **resolved in Sprint 4m** by the foreground service (process stays alive across screen-off) + `SharingStarted.Eagerly` on routing flows (listener never disconnects) + Firebase disk persistence (cold start hits cache before network). The trade-off is a persistent ongoing notification in the watch's panel and ~2-3%/day extra battery from the always-on listener; both judged worthwhile.
- `/ccwearos-takeover` cannot resume the SAME session that invoked it (Claude rejects concurrent access to a locked sessionId). The script self-detects this when `detectSessionIdDetailed` returns `source === "session-file"` with `ownerPid === process.ppid` and refuses upfront. The weaker `jsonl-mtime` fallback emits a warning and proceeds — if the new window closes silently, that's the cause.
- `cc` resume vs. self-takeover: the user must close the OLD Terminal window before the lock-bound `cc --resume` can take over; the takeover script does NOT kill the parent Claude. Manual coordination is the V1 contract.
- `sentInSession` lives only in the watch ViewModel — Android process kill resets it to `false` mid-conversation, so the Page 0 button reads "ask claude" even though the daemon will silently `--continue`. Cosmetic mismatch; cleanest fix is `/conversationActive` in RTDB (Tier 2).

## Prompt prefix (wrapper)

`buildPromptPrefix` in `wrapper/src/index.ts` wraps every voice prompt with three concatenated chunks (joined by `·` on a single line — the Claude Code TUI treats embedded `\n` as in-box newlines, not submit):

1. **Context note** — tells Claude he IS running on the user's macOS via pty with Bash. Without this, imperative voice commands like "abre Final Cut Pro" trigger a "I have no desktop access" refusal even though the Bash tool can run `open -a "Final Cut Pro"`. Bilingual (es/en) per the prompt language heuristic.
2. **Response format directive** — primera línea `**TL;DR:**` (≤18 palabras) + opcionalmente detalles. Only when no tools are needed.
3. **Followups directive** — ALWAYS end with `Sugerencias:` / `Followups:` + 2-3 short bullets. Parsed by `extractFollowups()` and surfaced as Page 4 chips.

The prefix is appended with `PROMPT_END_MARKER` so the parser can slice off everything before Claude's actual response.

## Toolchain on this Mac

Installed via `brew --cask`: `android-studio`, `android-platform-tools` (gives `adb` standalone). JDK via `brew install openjdk@21` (formula, not cask — temurin cask needs sudo). Source `scripts/env.sh` to put everything on PATH for any shell.
