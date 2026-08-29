// 좌석 스냅샷 open-model 부하 테스트 (k6)
//
// closed-loop는 서버가 느려지면 요청도 줄어 지연이 작게 보임 (coordinated omission)
// → 도착률을 단계적으로 올리며 p95 ≤ 300ms를 지키는 최대 처리량 탐색
//
// 서버는 perf 프로필로 실행
//   k6 run -e BASE=http://localhost:8080 -e MODE=cached perf/k6/snapshot-arrival.js
//   k6 run -e BASE=http://localhost:8080 -e MODE=legacy perf/k6/snapshot-arrival.js   # 캐시 이전 방식
//   k6 run -e RATES=50,100,200 ...                                                    # 단계 바꾸기
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'cached'; // cached | legacy
const SCHEDULE_ID = __ENV.SCHEDULE_ID || '1';
const RATES = (__ENV.RATES || '100,300,600,1000,1500,2000').split(',').map(Number);
const STAGE_SECONDS = 60; // 단계마다 10초 동안 올리고 50초 유지

const PATH = `/api/v1/experiment/schedule/${SCHEDULE_ID}/seat-status/snapshot${MODE === 'legacy' ? '/legacy' : ''}`;

const thresholds = { http_req_failed: ['rate<0.01'] };
RATES.forEach((rate, i) => {
  // 단계별 p95 기록용
  thresholds[`http_req_duration{stage:${i}_${rate}rps}`] = ['p(95)<300'];
});

export const options = {
  scenarios: {
    snapshot: {
      executor: 'ramping-arrival-rate',
      startRate: RATES[0],
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 5000,
      stages: RATES.flatMap((rate) => [
        { target: rate, duration: '10s' },
        { target: rate, duration: `${STAGE_SECONDS - 10}s` },
      ]),
    },
  },
  thresholds,
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const elapsedSeconds = exec.instance.currentTestRunDuration / 1000;
  const i = Math.min(RATES.length - 1, Math.floor(elapsedSeconds / STAGE_SECONDS));
  const res = http.get(`${BASE}${PATH}`, {
    headers: { 'Accept-Encoding': 'gzip' },
    tags: { mode: MODE, stage: `${i}_${RATES[i]}rps` },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
