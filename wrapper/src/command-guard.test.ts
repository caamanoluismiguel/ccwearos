import { describe, expect, it } from "vitest";
import { checkCommand, isAllowedCommandText, STOP } from "./command-guard.js";

describe("isAllowedCommandText", () => {
  it("accepts only permission answers and stop", () => {
    for (const t of ["1\r", "2\r", "", "\x1b", "\x03"]) expect(isAllowedCommandText(t)).toBe(true);
  });
  it("rejects anything that could drive the session", () => {
    for (const t of ["!rm -rf ~\r", "1", "1\r\r", "hola\r", "\x1b[A", 1, null, undefined])
      expect(isAllowedCommandText(t)).toBe(false);
  });
});

describe("checkCommand", () => {
  it("accepts stop without a prompt", () => {
    expect(checkCommand({ text: STOP }, null)).toEqual({ ok: true, kind: "stop" });
  });
  it("accepts an answer whose promptId matches the active prompt", () => {
    expect(checkCommand({ text: "1\r", promptId: "abc" }, "abc")).toEqual({ ok: true, kind: "answer" });
  });
  it("drops an answer when no prompt is active", () => {
    expect(checkCommand({ text: "1\r", promptId: "abc" }, null)).toEqual({ ok: false, reason: "no-active-prompt" });
  });
  it("drops an answer for a different or missing prompt id", () => {
    expect(checkCommand({ text: "1\r", promptId: "old" }, "new")).toEqual({ ok: false, reason: "prompt-mismatch" });
    expect(checkCommand({ text: "" }, "new")).toEqual({ ok: false, reason: "prompt-mismatch" });
  });
  it("drops non-allowlisted text even with a matching id", () => {
    expect(checkCommand({ text: "!ls\r", promptId: "x" }, "x")).toEqual({ ok: false, reason: "not-allowed" });
  });
});
