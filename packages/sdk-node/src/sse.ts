export interface SseEvent {
  event: string;
  data: string;
  id?: string;
}

/** Minimal SSE reader over fetch. Resolves when the stream ends; throws on network errors. */
export async function readSse(res: Response, onEvent: (e: SseEvent) => void, signal: AbortSignal) {
  if (!res.body) throw new Error('No response body');
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  let cur: Partial<SseEvent> = {};
  while (!signal.aborted) {
    const { value, done } = await reader.read();
    if (done) return;
    buf += dec.decode(value, { stream: true });
    let nl: number;
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl).replace(/\r$/, '');
      buf = buf.slice(nl + 1);
      if (line === '') {
        if (cur.data !== undefined || cur.event) onEvent({ event: cur.event ?? 'message', data: cur.data ?? '', id: cur.id });
        cur = {};
      } else if (!line.startsWith(':')) {
        const i = line.indexOf(':');
        const field = i < 0 ? line : line.slice(0, i);
        const val = i < 0 ? '' : line.slice(i + 1).replace(/^ /, '');
        if (field === 'event') cur.event = val;
        else if (field === 'data') cur.data = cur.data === undefined ? val : `${cur.data}\n${val}`;
        else if (field === 'id') cur.id = val;
      }
    }
  }
}
