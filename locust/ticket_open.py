"""
티켓 오픈 시나리오 (매진까지)
────────────────────────────────────────────────────────
좌석(10,500석)보다 많은 사용자가 오픈 직후 몰려 좌석을 사는 흐름 재현

사용자 한 명의 흐름:
  대기열 진입 → pollAfterMs 간격으로 상태 조회 → 입장
  → 좌석 배치도·스냅샷 조회 → 1~3초 고민 → 선호 구역(A > B > C > D)의 빈 좌석 예약
  → 409(다른 사람이 먼저 예약)면 다른 좌석으로 최대 5번 재시도 → 성공하면 종료(입장 슬롯은 서버가 반납)
  → 끝내 실패하거나 매진이면 대기열에서 나가고 종료

측정 지표:
  [Ticket] time to ticket : 처음 진입부터 예약 성공까지
  [Ticket] gave up        : 재시도 끝에 포기
  [Ticket] sold out       : 입장했지만 남은 좌석이 없음

주의:
  - Tomcat max-connections(기본 8,192)를 사용자 수보다 크게 설정
  - 재측정 시 데이터 초기화

실행 (사용자 15,000명을 30초에 걸쳐 투입):
  locust -f ticket_open.py --headless -u 15000 -r 500 -t 12m
"""

import random
import time

from locust import HttpUser, constant, task
from locust.exception import StopUser

from common import SCHEDULE_ID, QueueSession, record_timing

MAX_ATTEMPTS = 5
ZONE_PREFERENCE = (("A", 0.4), ("B", 0.3), ("C", 0.2), ("D", 0.1))


class SeatBoard:
    """프로세스 안에서 공유하는 빈 좌석 목록 (1초에 한 번 갱신)"""
    zone_of = None          # seatId -> 구역
    available = None        # 구역 -> set(seatId)
    refreshed_at = 0.0

    @classmethod
    def load_map(cls, seat_map_json):
        if cls.zone_of is None:
            cls.zone_of = {s["seatId"]: s["seatNum"][0] for s in seat_map_json["seats"]}

    @classmethod
    def stale(cls):
        return cls.available is None or time.time() - cls.refreshed_at > 1.0

    @classmethod
    def refresh(cls, snapshot_json):
        board = {zone: set() for zone, _ in ZONE_PREFERENCE}
        for seat in snapshot_json["seats"]:
            if seat["status"] == "AVAILABLE":
                board[cls.zone_of.get(seat["seatId"], "D")].add(seat["seatId"])
        cls.available = board
        cls.refreshed_at = time.time()

    @classmethod
    def pick(cls):
        zones = [zone for zone, _ in ZONE_PREFERENCE]
        first = random.choices(zones, weights=[w for _, w in ZONE_PREFERENCE])[0]
        for zone in [first] + [z for z in zones if z != first]:
            seats = cls.available.get(zone)
            if seats:
                return random.choice(tuple(seats))
        return None

    @classmethod
    def taken(cls, seat_id):
        for seats in cls.available.values():
            seats.discard(seat_id)


class TicketBuyer(HttpUser):
    host = "http://localhost:8080"
    wait_time = constant(0)

    def on_start(self):
        self.queue = QueueSession(self.client)
        self.queue.enter()
        self.browsed = False
        self.attempts = 0

    @task
    def journey(self):
        queue = self.queue
        if not queue.active:
            # 다음 조회 시각까지 대기 (gevent라 다른 사용자를 막지 않음)
            time.sleep(max(0.0, min(queue.next_poll_at - time.time(), 10.0)))
            queue.poll()
            return

        if not self.browsed:
            self._browse()
            self.browsed = True
            time.sleep(random.uniform(1.0, 3.0))  # 좌석을 고르는 시간
            return

        self._reserve()

    def _browse(self):
        params, headers = self.queue.auth()
        with self.client.get(f"/api/v1/schedule/{SCHEDULE_ID}/seat-map", name="[Seat] seat-map",
                             catch_response=True) as resp:
            if resp.status_code == 200:
                if SeatBoard.zone_of is None:
                    SeatBoard.load_map(resp.json())
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")
        self._snapshot(params, headers)

    def _snapshot(self, params, headers):
        with self.client.get(f"/api/v1/schedule/{SCHEDULE_ID}/seat-status/snapshot", params=params,
                             headers=headers, name="[Seat] snapshot", catch_response=True) as resp:
            if resp.status_code == 200:
                if SeatBoard.stale() and SeatBoard.zone_of is not None:
                    SeatBoard.refresh(resp.json())
                resp.success()
            elif self.queue.handle_session_end(resp):
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    def _reserve(self):
        if SeatBoard.available is None:
            self._browse()
            return
        seat_id = SeatBoard.pick()
        if seat_id is None:
            record_timing("[Ticket] sold out", (time.time() - self.queue.first_entered_at) * 1_000)
            self._leave()

        params, headers = self.queue.auth()
        body = {"userId": params["userId"], "scheduleId": SCHEDULE_ID, "seatId": seat_id}
        with self.client.post("/api/v1/reservation", json=body, headers=headers,
                              name="[Reserve] POST", catch_response=True) as resp:
            if resp.status_code == 200:
                resp.success()
                SeatBoard.taken(seat_id)
                record_timing("[Ticket] time to ticket", (time.time() - self.queue.first_entered_at) * 1_000)
                raise StopUser()
            if resp.status_code == 409:
                resp.success()  # 다른 사람이 먼저 예약한 좌석(정상 경합)
                SeatBoard.taken(seat_id)
                self.attempts += 1
            elif self.queue.handle_session_end(resp):
                resp.success()
                self.browsed = False
                return
            else:
                resp.failure(f"HTTP {resp.status_code}")
                self.attempts += 1

        if self.attempts >= MAX_ATTEMPTS:
            record_timing("[Ticket] gave up", (time.time() - self.queue.first_entered_at) * 1_000)
            self._leave()
        if self.attempts % 2 == 0:
            params, headers = self.queue.auth()
            self._snapshot(params, headers)  # 두 번 실패할 때마다 최신 좌석 상태 재조회
        time.sleep(random.uniform(0.3, 1.0))

    def _leave(self):
        params, headers = self.queue.auth()
        self.client.delete(f"/api/v1/queue/{SCHEDULE_ID}/entry", params=params, headers=headers,
                           name="[Queue] leave")
        raise StopUser()

