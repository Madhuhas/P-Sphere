/**
 * Extracts the first JSON object from an LLM response string and parses it.
 * Returns null if no valid JSON object is found or parsing fails.
 *
 * LLMs often wrap JSON in markdown fences or prose. This function handles
 * that by finding the outermost `{...}` block regardless of surrounding text.
 */
export function parseLlmJson<T>(text: string): T | null {
  const match = text.match(/\{[\s\S]*\}/);
  if (!match) return null;
  try {
    return JSON.parse(match[0]) as T;
  } catch {
    return null;
  }
}
