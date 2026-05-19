// Invoked from the `/ccwearos-takeover` slash command. The user is in a
// Claude session in their Terminal, has just decided to leave the Mac, and
// wants the watch to be the sole permission gate from this moment on.
//
// What we do:
//   1. Detect the current Claude session ID + cwd.
//   2. Soft-lock /sharedSession to kind="wrapper-pty" so the OLD Terminal's
//      PreToolUse hook bails immediately (kind !== "hook" → pass-through).
//   3. Open a new Terminal.app (or iTerm.app, by $TERM_PROGRAM) window via
//      osascript that runs `cc --resume <id>` — the wrapper-pty script
//      with --permission-mode dontAsk forced, so the watch is the sole gate.
//   4. Tell the user to close the old window; the new one is watch-owned.
//
// Why a new window (vs. relaunching inside the same Terminal):
//   - Claude Code does not expose a runtime API for changing permission mode
//     mid-session. The mode is fixed at process spawn. To switch to dontAsk
//     we have to (re)spawn `claude`. The cleanest place for the new process
//     is a fresh Terminal window — the old one keeps its scrollback intact
//     for reference and dies on user-close.
//   - Killing the user's running Claude from underneath them is invasive and
//     can corrupt session state. Better: let them close manually.
//
// Why `cc --resume` (vs. direct `claude --resume --permission-mode dontAsk`):
//   - `cc` is the wrapper-pty script we already have. It owns the pty,
//     publishes status/permissions to RTDB, writes /sharedSession, registers
//     the session scanner. We get all of that "for free" by resuming under it.
//
// Never exits non-zero (slash commands must always "succeed" so the Claude
// turn completes cleanly).

import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  appendAuditEntry,
  initFirebase,
  readSharedSession,
  setSharedSession,
} from "../../src/firebase.js";
import { buildShellCommand, pickLauncher } from "../../src/takeover-utils.js";
import type { SharedSessionMeta } from "../../src/types/schema.js";
import { detectSessionIdDetailed, isPidAlive } from "./_helpers.js";

const MODULE_DIR = dirname(fileURLToPath(import.meta.url));
const WRAPPER_ROOT = resolve(MODULE_DIR, "../..");
const SHARE_SCRIPT = join(WRAPPER_ROOT, "scripts/share.ts");
const TSX_BIN = join(WRAPPER_ROOT, "node_modules/.bin/tsx");

async function main(): Promise<void> {
  const cwd = process.cwd();
  initFirebase();

  if (!existsSync(TSX_BIN)) {
    console.log(`[ccwearos] ✗ tsx not found at ${TSX_BIN}`);
    console.log(
      "[ccwearos]   Run `cd ~/projects/CCWEAROS/wrapper && npm install` first.",
    );
    return;
  }
  if (!existsSync(SHARE_SCRIPT)) {
    console.log(`[ccwearos] ✗ share.ts not found at ${SHARE_SCRIPT}`);
    return;
  }

  // Capture existing /sharedSession BEFORE we overwrite — used to restore on
  // failure paths (osascript denied / crash between placeholder + cc claim).
  // If we just `setSharedSession(null)` on rollback, we'd silently destroy a
  // pre-existing /ccwearos (kind="hook") bridge the user had set up.
  const previousShared = await readSharedSession();
  if (
    previousShared &&
    previousShared.kind === "wrapper-pty" &&
    isPidAlive(previousShared.pid)
  ) {
    console.log(
      `[ccwearos] ✗ Otra sesión wrapper-pty ya está activa (pid=${previousShared.pid}, cwd=${previousShared.cwd}).`,
    );
    console.log(
      "[ccwearos]   Cerrá esa primero (Ctrl+C en su Terminal) y volvé a intentarlo.",
    );
    return;
  }

  const detected = detectSessionIdDetailed(cwd);
  if (!detected) {
    console.log("[ccwearos] ✗ No pude detectar el sessionId actual de Claude.");
    console.log(
      "[ccwearos]   Mirá ~/.claude/sessions/ — si está vacío, no hay nada que resumir.",
    );
    console.log(
      "[ccwearos]   Si Claude lo creó recién, esperá 1-2s y reintentá.",
    );
    return;
  }
  const sessionId = detected.sessionId;

  // Self-takeover guard: process.ppid is the Claude process that invoked this
  // slash command. If it matches the session-file's owning pid, we'd be asking
  // `claude --resume <id>` to claim a session that's already locked by the
  // caller — the new cc spawns, claims /sharedSession, then dies because
  // Claude refuses concurrent access to the same session. Result: a stale
  // wrapper-pty lock with a dead pid. Refuse upfront with a clear message.
  if (
    detected.source === "session-file" &&
    detected.ownerPid === process.ppid
  ) {
    console.log(
      "[ccwearos] ✗ Esta es la sesión que está invocando el slash command.",
    );
    console.log(
      "[ccwearos]   `claude --resume` rechaza sesiones ya bloqueadas por otro Claude.",
    );
    console.log(
      "[ccwearos]   Probá desde OTRA Terminal con `claude` corriendo (no esta).",
    );
    return;
  }
  // jsonl-mtime is the weakest provenance: it can't distinguish a dead
  // session from a live one we just didn't see in ~/.claude/sessions. Warn
  // the user — they may be about to attempt a self-takeover unknowingly.
  if (detected.source === "jsonl-mtime") {
    console.log(
      `[ccwearos] ⚠️  sessionId detectado por fallback de .jsonl mtime (sessionId=${sessionId.slice(0, 8)}…).`,
    );
    console.log(
      "[ccwearos]    Si esta sesión es la que está corriendo ahora mismo, `cc --resume` va a morir",
    );
    console.log(
      "[ccwearos]    apenas arranque (no se puede resumir una sesión ya activa). Procediendo igual —",
    );
    console.log(
      "[ccwearos]    si la nueva ventana se cierra sola, ese es el motivo.",
    );
  }

  // Soft-lock /sharedSession with kind="wrapper-pty" so the OLD Terminal's
  // PreToolUse hook bails on next fire (pre-tool-use.ts passes through when
  // kind !== "hook"). Use process.pid (this script's own pid): once we
  // process.exit at the end, isPidAlive returns false → the lock looks stale
  // to any subsequent /ccwearos-takeover attempt, which can clear it. Using
  // process.ppid (the OLD Claude) would keep the lock looking ALIVE as long
  // as the user keeps the old window open — even after a successful takeover
  // hands off to the new cc — which can block legitimate retries.
  const placeholder: SharedSessionMeta = {
    sessionId,
    pid: process.pid,
    cwd,
    startedAt: Date.now(),
    kind: "wrapper-pty",
  };
  await setSharedSession(placeholder);

  // Any failure past this point must restore the previous /sharedSession
  // (whether null or kind="hook" from a prior /ccwearos) so we don't silently
  // destroy state the user expects to be there.
  const rollback = async (): Promise<void> => {
    try {
      await setSharedSession(previousShared);
    } catch {
      // best-effort — outer caller will report the original error
    }
  };

  try {
    const shellCmd = buildShellCommand(cwd, TSX_BIN, SHARE_SCRIPT, sessionId);
    const launcher = pickLauncher(process.env["TERM_PROGRAM"]);
    // encoding: "utf8" → spawnSync's typed return narrows stderr to string,
    // but TS still widens to string | Buffer here. Cast at use site.
    const osascriptResult = spawnSync(
      "osascript",
      launcher.appleScriptArgs(shellCmd),
      {
        stdio: "pipe",
        encoding: "utf8",
        // First-time invocation triggers macOS TCC Automation prompt. We don't
        // want to hang the hook indefinitely (Claude Code's hook timeout is
        // 60s); 30s is plenty for a user paying attention.
        timeout: 30_000,
      },
    );

    if (osascriptResult.status !== 0) {
      await rollback();
      const launcherName = launcher.name;
      console.log(`[ccwearos] ✗ osascript no pudo abrir ${launcherName}.app:`);
      const stderr = String(osascriptResult.stderr ?? "").trim();
      if (stderr) console.log(`[ccwearos]   ${stderr}`);
      if (osascriptResult.signal === "SIGTERM") {
        console.log(
          "[ccwearos]   (osascript timed out — probablemente esperando aprobación de Automation)",
        );
      }
      console.log(
        "[ccwearos]   macOS puede necesitar permiso de Automation: Configuración → Privacidad → Automatización.",
      );
      console.log(
        "[ccwearos]   Probá manualmente abriendo una Terminal nueva y corriendo:",
      );
      console.log(`[ccwearos]     cd ${cwd} && cc --resume ${sessionId}`);
      return;
    }

    // Await the audit log write — pre-tool-use.ts learned the hard way that
    // `void` discard + immediate process.exit drops the RTDB write.
    await appendAuditEntry({
      ts: Date.now(),
      kind: "hook",
      tool: "(takeover)",
      args: `-> ${launcher.name}.app · session=${sessionId.slice(0, 8)}`,
      decision: "allow",
      source: "terminal",
    });

    console.log(
      `[ccwearos] ✓ Sesión migrada a una nueva ${launcher.name}.app.`,
    );
    console.log(`[ccwearos]   Resumida: sessionId=${sessionId.slice(0, 8)}…`);
    console.log(
      "[ccwearos]   Modo: permission-mode=dontAsk → el reloj es el único gate.",
    );
    console.log("");
    console.log(
      "[ccwearos] Esta Terminal queda read-only. Cerrala (Cmd+W) cuando vuelvas al Mac,",
    );
    console.log(
      "[ccwearos] o seguí leyéndola — la nueva ventana es la que el reloj controla.",
    );
  } catch (err) {
    // Anything thrown between the placeholder write and the success path
    // (spawnSync throwing on EMFILE/ENOMEM, audit-log RTDB write failing,
    // any synchronous bug) — restore the previous state so the next attempt
    // doesn't refuse with "another wrapper-pty session is alive".
    await rollback();
    console.log(`[ccwearos] error: ${(err as Error).message}`);
  }
}

main()
  .then(() => process.exit(0))
  .catch((err) => {
    console.log(`[ccwearos] error: ${(err as Error).message}`);
    process.exit(0); // never fail the slash command
  });
