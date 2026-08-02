/**
 * 최소 SSE(text/event-stream) 프레임 파서.
 *
 * 브라우저 표준 `EventSource`는 커스텀 헤더를 실을 수 없어 `Authorization: Bearer ...`가
 * 필수인 스트림에는 쓸 수 없다. 그래서 fetch로 스트림을 열고 본문을 직접 파싱한다.
 * (외부 폴리필 의존성을 추가하지 않기 위해 필요한 최소 범위만 구현한다.)
 *
 * 지원 범위 — 서버가 실제로 보내는 것만 다룬다:
 * - `event:` / `data:` 필드, `data:` 여러 줄은 개행으로 이어붙임
 * - `:ping` 하트비트를 포함한 주석 줄 무시
 * - CRLF/LF 개행 혼용
 * 미지원: `id:`(서버가 보내지 않아 Last-Event-ID 복구가 불가하다), `retry:`
 */

export interface SseFrame {
  /** `event:` 필드. 생략된 프레임은 SSE 규격대로 'message'로 본다. */
  event: string;
  /** `data:` 줄들을 개행으로 이어붙인 원문. */
  data: string;
}

/** 프레임 하나(빈 줄로 끝나는 블록)를 파싱한다. data가 없으면 null(주석/하트비트만 있는 블록). */
function parseFrame(block: string): SseFrame | null {
  let event = 'message';
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    // 주석 줄(':'로 시작). 15초마다 오는 `:ping` 하트비트가 여기로 걸러진다.
    if (line.length === 0 || line.startsWith(':')) continue;

    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    // 규격상 콜론 뒤 공백 하나만 제거한다.
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') {
      event = value;
    } else if (field === 'data') {
      dataLines.push(value);
    }
    // id/retry 및 알 수 없는 필드는 무시한다.
  }

  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join('\n') };
}

/**
 * 스트림을 끝까지 읽으며 프레임마다 `onFrame`을 호출한다.
 *
 * 정상 종료(서버가 연결을 닫음 — 30분 타임아웃 등)면 resolve하고,
 * 중단(AbortController)·네트워크 오류면 reject한다. 재연결 여부는 호출자가 정한다.
 */
export async function consumeEventStream(
  stream: ReadableStream<Uint8Array>,
  onFrame: (frame: SseFrame) => void,
): Promise<void> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, '\n');

      let boundary = buffer.indexOf('\n\n');
      while (boundary !== -1) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);

        const frame = parseFrame(block);
        if (frame) onFrame(frame);

        boundary = buffer.indexOf('\n\n');
      }
    }

    // 마지막 프레임이 빈 줄 없이 끝난 경우를 위한 마무리.
    const tail = parseFrame(buffer);
    if (tail) onFrame(tail);
  } finally {
    reader.releaseLock();
  }
}
