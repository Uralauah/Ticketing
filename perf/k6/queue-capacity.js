// 대기열 용량 (k6, 도착률 모델)
//
// MODE=entry: 오픈 순간 진입 폭주, 초당 진입 수를 단계적으로 올리며 단계별 p95 기록
//   k6 run -e MODE=entry -e RATES=1000,2000,4000,8000 perf/k6/queue-capacity.js
//
// MODE=poll: 대기자가 많을 때의 상태 조회 부하 (VU마다 대기자 USERS_PER_VU명 보관)
//   대기자 N명 ≈ 초당 N/10건 조회 → 초당 5,000건 ≈ 대기자 5만 명
//   k6 run -e MODE=poll -e RATES=2000,5000,10000 perf/k6/queue-capacity.js
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'entry';
const SCHEDULE_ID = __ENV.SCHEDULE_ID || '1';
const RATES = (__ENV.RATES || (MODE === 'entry' ? '1000,2000,4000,8000' : '2000,5000,10000')).split(',').map(Number);
const STAGE_SECONDS = Number(__ENV.STAGE_SECONDS || 20);
const USERS_PER_VU = Number(__ENV.USERS_PER_VU || 50);
const VUS = Number(__ENV.VUS || 1000);
const USER_ID_BASE = Number(__ENV.USER_ID_BASE || 1_000_000); // 시드 사용자와 겹치지 않게
const SLO_MS = MODE === 'entry' ? 200 : 100;

const reentered = new Counter('queue_reentered');

http.setResponseCallback(http.expectedStatuses(200));

const thresholds = { http_req_failed: ['rate<0.01'] };
RATES.forEach((rate, i) => {
  thresholds[`http_req_duration{stage:${i}_${rate}rps,kind:${MODE}}`] = [`p(95)<${SLO_MS}`];
});

export const options = {
  scenarios: {
    queue: {
      executor: 'ramping-arrival-rate',
      startRate: RATES[0],
      timeUnit: '1s',
      preAllocatedVUs: MODE === 'poll' ? VUS : 300,
      maxVUs: MODE === 'poll' ? VUS : 3000,
      stages: RATES.flatMap((rate) => [
        { target: rate, duration: '3s' },
        { target: rate, duration: `${STAGE_SECONDS - 3}s` },
      ]),
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

function stageTag() {
  const elapsed = exec.instance.currentTestRunDuration / 1000;
  const i = Math.min(RATES.length - 1, Math.floor(elapsed / STAGE_SECONDS));
  return `${i}_${RATES[i]}rps`;
}

function enter(userId, kind, needToken) {
  // 토큰이 필요 없으면 본문을 읽지 않음 (부하 발생기 CPU 절약)
  const res = http.post(`${BASE}/api/v1/queue/${SCHEDULE_ID}/entry?userId=${userId}`, null,
    { tags: { stage: stageTag(), kind }, responseType: needToken ? 'text' : 'none' });
  check(res, { 'entry 200': (r) => r.status === 200 });
  return needToken && res.status === 200 ? res.json('token') : null;
}

// ── MODE=poll: VU마다 대기자 목록을 차례로 조회 ──
let users = null;
let cursor = 0;

function fillUsers() {
  const base = USER_ID_BASE + (exec.vu.idInTest - 1) * USERS_PER_VU;
  const requests = [];
  for (let i = 0; i < USERS_PER_VU; i++) {
    requests.push(['POST', `${BASE}/api/v1/queue/${SCHEDULE_ID}/entry?userId=${base + i}`, null, { tags: { kind: 'fill' } }]);
  }
  const responses = http.batch(requests);
  users = responses.map((r, i) => ({ id: base + i, token: r.status === 200 ? r.json('token') : null }));
}

function poll() {
  if (users === null) {
    fillUsers();
    return;
  }
  const user = users[cursor];
  cursor = (cursor + 1) % users.length;
  if (!user.token) {
    user.token = enter(user.id, 'reenter', true);
    return;
  }
  const res = http.get(`${BASE}/api/v1/queue/${SCHEDULE_ID}/status?userId=${user.id}`,
    { headers: { 'X-Queue-Token': user.token }, tags: { stage: stageTag(), kind: 'poll' },
      responseCallback: http.expectedStatuses(200, 401, 410) });
  if (res.status === 401 || res.status === 410) {
    // 세션 종료 또는 이탈로 정리됨 → 재진입
    user.token = null;
    reentered.add(1);
  }
}

export default function () {
  if (MODE === 'poll') {
    poll();
  } else {
    enter(USER_ID_BASE + exec.scenario.iterationInTest, 'entry', false);
  }
}
