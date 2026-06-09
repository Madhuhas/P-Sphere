import { config } from '../config/env';

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

export interface ChatResponse {
  message: string;
  model: string;
  done: boolean;
}

// ── Ollama ────────────────────────────────────────────────────────────────────

async function chatOllama(messages: ChatMessage[]): Promise<ChatResponse> {
  const res = await fetch(`${config.llm.baseUrl}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: config.llm.model,
      messages,
      stream: false,
    }),
  });

  if (!res.ok) throw new Error(`Ollama error: ${res.status} ${await res.text()}`);
  const data = await res.json() as { message: { content: string }; model: string; done: boolean };
  return { message: data.message.content, model: data.model, done: data.done };
}

// ── OpenAI-compatible (LM Studio, LiteLLM, etc.) ──────────────────────────────

async function chatOpenAICompat(messages: ChatMessage[]): Promise<ChatResponse> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (config.llm.apiKey) headers['Authorization'] = `Bearer ${config.llm.apiKey}`;

  const res = await fetch(`${config.llm.baseUrl}/chat/completions`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      model: config.llm.model,
      messages,
      stream: false,
    }),
  });

  if (!res.ok) throw new Error(`LLM error: ${res.status} ${await res.text()}`);
  const data = await res.json() as {
    choices: { message: { content: string } }[];
    model: string;
  };
  return {
    message: data.choices[0]?.message.content ?? '',
    model: data.model,
    done: true,
  };
}

// ── Public API ────────────────────────────────────────────────────────────────

export async function chat(messages: ChatMessage[]): Promise<ChatResponse> {
  if (config.llm.provider === 'ollama') return chatOllama(messages);
  return chatOpenAICompat(messages);
}

export async function isAvailable(): Promise<boolean> {
  try {
    const url = config.llm.provider === 'ollama'
      ? `${config.llm.baseUrl}/api/tags`
      : `${config.llm.baseUrl}/models`;
    const res = await fetch(url, { signal: AbortSignal.timeout(3000) });
    return res.ok;
  } catch {
    return false;
  }
}
