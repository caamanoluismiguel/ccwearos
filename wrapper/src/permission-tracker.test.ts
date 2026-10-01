import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  extractPermissionPrompt,
  PERMISSION_DETAILS_UNAVAILABLE,
  PermissionPromptTracker,
} from "./parser.js";

const fixture = (name: string): string =>
  readFileSync(join(__dirname, "..", "fixtures", "permission", name), "utf8");

function feedInChunks(t: PermissionPromptTracker, text: string, size: number): string[] {
  const out: string[] = [];
  for (let i = 0; i < text.length; i += size) {
    const p = t.feed(text.slice(i, i + size));
    if (p) out.push(p);
  }
  return out;
}

describe("PermissionPromptTracker", () => {
  for (const name of ["bash-simple.txt", "bash-wrapped.txt", "fetch.txt", "web-search.txt"]) {
    it(`recovers the full prompt from 256-char chunks (${name})`, () => {
      const text = fixture(name);
      const whole = extractPermissionPrompt(text);
      expect(whole).not.toBeNull();
      const emitted = feedInChunks(new PermissionPromptTracker(), text, 256);
      expect(emitted.at(-1)).toBe(whole);
      expect(emitted.at(-1)).not.toBe(PERMISSION_DETAILS_UNAVAILABLE);
    });
  }

  it("emits a prompt once even when the box is redrawn", () => {
    const text = fixture("bash-simple.txt");
    const t = new PermissionPromptTracker();
    const first = feedInChunks(t, text, 256);
    const second = feedInChunks(t, text, 256);
    expect(first.length).toBeGreaterThan(0);
    expect(second.filter((p) => p === first.at(-1))).toHaveLength(0);
  });

  it("emits the same prompt again after reset (a new, identical request)", () => {
    const text = fixture("bash-simple.txt");
    const t = new PermissionPromptTracker();
    const first = feedInChunks(t, text, 256).at(-1);
    t.reset();
    expect(feedInChunks(t, text, 256).at(-1)).toBe(first);
  });

  it("ignores screens without a permission box", () => {
    expect(feedInChunks(new PermissionPromptTracker(), fixture("no-permission-status.txt"), 256)).toEqual([]);
  });
});
