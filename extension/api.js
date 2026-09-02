// 서버 큐 API 클라이언트.
// 확장 어디에서도 fetch 를 직접 부르지 않고 이 파일만 거치게 해서 엔드포인트 변경 지점을 하나로 둔다.

const DEFAULT_BASE_URL = 'http://localhost:8080';

export async function baseUrl() {
  const { serverUrl } = await chrome.storage.local.get('serverUrl');
  return serverUrl || DEFAULT_BASE_URL;
}

async function request(path, options = {}) {
  const url = (await baseUrl()) + path;
  const res = await fetch(url, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
  });

  if (res.status === 204) return null;

  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const message = body?.message || `HTTP ${res.status}`;
    const error = new Error(message);
    error.status = res.status;
    error.code = body?.code;
    throw error;
  }
  return body;
}

/** 수집 요청 등록 */
export function enqueue(siteCode, externalId, sourceUrl) {
  return request('/api/v1/jobs', {
    method: 'POST',
    body: JSON.stringify({ siteCode, externalId, sourceUrl }),
  });
}

/** 다음 작업 점유. 받을 게 없으면 null */
export function claim(workerId) {
  return request(`/api/v1/jobs/claim?workerId=${encodeURIComponent(workerId)}`, { method: 'POST' });
}

/** 수집 결과 제출 */
export function submitResult(jobId, workerId, payload) {
  return request(`/api/v1/jobs/${jobId}/result`, {
    method: 'POST',
    body: JSON.stringify({ workerId, payload }),
  });
}

/** 실패 보고 */
export function reportFailure(jobId, reason) {
  return request(`/api/v1/jobs/${jobId}/failure`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

export function stats() {
  return request('/api/v1/jobs/stats');
}
