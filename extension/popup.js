const $ = (id) => document.getElementById(id);
let running = false;

function paint(state) {
  running = state.running;
  $('worker').textContent = state.workerId || '-';
  $('toggle').textContent = running ? '정지' : '시작';
  $('toggle').classList.toggle('on', running);

  const s = state.stats;
  $('pending').textContent = s ? s.pending : '-';
  $('running').textContent = s ? `${s.running} / ${s.maxConcurrent}` : '-';
  $('succeeded').textContent = s ? s.succeeded : '-';
  $('failed').textContent = s ? s.failed : '-';

  if (state.error) {
    $('logs').innerHTML = `<div>서버 연결 실패: ${state.error}</div>`;
  }
}

async function refresh() {
  chrome.runtime.sendMessage({ type: 'STATUS' }, (state) => {
    if (state) paint(state);
  });
  const { logs = [] } = await chrome.storage.local.get('logs');
  if (logs.length) {
    $('logs').innerHTML = logs
      .slice(0, 12)
      .map((l) => `<div>${l.at.slice(11, 19)} ${l.message}</div>`)
      .join('');
  }
}

$('toggle').addEventListener('click', () => {
  chrome.runtime.sendMessage({ type: running ? 'STOP' : 'START' }, () => refresh());
});

refresh();
setInterval(refresh, 1500);
