// 워커 루프.
// 서버에서 작업을 하나 받아 → 탭을 열고 → 추출기를 주입해 결과를 받고 → 제출하고 → 탭을 닫는다.
//
// 동시 실행 개수는 확장이 아니라 서버가 정한다(claim 이 204 를 주면 대기).
// 확장에서 세면 창을 여러 개 띄웠을 때 상한이 무너지기 때문이다.

import { claim, reportFailure, submitResult, stats } from './api.js';
import { extractProduct } from './extractor.js';

const IDLE_DELAY_MS = 3000; // 받을 작업이 없을 때 쉬는 간격
const BUSY_DELAY_MS = 500; // 연속 처리 사이 최소 간격
const PAGE_LOAD_TIMEOUT_MS = 30000;
const WORKER_ID = crypto.randomUUID().slice(0, 8);

let running = false;

async function log(message) {
  const { logs = [] } = await chrome.storage.local.get('logs');
  logs.unshift({ at: new Date().toISOString(), message });
  await chrome.storage.local.set({ logs: logs.slice(0, 50) });
}

/** 탭이 완전히 로드될 때까지 기다린다. 타임아웃이 없으면 워커가 영원히 묶인다. */
function waitForTabLoad(tabId) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      chrome.tabs.onUpdated.removeListener(listener);
      reject(new Error('페이지 로드 타임아웃'));
    }, PAGE_LOAD_TIMEOUT_MS);

    function listener(updatedTabId, info) {
      if (updatedTabId === tabId && info.status === 'complete') {
        clearTimeout(timer);
        chrome.tabs.onUpdated.removeListener(listener);
        resolve();
      }
    }
    chrome.tabs.onUpdated.addListener(listener);
  });
}

async function collect(job) {
  const tab = await chrome.tabs.create({ url: job.sourceUrl, active: false });
  try {
    await waitForTabLoad(tab.id);
    const [injection] = await chrome.scripting.executeScript({
      target: { tabId: tab.id },
      func: extractProduct,
      args: [job.siteCode],
    });
    if (!injection?.result) {
      throw new Error('추출 결과가 비어 있습니다');
    }
    return injection.result;
  } finally {
    // 성공하든 실패하든 탭은 반드시 닫는다. 남으면 브라우저가 금방 무거워진다.
    await chrome.tabs.remove(tab.id).catch(() => {});
  }
}

async function processOne() {
  const job = await claim(WORKER_ID);
  if (!job) return false;

  await log(`작업 ${job.id} 수집 시작 (${job.siteCode}/${job.externalId})`);
  try {
    const payload = await collect(job);
    const product = await submitResult(job.id, WORKER_ID, payload);
    await log(`작업 ${job.id} 완료 → ${product.title} (옵션 ${product.optionCount}개)`);
  } catch (e) {
    await log(`작업 ${job.id} 실패: ${e.message}`);
    // 422(정규화 실패)는 서버가 이미 확정 처리했으므로 중복 보고하지 않는다.
    if (e.status !== 422) {
      await reportFailure(job.id, e.message).catch(() => {});
    }
  }
  return true;
}

async function loop() {
  while (running) {
    let worked = false;
    try {
      worked = await processOne();
    } catch (e) {
      await log(`워커 오류: ${e.message}`);
    }
    await new Promise((r) => setTimeout(r, worked ? BUSY_DELAY_MS : IDLE_DELAY_MS));
  }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.type === 'START') {
    if (!running) {
      running = true;
      loop();
      log(`워커 ${WORKER_ID} 시작`);
    }
    sendResponse({ running, workerId: WORKER_ID });
  } else if (message.type === 'STOP') {
    running = false;
    log(`워커 ${WORKER_ID} 정지`);
    sendResponse({ running: false, workerId: WORKER_ID });
  } else if (message.type === 'STATUS') {
    stats()
      .then((s) => sendResponse({ running, workerId: WORKER_ID, stats: s }))
      .catch((e) => sendResponse({ running, workerId: WORKER_ID, error: e.message }));
    return true; // 비동기 응답
  }
  return false;
});
