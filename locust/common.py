"""
Locust 시나리오 공통: 대기열 진입 → 입장 대기 → 입장 토큰 보관

- 서버가 주는 pollAfterMs 간격으로 상태 조회, 세션이 끝나면 재진입
- 사용자 ID 범위는 시드 데이터(db/seed/load-test-data.sql)와 동일
- 입장 대기 시간 기록
  [Queue] wait until active : 진입부터 입장까지
  [Queue] first admission   : 사용자별 첫 입장까지
"""

import random
import time

from locust import events

SCHEDULE_ID = 1
USER_ID_RANGE = (1, 100_000)
QUEUE_TOKEN_HEADER = "X-Queue-Token"


class QueueSession:
    """한 가상 사용자의 대기열 상태"""

    def __init__(self, client, schedule_id=SCHEDULE_ID):
        self.client = client
        self.schedule_id = schedule_id
        self.user_id = random.randint(*USER_ID_RANGE)
        self.token = None
        self.active = False
        self.next_poll_at = 0.0
        self.entered_at = None
        self.first_entered_at = None
        self.ever_admitted = False

    # ── 대기열 진입 ──────────────────────────────────────────────────────────

    def enter(self):
        with self.client.post(
            f"/api/v1/queue/{self.schedule_id}/entry",
            params={"userId": self.user_id},
            name="[Queue] entry",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                body = resp.json()
                self.token = body["token"]
                self.active = False
                self.entered_at = time.time()
                if self.first_entered_at is None:
                    self.first_entered_at = self.entered_at
                self.next_poll_at = time.time() + body.get("pollAfterMs", 1_000) / 1_000
                resp.success()
            elif resp.status_code == 409:
                # 무작위 ID 충돌 → 다른 ID로 재시도
                self.user_id = random.randint(*USER_ID_RANGE)
                resp.success()
            else:
                resp.failure(f"entry failed: {resp.status_code}")

    # ── 입장 대기 ────────────────────────────────────────────────────────────

    def poll(self):
        """조회 시각이 됐으면 상태 확인, 입장했으면 True 반환"""
        if self.active:
            return True
        if not self.token:
            self.enter()
            return False
        if time.time() < self.next_poll_at:
            return False

        with self.client.get(
            f"/api/v1/queue/{self.schedule_id}/status",
            params={"userId": self.user_id},
            headers={QUEUE_TOKEN_HEADER: self.token},
            name="[Queue] status",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                body = resp.json()
                self.active = body.get("active", False)
                self.next_poll_at = time.time() + body.get("pollAfterMs", 1_000) / 1_000
                resp.success()
                if self.active:
                    self._record_admission()
            elif resp.status_code in (401, 410):
                # 세션 만료 또는 대기열에서 정리됨 → 재진입
                self.reset()
                resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")
        return self.active

    def _record_admission(self):
        now = time.time()
        record_timing("[Queue] wait until active", (now - self.entered_at) * 1_000)
        if not self.ever_admitted:
            self.ever_admitted = True
            record_timing("[Queue] first admission", (now - self.first_entered_at) * 1_000)

    def reset(self):
        self.token = None
        self.active = False

    def auth(self):
        """좌석 상태·예약 API에 붙일 사용자 식별 값"""
        return {"userId": self.user_id}, {QUEUE_TOKEN_HEADER: self.token}

    def handle_session_end(self, resp):
        """입장 세션 종료로 거절된 응답이면 재진입하도록 초기화 후 True 반환"""
        if resp.status_code in (400, 401) and _error_code(resp) in ("QUEUE-400-001", "QUEUE-401-001"):
            self.reset()
            return True
        return False


def record_timing(name, elapsed_ms):
    events.request.fire(request_type="QUEUE", name=name, response_time=elapsed_ms,
                        response_length=0, exception=None, context={})


def _error_code(resp):
    try:
        return resp.json().get("code")
    except ValueError:
        return None
