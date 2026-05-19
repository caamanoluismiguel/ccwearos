import { describe, expect, it } from "vitest";
import { parseShareArgs } from "./share-args.js";

describe("parseShareArgs", () => {
  it("returns null when no flags present", () => {
    expect(parseShareArgs([])).toEqual({ resumeSessionId: null });
  });

  it("parses --resume <id> (separate arg form)", () => {
    expect(
      parseShareArgs(["--resume", "550e8400-e29b-41d4-a716-446655440000"]),
    ).toEqual({ resumeSessionId: "550e8400-e29b-41d4-a716-446655440000" });
  });

  it("parses --resume=<id> (= form)", () => {
    expect(parseShareArgs(["--resume=abc12345-6789"])).toEqual({
      resumeSessionId: "abc12345-6789",
    });
  });

  it("rejects shell-meta in sessionId (defense in depth)", () => {
    expect(() => parseShareArgs(["--resume", "abc; rm -rf /"])).toThrow(
      /malformed/,
    );
    expect(() =>
      parseShareArgs(["--resume", "abc$(touch /tmp/pwned)"]),
    ).toThrow(/malformed/);
    expect(() => parseShareArgs(["--resume", "abc`echo`"])).toThrow(
      /malformed/,
    );
  });

  it("rejects empty --resume value", () => {
    expect(() => parseShareArgs(["--resume"])).toThrow(/requires/);
    expect(() => parseShareArgs(["--resume", ""])).toThrow(/requires/);
  });

  it("rejects too-short or too-long sessionId", () => {
    expect(() => parseShareArgs(["--resume", "abc"])).toThrow(/malformed/);
    expect(() => parseShareArgs(["--resume", "a".repeat(65)])).toThrow(
      /malformed/,
    );
  });

  it("accepts canonical hex-dash session ids of varying lengths", () => {
    expect(parseShareArgs(["--resume", "abcdef12"])).toEqual({
      resumeSessionId: "abcdef12",
    });
    expect(
      parseShareArgs(["--resume", "abcdef12-3456-7890-abcd-ef1234567890"]),
    ).toEqual({
      resumeSessionId: "abcdef12-3456-7890-abcd-ef1234567890",
    });
  });

  it("ignores unknown args", () => {
    expect(parseShareArgs(["--something-else", "value"])).toEqual({
      resumeSessionId: null,
    });
  });
});
