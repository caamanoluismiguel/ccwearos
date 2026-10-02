import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  describeTool,
  intentFromText,
  LABEL_NEXT_STEP,
  LABEL_THINKING,
  ProgressPublisher,
  ProgressTracker,
  shortPath,
} from "./progress.js";
import type { RunProgress } from "./types/schema.js";

const HOME = "/Users/me";

// Stream-json event builders shaped like real `claude -p --verbose` output.
const assistant = (content: unknown[], parent: string | null = null) => ({
  type: "assistant",
  message: { role: "assistant", content },
  parent_tool_use_id: parent,
});
const toolUse = (id: string, name: string, input: Record<string, unknown>) => ({
  type: "tool_use",
  id,
  name,
  input,
});
const text = (t: string) => ({ type: "text", text: t });
const toolResult = (id: string) => ({
  type: "user",
  message: { role: "user", content: [{ type: "tool_result", tool_use_id: id, content: "ok", is_error: false }] },
  parent_tool_use_id: null,
});

describe("ProgressTracker over a realistic run", () => {
  it("thinking → Bash find → result → text → Edit → result", () => {
    const t = new ProgressTracker({ runStartedAt: 1_000, home: HOME });
    expect(t.snapshot()).toEqual({
      step: 0,
      label: LABEL_THINKING,
      stepStartedAt: 1_000,
      lastEventAt: 1_000,
      runStartedAt: 1_000,
    });

    // init event: liveness only.
    let r = t.ingest({ type: "system", subtype: "init", model: "claude-opus-4-7" }, 1_500);
    expect(r).toEqual({ changed: true, stepChanged: false });
    expect(t.snapshot()).toMatchObject({ step: 0, label: "Pensando", lastEventAt: 1_500 });

    r = t.ingest(
      assistant([
        text("Voy a buscar el último archivo en tus Descargas. Luego te lo mando."),
        toolUse("t1", "Bash", {
          command: "find ~/Downloads -maxdepth 1 -type f -print0 | xargs -0 ls -t | head -1",
        }),
      ]),
      2_000,
    );
    expect(r.stepChanged).toBe(true);
    expect(t.snapshot()).toEqual({
      step: 1,
      label: "Buscando archivos",
      detail: "~/Downloads",
      intent: "Voy a buscar el último archivo en tus Descargas.",
      stepStartedAt: 2_000,
      lastEventAt: 2_000,
      runStartedAt: 1_000,
    });

    r = t.ingest(toolResult("t1"), 90_000);
    expect(r.stepChanged).toBe(false);
    const after = t.snapshot();
    expect(after).toMatchObject({ step: 1, label: LABEL_NEXT_STEP, stepStartedAt: 2_000, lastEventAt: 90_000 });
    expect(after).not.toHaveProperty("detail");

    t.ingest(assistant([text("Encontré **factura.pdf**; ahora lo adjunto.")]), 91_000);
    expect(t.snapshot()).toMatchObject({ step: 1, intent: "Encontré factura.pdf; ahora lo adjunto." });

    r = t.ingest(
      assistant([toolUse("t2", "Edit", { file_path: "/Users/me/projects/app/src/parser.ts" })]),
      92_000,
    );
    expect(r.stepChanged).toBe(true);
    expect(t.snapshot()).toMatchObject({
      step: 2,
      label: "Editando parser.ts",
      detail: "…/src/parser.ts",
      stepStartedAt: 92_000,
    });

    t.ingest(toolResult("t2"), 93_000);
    expect(t.snapshot()).toMatchObject({ step: 2, label: LABEL_NEXT_STEP, lastEventAt: 93_000 });

    // The result event only bumps liveness.
    t.ingest({ type: "result", subtype: "success", result: "Listo" }, 94_000);
    expect(t.snapshot()).toMatchObject({ step: 2, lastEventAt: 94_000 });
  });

  it("parallel tools: still running until every result is in", () => {
    const t = new ProgressTracker({ runStartedAt: 0, home: HOME });
    t.ingest(
      assistant([
        toolUse("a", "Read", { file_path: "/Users/me/a.txt" }),
        toolUse("b", "Read", { file_path: "/Users/me/b.txt" }),
      ]),
      10,
    );
    expect(t.snapshot()).toMatchObject({ step: 2, label: "Leyendo b.txt" });
    t.ingest(toolResult("a"), 20);
    expect(t.snapshot().label).toBe("Leyendo b.txt");
    t.ingest(toolResult("b"), 30);
    expect(t.snapshot().label).toBe(LABEL_NEXT_STEP);
  });

  it("sub-agent tools count as steps but their text is not the intent", () => {
    const t = new ProgressTracker({ runStartedAt: 0, home: HOME });
    t.ingest(assistant([text("Le pido ayuda a un subagente.")]), 1);
    t.ingest(assistant([text("I will grep now."), toolUse("s1", "Grep", { pattern: "foo" })], "toolu_parent"), 2);
    expect(t.snapshot()).toMatchObject({ step: 1, label: "Buscando en archivos", intent: "Le pido ayuda a un subagente." });
  });

  it("ignores junk without throwing", () => {
    const t = new ProgressTracker({ runStartedAt: 0, home: HOME });
    for (const ev of [null, 42, "x", {}, { type: "assistant" }, { type: "user", message: {} }]) t.ingest(ev, 5);
    expect(t.snapshot()).toMatchObject({ step: 0, label: LABEL_THINKING, lastEventAt: 5 });
  });
});

describe("describeTool label/detail mapping", () => {
  const cases: [string, Record<string, unknown>, string, string | undefined][] = [
    ["Bash", { command: "find ~/Downloads -type f" }, "Buscando archivos", "~/Downloads"],
    ["Bash", { command: "ls -t '/Users/me/Library/Mobile Documents/com~apple~CloudDocs'" }, "Buscando archivos", "…/Mobile Documents/com~apple~CloudDocs"],
    ["Bash", { command: "rg TODO src/" }, "Buscando archivos", undefined],
    ["Bash", { command: "grep -r foo ./src" }, "Buscando archivos", "./src"],
    ["Bash", { command: "cat /Users/me/notes.txt" }, "Leyendo archivos", "~/notes.txt"],
    ["Bash", { command: "head -5 ~/a/b/c.log" }, "Leyendo archivos", "…/b/c.log"],
    ["Bash", { command: "sed -n 1,5p x" }, "Leyendo archivos", undefined],
    ["Bash", { command: 'mv ~/Downloads/f.pdf "/Users/me/Documents/Facturas/"' }, "Moviendo archivos", "~/Downloads/f.pdf"],
    ["Bash", { command: "cp a b" }, "Moviendo archivos", undefined],
    ["Bash", { command: "mkdir -p ~/x" }, "Moviendo archivos", "~/x"],
    ["Bash", { command: "cd ~/projects/app && git status" }, "Usando git", undefined],
    ["Bash", { command: "npm test" }, "Corriendo el proyecto", undefined],
    ["Bash", { command: "FOO=1 npx vitest run" }, "Corriendo el proyecto", undefined],
    ["Bash", { command: "node script.js" }, "Corriendo el proyecto", undefined],
    ["Bash", { command: "pnpm i" }, "Corriendo el proyecto", undefined],
    ["Bash", { command: "yarn build" }, "Corriendo el proyecto", undefined],
    ["Bash", { command: 'osascript -e \'tell application "Mail" to activate\'' }, "Usando apps del Mac", undefined],
    ["Bash", { command: 'open -a "Final Cut Pro"' }, "Usando apps del Mac", undefined],
    ["Bash", { command: "curl -s https://api.github.com/repos/x" }, "Consultando internet", "api.github.com"],
    ["Bash", { command: "wget https://www.example.com/f.zip" }, "Consultando internet", "example.com"],
    ["Bash", { command: "sudo /usr/bin/find / -name x" }, "Buscando archivos", "/"],
    ["Bash", { command: "echo hola" }, "Corriendo un comando", undefined],
    ["Bash", {}, "Corriendo un comando", undefined],
    ["Read", { file_path: "/Users/me/projects/app/README.md" }, "Leyendo README.md", "…/app/README.md"],
    ["Edit", { file_path: "/Users/me/a.ts" }, "Editando a.ts", "~/a.ts"],
    ["Write", { file_path: "/tmp/out.json" }, "Editando out.json", "/tmp/out.json"],
    ["MultiEdit", { file_path: "/Users/me/x/y/z.ts" }, "Editando z.ts", "…/y/z.ts"],
    ["Glob", { pattern: "**/*.pdf", path: "/Users/me/Downloads" }, "Buscando en archivos", "~/Downloads"],
    ["Grep", { pattern: "factura" }, "Buscando en archivos", "factura"],
    ["WebFetch", { url: "https://docs.claude.com/en/x" }, "Leyendo una página", "docs.claude.com"],
    ["WebSearch", { query: "clima Bogotá" }, "Buscando en la web", "clima Bogotá"],
    ["Task", { description: "Buscar facturas" }, "Delegando una parte", "Buscar facturas"],
    ["Agent", { description: "Revisar código" }, "Delegando una parte", "Revisar código"],
    ["mcp__claude_ai_Gmail__send_message", { to: "x" }, "Usando Gmail", undefined],
    ["mcp__github__create_issue", {}, "Usando github", undefined],
    ["TodoWrite", { todos: [] }, "Organizando el plan", undefined],
    ["SomethingNew", {}, "Usando SomethingNew", undefined],
  ];
  it.each(cases)("%s %j → %s", (name, input, label, detail) => {
    const d = describeTool(name, input, HOME);
    expect(d.label).toBe(label);
    expect(d.detail).toBe(detail);
  });

  it("caps label at 28, detail at 40, and never shows the full command", () => {
    const d = describeTool("Read", { file_path: "/Users/me/a-really-long-file-name-for-a-watch.txt" }, HOME);
    expect(d.label.length).toBeLessThanOrEqual(28);
    expect(d.label.endsWith("…")).toBe(true);
    const long = "find /Users/me/" + "deep/".repeat(20) + "folder-with-a-very-very-long-name -name '*.pdf' -exec rm {} +";
    const b = describeTool("Bash", { command: long }, HOME);
    expect(b.detail!.length).toBeLessThanOrEqual(40);
    expect(b.detail).not.toContain("-exec");
    const q = describeTool("WebSearch", { query: "x".repeat(100) }, HOME);
    expect(q.detail!.length).toBe(40);
  });

  it("shortPath keeps short paths and collapses long ones", () => {
    expect(shortPath("/Users/me", HOME)).toBe("~");
    expect(shortPath("/Users/me/Downloads/", HOME)).toBe("~/Downloads");
    expect(shortPath("/Users/me/Library/Mobile Documents", HOME)).toBe("~/Library/Mobile Documents");
    expect(shortPath("/etc/hosts", HOME)).toBe("/etc/hosts");
    expect(shortPath("/", HOME)).toBe("/");
  });
});

describe("intentFromText", () => {
  it("first sentence, markdown stripped, ≤90", () => {
    expect(intentFromText("## Voy a **buscar** en `~/Downloads`. Después te aviso.")).toBe(
      "Voy a buscar en ~/Downloads.",
    );
    expect(intentFromText("Revisando la carpeta:")).toBe("Revisando la carpeta");
    const long = intentFromText("a".repeat(200));
    expect(long!.length).toBe(90);
  });
  it("skips prompt-prefix echoes and TL;DR lines, stops at followups", () => {
    expect(intentFromText("Contexto: estás corriendo en el Mac\n**TL;DR:** Listo\nYa lo envié a tu correo.")).toBe(
      "Ya lo envié a tu correo.",
    );
    expect(intentFromText("TL;DR: hecho")).toBeNull();
    expect(intentFromText("Sugerencias:\n- Abrir correo")).toBeNull();
    expect(intentFromText("   \n")).toBeNull();
  });
});

describe("ProgressPublisher cadence", () => {
  let now = 0;
  let writes: RunProgress[] = [];
  let tracker: ProgressTracker;
  let pub: ProgressPublisher;

  beforeEach(() => {
    vi.useFakeTimers();
    now = 10_000;
    writes = [];
    tracker = new ProgressTracker({ runStartedAt: now, home: HOME });
    pub = new ProgressPublisher(tracker, (p) => writes.push(p), { now: () => now });
  });
  afterEach(() => {
    pub.stop();
    vi.useRealTimers();
  });
  const tick = async (ms: number): Promise<void> => {
    now += ms;
    await vi.advanceTimersByTimeAsync(ms);
  };

  it("writes step 0 at start, then step changes immediately", async () => {
    pub.start();
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ step: 0, label: "Pensando" });
    await tick(100);
    pub.ingest(assistant([toolUse("t1", "Bash", { command: "find ~/Downloads" })]));
    expect(writes).toHaveLength(2);
    await tick(100);
    pub.ingest(assistant([toolUse("t2", "Read", { file_path: "/Users/me/a.txt" })]));
    expect(writes).toHaveLength(3);
    expect(writes.map((w) => w.step)).toEqual([0, 1, 2]);
  });

  it("throttles ordinary updates to 1/s with a trailing write", async () => {
    pub.start();
    for (let i = 0; i < 20; i++) {
      await tick(40);
      pub.ingest({ type: "system", subtype: "status" });
    }
    // 800ms in: only the start write so far, one trailing write pending.
    expect(writes).toHaveLength(1);
    await tick(200);
    expect(writes).toHaveLength(2);
    expect(writes[1]!.lastEventAt).toBe(10_800);
    await tick(5_000);
    expect(writes).toHaveLength(2); // no events, no write (until liveness)
  });

  it("liveness: re-writes every 10s without faking lastEventAt", async () => {
    pub.start();
    pub.ingest(assistant([toolUse("t1", "Bash", { command: "find ~ -name '*.pdf'" })]));
    expect(writes).toHaveLength(2);
    await tick(10_000);
    expect(writes).toHaveLength(3);
    await tick(10_000);
    expect(writes).toHaveLength(4);
    for (const w of writes.slice(1)) expect(w.lastEventAt).toBe(10_000);
  });

  it("stop(): no writes afterwards, pending trailing write dropped", async () => {
    pub.start();
    await tick(100);
    pub.ingest({ type: "system" });
    pub.stop();
    await tick(30_000);
    pub.ingest(assistant([toolUse("t1", "Bash", { command: "ls" })]));
    expect(writes).toHaveLength(1);
  });
});
