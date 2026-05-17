import { describe, expect, it } from "vitest";
import {
  extractPermissionPrompt,
  extractTokenCount,
  extractTokenCounts,
  isAwaitingPermission,
} from "./parser.js";

describe("extractTokenCount", () => {
  it("parses 'Tokens used: 1234'", () => {
    expect(extractTokenCount("Tokens used: 1234")).toBe(1234);
  });

  it("parses 'Usage: 50 tkns' (the variant the blueprint warns about)", () => {
    expect(extractTokenCount("Usage: 50 tkns")).toBe(50);
  });

  it("handles comma-grouped numbers", () => {
    expect(extractTokenCount("tokens used: 12,345")).toBe(12345);
  });

  it("returns null when nothing matches", () => {
    expect(extractTokenCount("hello world")).toBeNull();
  });
});

describe("extractTokenCounts", () => {
  it("returns every match in a chunk, in pattern order", () => {
    const chunk = "Tokens used: 100\nUsage: 200 tkns\nTokens used: 300";
    const got = extractTokenCounts(chunk);
    // both `tokens used` matches first, then `usage` match
    expect(got.sort((a, b) => a - b)).toEqual([100, 200, 300]);
  });

  it("returns an empty array for non-matching input", () => {
    expect(extractTokenCounts("no numbers here")).toEqual([]);
  });

  it("skips zero and negative-looking values", () => {
    // "-50" never matches because the patterns expect a leading digit.
    expect(extractTokenCounts("Tokens used: 0")).toEqual([]);
  });

  it("captures input/output token splits", () => {
    expect(
      extractTokenCounts("Sent 1,200 input tokens, got 350 output tokens"),
    ).toEqual(expect.arrayContaining([1200, 350]));
  });
});

describe("isAwaitingPermission", () => {
  it("detects [Y/n]", () => {
    expect(isAwaitingPermission("Continue? [Y/n]")).toBe(true);
  });

  it("detects 'Do you want to allow'", () => {
    expect(isAwaitingPermission("Do you want to allow this action?")).toBe(
      true,
    );
  });

  it("detects (y/n)", () => {
    expect(isAwaitingPermission("Proceed?\n(y/n)")).toBe(true);
  });

  it("ignores plain output", () => {
    expect(isAwaitingPermission("Tokens used: 100")).toBe(false);
  });
});

describe("extractPermissionPrompt", () => {
  it("returns the full prompt line surrounding the match", () => {
    const chunk =
      "Reading foo.ts\nDo you want to allow web fetch? [Y/n]\nWaiting...";
    expect(extractPermissionPrompt(chunk)).toBe(
      "Do you want to allow web fetch? [Y/n]",
    );
  });

  it("returns null when no permission pattern is present", () => {
    expect(extractPermissionPrompt("Just running normally")).toBeNull();
  });

  it("trims whitespace around the matched line", () => {
    expect(extractPermissionPrompt("\n   Allow?   \n")).toBe("Allow?");
  });
});
