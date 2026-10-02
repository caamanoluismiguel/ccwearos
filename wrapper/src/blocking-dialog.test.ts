import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  BlockingDialogTracker,
  cleanTerminalText,
  extractBlockingDialog,
  extractPermissionPrompt,
  extractResponseLines,
} from "./parser.js";

const ESC = "\x1b";
const dialogFixture = (name: string): string =>
  readFileSync(join(__dirname, "..", "fixtures", "dialogs", name), "utf8");
const permissionFixture = (name: string): string =>
  readFileSync(join(__dirname, "..", "fixtures", "permission", name), "utf8");
// Render like the TUI: spaces as cursor-forward, rows joined by \r\n.
const tui = (...rows: string[]): string =>
  rows.map((r) => r.replace(/ /g, `${ESC}[1C`)).join("\r\n");

describe("extractBlockingDialog", () => {
  it("recognises the workspace-trust dialog and its folder", () => {
    expect(extractBlockingDialog(dialogFixture("trust.txt"))).toEqual({
      kind: "trust",
      detail: "/Users/luismiguelcaamano/projects/CCWEAROS/wrapper",
    });
  });

  it("still recognises trust when the spacing was lost (production capture)", () => {
    const squashed = [
      "Accessingworkspace:",
      "/Users/me/projects/x",
      "Quicksafetycheck:Isthisaprojectyoucreatedoroneyoutrust?",
      "❯No,exit",
      "Yes,Itrustthisfolder",
      "Entertoconfirm·Esctocancel",
    ].join("\n");
    expect(extractBlockingDialog(squashed)).toEqual({
      kind: "trust",
      detail: "/Users/me/projects/x",
    });
  });

  it("does not flag a conversation that merely mentions the trust dialog", () => {
    const text = tui(
      "⏺ That was the 'Yes, I trust this folder' prompt from Claude Code.",
      "  It appears the first time you open a folder.",
    );
    expect(extractBlockingDialog(text)).toBeNull();
  });

  it("recognises login/auth failures", () => {
    expect(
      extractBlockingDialog(tui("⎿ Invalid API key · Please run /login")),
    ).toEqual({ kind: "auth", detail: "Invalid API key · Please run /login" });
    expect(
      extractBlockingDialog(
        tui(
          '⎿ API Error: 401 {"type":"error","error":{"type":"authentication_error","message":"OAuth token has expired."}}',
        ),
      )?.kind,
    ).toBe("auth");
  });

  it("catches an unknown Enter-to-confirm box as 'other' with its title", () => {
    const text = tui(
      "─".repeat(60),
      " New MCP server found in .mcp.json: sqlite",
      "",
      " ❯ 1. Use this MCP server",
      "   2. Continue without using this MCP server",
      "",
      " Enter to confirm · Esc to cancel",
    );
    expect(extractBlockingDialog(text)).toEqual({
      kind: "other",
      detail: "New MCP server found in .mcp.json: sqlite",
    });
  });

  it("never reports a tool permission box as a blocking dialog", () => {
    for (const name of ["bash-simple.txt", "edit-synthetic.txt", "fetch.txt"]) {
      const text = permissionFixture(name);
      expect(extractPermissionPrompt(text)).not.toBeNull();
      expect(extractBlockingDialog(text)).toBeNull();
    }
    // Same box with Claude Code's "Esc to cancel" footer appended.
    const withFooter =
      permissionFixture("bash-simple.txt") +
      tui("", "Enter to confirm · Esc to cancel");
    expect(extractBlockingDialog(withFooter)).toBeNull();
  });

  it("returns null for a normal response", () => {
    const text = tui(
      "⏺ **TL;DR:** Tienes 12 carpetas en projects.",
      "",
      "Sugerencias:",
      "- Abre la más reciente",
    );
    expect(extractBlockingDialog(text)).toBeNull();
  });
});

describe("extractPermissionPrompt vs trust dialog", () => {
  it("does not treat the workspace-trust dialog as a tool permission", () => {
    expect(extractPermissionPrompt(dialogFixture("trust.txt"))).toBeNull();
  });

  // A future rewording drops every TRUST_MARKER. Its "1. Yes, …" option must
  // still never reach the legacy matchers (the watch's Allow sends "1\r").
  const reworded = tui(
    "─".repeat(60),
    " Workspace access",
    " Do you allow Claude to read and run files here?",
    "",
    " ❯ 1. Yes, continue",
    "   2. No, exit",
    "",
    " Enter to confirm · Esc to cancel",
  );

  it("reworded trust dialog: not a permission prompt", () => {
    expect(extractPermissionPrompt(reworded)).toBeNull();
  });

  it("reworded trust dialog: reported as a blocking 'other' dialog", () => {
    expect(extractBlockingDialog(reworded)).toEqual({
      kind: "other",
      detail: "Workspace access",
    });
  });

  it("Esc-only footer with no readable box: blocking, never a permission", () => {
    const text = tui(" ❯ 1. Yes", "   2. No", "", " Esc to cancel");
    expect(extractPermissionPrompt(text)).toBeNull();
    expect(extractBlockingDialog(text)?.kind).toBe("other");
  });

  it("prose that mentions 'Esc to cancel' is not dialog chrome", () => {
    const text = tui("⏺ Press Esc to cancel the run.", "❯ 1. Yes");
    expect(extractBlockingDialog(text)).toBeNull();
    expect(extractPermissionPrompt(text)).not.toBeNull();
  });
});

describe("BlockingDialogTracker", () => {
  it("finds the trust dialog across small chunks, once", () => {
    const text = dialogFixture("trust.txt");
    const t = new BlockingDialogTracker();
    const hits = [];
    for (let i = 0; i < text.length; i += 64) {
      const d = t.feed(text.slice(i, i + 64));
      if (d) hits.push(d);
    }
    expect(hits).toHaveLength(1);
    expect(hits[0]?.kind).toBe("trust");
  });
});

describe("cleanTerminalText (space restoration + control stripping)", () => {
  it("turns cursor-forward escapes into spaces", () => {
    expect(cleanTerminalText(`Hola${ESC}[1Cmundo`)).toBe("Hola mundo");
    expect(cleanTerminalText(`a${ESC}[3Cb`)).toBe("a   b");
    // Omitted count means 1 — this form used to lose the space.
    expect(cleanTerminalText(`Yes,${ESC}[CI${ESC}[Ctrust`)).toBe("Yes, I trust");
  });

  it("strips charset designators and C0 controls but keeps \\n and \\t", () => {
    expect(cleanTerminalText(`fin${ESC}(B\x0f`)).toBe("fin");
    expect(cleanTerminalText(`${ESC})0x${ESC}7y${ESC}8\x07`)).toBe("xy");
    expect(cleanTerminalText("a\tb\nc")).toBe("a\tb\nc");
  });

  it("restores the trust fixture's words", () => {
    const text = cleanTerminalText(dialogFixture("trust.txt"));
    expect(text).toContain("Yes, I trust this folder");
    expect(text).toContain("Quick safety check: Is this a project");
    expect(text).not.toMatch(/[\x00-\x08\x0b\x0c\x0e-\x1f]/);
  });
});

describe("extractResponseLines never publishes TUI chrome", () => {
  it("drops dialog chrome, option lists and trailing control junk", () => {
    const out = extractResponseLines(dialogFixture("trust.txt"));
    expect(out).not.toMatch(/Enter to confirm|Esc to cancel/);
    expect(out).not.toMatch(/❯|─|\(B|\x0f|\x1b/);
    expect(out).not.toMatch(/No, exit|I trust this folder/);
  });

  it("drops the echoed prompt prefix and box borders", () => {
    const out = extractResponseLines(
      tui(
        "> Contexto: estás corriendo en el Mac del usuario vía pty. Tienes Bash.",
        "╭──────────╮",
        "│ Hola, aquí está la lista │",
        "│          │",
        "╰──────────╯",
      ),
    );
    expect(out).toBe("Hola, aquí está la lista");
  });

  it("keeps real numbered answers", () => {
    const out = extractResponseLines(tui("1. Yes, you can restart it safely"));
    expect(out).toBe("1. Yes, you can restart it safely");
  });
});
