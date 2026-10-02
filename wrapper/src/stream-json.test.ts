import { describe, expect, it } from "vitest";
import {
  activityForTool,
  detectRunBlocker,
  parseStreamEvent,
  StreamJsonLines,
  toolArg,
  type StreamResult,
} from "./stream-json.js";

const result = (over: Partial<StreamResult> = {}): StreamResult => ({
  text: "ok",
  isError: false,
  subtype: "success",
  numTurns: 1,
  totalCostUsd: 0,
  tokens: 0,
  contextWindow: null,
  permissionDenials: 0,
  ...over,
});

describe("StreamJsonLines", () => {
  it("joins lines split across chunks and skips non-JSON noise", () => {
    const l = new StreamJsonLines();
    expect(l.push('{"type":"sys')).toEqual([]);
    expect(l.push('tem"}\nwarning: noise\n{"a":1}\n{"b"')).toEqual([
      { type: "system" },
      { a: 1 },
    ]);
    expect(l.push(":2}")).toEqual([]);
    expect(l.flush()).toEqual([{ b: 2 }]);
  });
});

describe("parseStreamEvent", () => {
  it("ignores sub-agent text but keeps sub-agent tools", () => {
    const u = parseStreamEvent(
      {
        type: "assistant",
        parent_tool_use_id: "toolu_parent",
        message: {
          content: [
            { type: "text", text: "internal sub-agent chatter" },
            { type: "tool_use", name: "Grep", input: { pattern: "TODO" } },
          ],
        },
      },
      5,
    );
    expect(u.text).toBeUndefined();
    expect(u.tools).toEqual([{ tool: "Grep", arg: "TODO", ts: 5 }]);
  });

  it("maps rate limits and tolerates garbage", () => {
    expect(
      parseStreamEvent({
        type: "rate_limit_event",
        rate_limit_info: { resetsAt: 10, rateLimitType: "seven_day" },
      }),
    ).toEqual({ rateLimit: { kind: "weekly", resetsAt: 10 } });
    expect(parseStreamEvent(null)).toEqual({});
    expect(parseStreamEvent({ type: "assistant", message: { content: "x" } })).toEqual({});
  });
});

describe("toolArg / activityForTool", () => {
  it("keeps the command head and the END of long paths", () => {
    expect(toolArg("Bash", { command: "git status\ngit diff" })).toBe("git status");
    const long = `/Users/me/${"deep/".repeat(20)}parser.ts`;
    const arg = toolArg("Edit", { file_path: long });
    expect(arg?.length).toBeLessThanOrEqual(60);
    expect(arg?.endsWith("parser.ts")).toBe(true);
    expect(toolArg("WebSearch", { query: "clima Bogotá" })).toBe("clima Bogotá");
    expect(toolArg("Edit", {})).toBeNull();
  });

  it("Spanish activity lines", () => {
    expect(activityForTool({ tool: "Edit", arg: "/x/parser.ts", ts: 0 })).toBe("Editando parser.ts");
    expect(activityForTool({ tool: "Bash", arg: "ls", ts: 0 })).toBe("Ejecutando un comando");
    expect(activityForTool({ tool: "WebFetch", arg: null, ts: 0 })).toBe("Buscando en la web");
    expect(activityForTool({ tool: "mcp__x", arg: null, ts: 0 })).toBe("Usando mcp__x");
  });
});

describe("detectRunBlocker", () => {
  it("a successful result is never a blocker, whatever stderr says", () => {
    expect(detectRunBlocker({ result: result(), stderr: "Please run /login" })).toBeNull();
  });

  it("is_error result: auth only when the text says so", () => {
    expect(
      detectRunBlocker({ result: result({ isError: true, text: "OAuth token has expired." }), stderr: "" }),
    ).toEqual({ kind: "auth", detail: "OAuth token has expired." });
    expect(
      detectRunBlocker({ result: result({ isError: true, text: "Reached max turns" }), stderr: "" }),
    ).toBeNull();
  });

  it("no result: auth or trust from stderr, else null (crash is decided later)", () => {
    expect(detectRunBlocker({ result: null, stderr: "Invalid API key" })?.kind).toBe("auth");
    expect(
      detectRunBlocker({ result: null, stderr: "Do you trust the files in this folder? trust this folder" })
        ?.kind,
    ).toBe("trust");
    expect(detectRunBlocker({ result: null, stderr: "segfault" })).toBeNull();
  });
});
