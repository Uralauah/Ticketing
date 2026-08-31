"""
실시간 전파 시나리오 (SSE + 예약)
────────────────────────────────────────────────────────
- SeatListener: 대기열 통과 → 스냅샷 → SSE 연결 유지, publishedAt과 수신 시각 차이를 기록
- Booker: 대기열 통과 → 빈 좌석 예약 → 입장 슬롯 반납 후 재진입

측정 지표:
  [SSE] propagation : 예약 커밋 → SSE 수신까지 걸린 시간
  [Reserve] POST    : 예약 API 응답 시간 (409는 정상 경합으로 성공 처리)

주의:
  - 서버와 같은 기기(또는 시계가 동기화된 기기)에서 실행
  - 재측정 시 docker compose down -v로 데이터 초기화

실행 (Listener:Booker = 10:1):
  locust -f realtime.py --users 550 --spawn-rate 50 --run-time 3m
"""

import json
import random
import re
import time

from locust import HttpUser, between, events, task

from common import SCHEDULE_ID, QueueSession

SNAPSHOT_PATH = f"/api/v1/schedule/{SCHEDULE_ID}/seat-status/snapshot"
STREAM_PATH = f"/api/v1/schedule/{SCHEDULE_ID}/seat-status/stream"
STREAM_HOLD_SECONDS = 600
VERSION_PATTERN = re.compile(rb'"currentVersion"\s*:\s*(\d+)')


class SeatListener(HttpUser):
    weight = 10
    host = "http://localhost:8080"
    wait_time = between(1, 2)

    def on_start(self):
        self.queue = QueueSession(self.client)
        self.queue.enter()

    @task
    def listen(self):
        if not self.queue.poll():
            return
        version = self._snapshot_version()
        if version is not None:
            self._stream(since=version)

    def _snapshot_version(self):
        params, headers = self.queue.auth()
        with self.client.get(SNAPSHOT_PATH, params=params, headers=headers,
                             name="[Seat] snapshot", catch_response=True) as resp:
            if resp.status_code == 200:
                resp.success()
                match = VERSION_PATTERN.search(resp.content[:64])
                return int(match.group(1)) if match else None
            if self.queue.handle_session_end(resp):
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")
            return None

    def _stream(self, since):
        params, headers = self.queue.auth()
        params = dict(params, since=since)
        headers = dict(headers, Accept="text/event-stream")
        with self.client.get(STREAM_PATH, params=params, headers=headers, stream=True,
                             name="[SSE] connect", catch_response=True) as resp:
            if resp.status_code != 200:
                if self.queue.handle_session_end(resp):
                    resp.success()
                else:
                    resp.failure(f"HTTP {resp.status_code}")
                return
            resp.success()
            self._read_events(resp)

    def _read_events(self, resp):
        deadline = time.time() + STREAM_HOLD_SECONDS
        event_name = None
        # heartbeat(15초) 덕분에 이벤트가 없어도 마감 시각 확인 가능
        for line in resp.iter_lines(decode_unicode=True):
            if time.time() > deadline:
                break
            if not line:
                event_name = None
            elif line.startswith("event:"):
                event_name = line[len("event:"):].strip()
            elif line.startswith("data:") and event_name == "seat-status":
                published_at = json.loads(line[len("data:"):]).get("publishedAt", 0)
                if published_at:
                    events.request.fire(
                        request_type="SSE",
                        name="[SSE] propagation",
                        response_time=max(0.0, time.time() * 1000 - published_at),
                        response_length=len(line),
                        exception=None,
                        context={},
                    )


class Booker(HttpUser):
    weight = 1
    host = "http://localhost:8080"
    wait_time = between(0.5, 1.5)

    # 스냅샷 파싱 비용이 커서 빈 좌석 목록을 공유하고 5초마다 갱신
    _available_seats = []
    _seats_refreshed_at = 0.0

    def on_start(self):
        self.queue = QueueSession(self.client)
        self.queue.enter()

    @task
    def book(self):
        if not self.queue.poll():
            return
        seats = self._seats()
        if not seats:
            return

        params, headers = self.queue.auth()
        body = {"userId": params["userId"], "scheduleId": SCHEDULE_ID, "seatId": random.choice(seats)}
        with self.client.post("/api/v1/reservation", json=body, headers=headers,
                              name="[Reserve] POST", catch_response=True) as resp:
            if resp.status_code == 200:
                resp.success()
                self.queue.reset()  # 예약 시 입장 슬롯 반납 → 재진입
            elif resp.status_code == 409:
                resp.success()  # 다른 사람이 먼저 예약한 좌석(정상 경합)
            elif resp.status_code == 400 and _code(resp) == "USER-400-001":
                resp.success()  # 포인트 소진 → 다른 사용자로 재진입
                self.queue = QueueSession(self.client)
            elif self.queue.handle_session_end(resp):
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    def _seats(self):
        cls = type(self)
        if time.time() - cls._seats_refreshed_at < 5 and cls._available_seats:
            return cls._available_seats
        params, headers = self.queue.auth()
        with self.client.get(SNAPSHOT_PATH, params=params, headers=headers,
                             name="[Seat] snapshot", catch_response=True) as resp:
            if resp.status_code != 200:
                if self.queue.handle_session_end(resp):
                    resp.success()
                else:
                    resp.failure(f"HTTP {resp.status_code}")
                return cls._available_seats
            resp.success()
            cls._available_seats = [s["seatId"] for s in resp.json()["seats"] if s["status"] == "AVAILABLE"]
            cls._seats_refreshed_at = time.time()
            return cls._available_seats


def _code(resp):
    try:
        return resp.json().get("code")
    except ValueError:
        return None


@events.quitting.add_listener
def print_summary(environment, **kwargs):
    stats = environment.stats
    propagation = stats.get("[SSE] propagation", "SSE")
    reserve = stats.get("[Reserve] POST", "POST")

    print("\n" + "=" * 55)
    print("  [Realtime] 결과 요약")
    print("=" * 55)
    if propagation and propagation.num_requests:
        print("  [SSE 전파 지연] (예약 커밋 → SSE 수신)")
        print(f"    수신 이벤트    : {propagation.num_requests:,}")
        print(f"    p50 (ms)       : {propagation.get_response_time_percentile(0.50):.0f}")
        print(f"    p95 (ms)       : {propagation.get_response_time_percentile(0.95):.0f}")
        print(f"    p99 (ms)       : {propagation.get_response_time_percentile(0.99):.0f}")
    if reserve and reserve.num_requests:
        print("  [예약]")
        print(f"    요청수         : {reserve.num_requests:,}")
        print(f"    실패           : {reserve.num_failures:,}")
        print(f"    p95 (ms)       : {reserve.get_response_time_percentile(0.95):.0f}")
    print("=" * 55 + "\n")
