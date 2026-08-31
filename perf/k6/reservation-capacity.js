// 예약 경로 용량과 인기 좌석 경합 (k6, 도착률 모델)
// perf 프로필의 /api/v1/experiment/reservation 사용 (대기열 제외)
//
// MODE=uniform (기본): 전 좌석에 RATE건/초로 예약 시도
//   k6 run -e MODE=uniform -e RATE=200 -e DURATION=20s perf/k6/reservation-capacity.js
//
// MODE=hot: 일반 예약 중간에 앞줄(A1열) 50석으로 폭주 투입
//   k6 run -e MODE=hot -e BG_RATE=50 -e BURST_RATE=500 -e BURST_SECONDS=2 perf/k6/reservation-capacity.js
//   BURST_TARGET=spread: 같은 폭주를 전 좌석에 분산 (대조군)
//
// 409(이미 예약된 좌석)는 실패로 집계하지 않음
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const MODE = __ENV.MODE || 'uniform';
const SCHEDULE_ID = Number(__ENV.SCHEDULE_ID || 1);
const USERS = Number(__ENV.USERS || 100000);

const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '20s';

const BG_RATE = Number(__ENV.BG_RATE || 50);
const BURST_RATE = Number(__ENV.BURST_RATE || 500);
const BURST_SECONDS = Number(__ENV.BURST_SECONDS || 2);
const BURST_START = 10; // 일반 예약이 안정된 뒤 폭주 시작
const HOT_TOTAL_SECONDS = 40;
const BURST_TARGET = __ENV.BURST_TARGET || 'hot'; // hot | spread

const succeeded = new Counter('reserve_succeeded');
const conflicted = new Counter('reserve_conflicted');
const errored = new Counter('reserve_errored');

http.setResponseCallback(http.expectedStatuses(200, 409));

const scenarios = MODE === 'hot'
  ? {
      background: {
        executor: 'constant-arrival-rate', exec: 'background', rate: BG_RATE, timeUnit: '1s',
        duration: `${HOT_TOTAL_SECONDS}s`, preAllocatedVUs: 100, maxVUs: 2000,
      },
      burst: {
        executor: 'constant-arrival-rate', exec: 'burst', rate: BURST_RATE, timeUnit: '1s',
        duration: `${BURST_SECONDS}s`, startTime: `${BURST_START}s`, preAllocatedVUs: 500, maxVUs: 3000,
      },
    }
  : {
      uniform: {
        executor: 'constant-arrival-rate', exec: 'uniform', rate: RATE, timeUnit: '1s',
        duration: DURATION, preAllocatedVUs: 200, maxVUs: 3000,
      },
    };

export const options = {
  scenarios,
  thresholds: MODE === 'hot'
    ? {
        'http_req_duration{scenario:burst}': ['p(95)<500'],
        'http_req_duration{scenario:background,phase:before}': ['p(95)<500'],
        'http_req_duration{scenario:background,phase:during}': ['p(95)<500'],
        'http_req_duration{scenario:background,phase:after}': ['p(95)<500'],
        reserve_errored: ['count==0'],
      }
    : {
        'http_req_duration{scenario:uniform}': ['p(95)<500'],
        reserve_errored: ['count==0'],
      },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// setup 데이터는 VU마다 복사 → 연속 구간은 [최소, 최대]로만 전달
function compact(ids) {
  const sorted = [...ids].sort((a, b) => a - b);
  const contiguous = sorted[sorted.length - 1] - sorted[0] + 1 === sorted.length;
  return contiguous ? { min: sorted[0], max: sorted[sorted.length - 1] } : { list: sorted };
}

export function setup() {
  const seats = http.get(`${BASE}/api/v1/schedule/${SCHEDULE_ID}/seat-map`).json('seats');
  return {
    all: compact(seats.map((s) => s.seatId)),
    hot: compact(seats.filter((s) => s.seatNum.startsWith('A1-')).map((s) => s.seatId)),
    others: compact(seats.filter((s) => !s.seatNum.startsWith('A')).map((s) => s.seatId)),
  };
}

function reserve(seatId, tags) {
  const userId = 1 + Math.floor(Math.random() * USERS);
  const res = http.post(`${BASE}/api/v1/experiment/reservation`,
    JSON.stringify({ userId, scheduleId: SCHEDULE_ID, seatId }),
    { headers: { 'Content-Type': 'application/json' }, tags });
  if (res.status === 200) succeeded.add(1, tags);
  else if (res.status === 409) conflicted.add(1, tags);
  else errored.add(1, tags);
  check(res, { '200 or 409': (r) => r.status === 200 || r.status === 409 });
}

function pick(set) {
  if (set.list) return set.list[Math.floor(Math.random() * set.list.length)];
  return set.min + Math.floor(Math.random() * (set.max - set.min + 1));
}

export function uniform(data) {
  reserve(pick(data.all), {});
}

export function background(data) {
  const elapsed = exec.instance.currentTestRunDuration / 1000;
  const phase = elapsed < BURST_START ? 'before' : elapsed < BURST_START + BURST_SECONDS + 3 ? 'during' : 'after';
  reserve(pick(data.others), { phase });
}

export function burst(data) {
  reserve(pick(BURST_TARGET === 'spread' ? data.all : data.hot), {});
}
