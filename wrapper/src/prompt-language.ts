// Language of a voice prompt. The watch dictates in es-CO and the owner
// speaks Spanish, so Spanish is the default; English only when the text is
// clearly English (common English words and no Spanish markers).
export function promptLanguage(text: string): "es" | "en" {
  if (/[áéíóúñ¿¡]/i.test(text)) return "es";
  const es = /\b(qu[eé]|c[oó]mo|cu[aá]nt[oa]s?|cu[aá]l|d[oó]nde|cu[aá]ndo|por|para|mi|mis|el|la|los|las|un|una|y|de|del|en|con|que|me|dime|m[aá]ndame|abre|busca|haz|organiza)\b/i;
  if (es.test(text)) return "es";
  const en = /\b(the|what|how|please|can|could|you|my|show|open|tell|is|are|and|of|to|in|with|send|find)\b/i;
  return en.test(text) ? "en" : "es";
}
