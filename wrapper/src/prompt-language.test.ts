import { describe, expect, it } from "vitest";

import { promptLanguage } from "./prompt-language.js";

describe("promptLanguage", () => {
  it("defaults to Spanish for Spanish voice text without question words", () => {
    expect(promptLanguage("Mándame el último download que hay en mi carpeta y mándamela a mi correo")).toBe("es");
    expect(promptLanguage("Organiza mis downloads")).toBe("es");
    expect(promptLanguage("corre ls en la carpeta projects")).toBe("es");
  });
  it("detects clear English", () => {
    expect(promptLanguage("what is the weather in Bogota")).toBe("en");
    expect(promptLanguage("open my calendar please")).toBe("en");
  });
  it("falls back to Spanish when unsure", () => {
    expect(promptLanguage("ls")).toBe("es");
  });
});
