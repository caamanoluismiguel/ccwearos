import { describe, expect, it } from "vitest";
import { __internal } from "./sessions-scanner.js";

const { extractUserText, unsanitizeCwd } = __internal;

describe("extractUserText", () => {
  it("returns trimmed content when message.content is a plain string", () => {
    const entry = {
      type: "user",
      message: { role: "user", content: "  qué hora es  " },
    };
    expect(extractUserText(entry)).toBe("qué hora es");
  });

  it("returns the first text block when content is a typed-block array", () => {
    const entry = {
      type: "user",
      message: {
        role: "user",
        content: [
          { type: "tool_result", tool_use_id: "x", content: "noise" },
          { type: "text", text: "real user message" },
        ],
      },
    };
    expect(extractUserText(entry)).toBe("real user message");
  });

  it("returns null for non-user entries", () => {
    expect(
      extractUserText({ type: "assistant", message: { content: "hi" } }),
    ).toBeNull();
    expect(extractUserText({ type: "permission-mode" })).toBeNull();
  });

  it("handles malformed entries gracefully", () => {
    expect(extractUserText(null)).toBeNull();
    expect(extractUserText("not an object")).toBeNull();
    expect(extractUserText({ type: "user" })).toBeNull();
    expect(extractUserText({ type: "user", message: null })).toBeNull();
    expect(
      extractUserText({ type: "user", message: { content: null } }),
    ).toBeNull();
  });

  it("skips local-command-output wrapper entries (string content)", () => {
    const entry = {
      type: "user",
      message: {
        role: "user",
        content:
          "<local-command-stdout>Catch you later!</local-command-stdout>",
      },
    };
    expect(extractUserText(entry)).toBeNull();
  });

  it("skips local-command-caveat wrapper entries", () => {
    const entry = {
      type: "user",
      message: {
        role: "user",
        content:
          "<local-command-caveat>Caveat: messages...</local-command-caveat>",
      },
    };
    expect(extractUserText(entry)).toBeNull();
  });

  it("skips local-command wrapper in typed-block array content", () => {
    const entry = {
      type: "user",
      message: {
        role: "user",
        content: [
          { type: "text", text: "<command-name>/exit</command-name>" },
          { type: "text", text: "this is the real follow-up text" },
        ],
      },
    };
    expect(extractUserText(entry)).toBe("this is the real follow-up text");
  });
});

describe("unsanitizeCwd", () => {
  it("converts a sanitized dir name back to a path", () => {
    expect(unsanitizeCwd("-Users-luismiguelcaamano-projects-CCWEAROS")).toBe(
      "/Users/luismiguelcaamano/projects/CCWEAROS",
    );
  });

  it("returns input unchanged when no leading dash", () => {
    expect(unsanitizeCwd("not-a-sanitized-path")).toBe("not-a-sanitized-path");
  });

  it("handles the bare root dir", () => {
    expect(unsanitizeCwd("-Users-luismiguelcaamano")).toBe(
      "/Users/luismiguelcaamano",
    );
  });
});
