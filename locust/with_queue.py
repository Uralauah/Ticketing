"""
대기열 있는 시나리오 (After)
────────────────────────────────────────────────────────
유저가 대기열에 진입 → 활성화 대기(폴링) → 좌석 조회 순서로 동작한다.
서버에 동시 접근하는 유저 수가 MAX_ACTIVE_USERS(1000명)로 제한된다.

상태 흐름:
  WAITING  ──(active=true)──▶  ACTIVE  ──(session 만료)──▶  WAITING

기대 결과:
  - 좌석 조회 성공률 ~100% (활성 유저만 요청하므로)
  - 서버 동시 좌석 요청 수가 1000건으로 유지됨
  - p95 응답 시간이 no_queue 대비 안정적

실행:
  locust -f with_queue.py --users 2000 --spawn-rate 100 --run-time 2m
"""

import random

from locust import HttpUser, between, events, task

SCHEDULE_ID = 1


class QueuedUser(HttpUser):
    host = "http://localhost:8080"
    wait_time = between(0.5, 1.5)  # no_queue와 동일한 대기 시간

    # ── 초기화 ────────────────────────────────────────────────────────────────

    def on_start(self):
        self.user_id = random.randint(1, 100_000)  # DB에 존재하는 유저 범위
        self.token = None
        self.active = False
        self._enter_queue()

    def _enter_queue(self):
        with self.client.post(
            f"/api/v1/queue/{SCHEDULE_ID}/entry",
            params={"userId": self.user_id},
            name="[Queue] entry",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                self.token = resp.json().get("token")
                self.active = False
                resp.success()
            elif resp.status_code == 409:
                resp.success()  # 이미 등록 — 토큰은 유지
            else:
                resp.failure(f"entry failed: {resp.status_code}")

    # ── 태스크 ────────────────────────────────────────────────────────────────

    @task(3)
    def poll_queue_status(self):
        """대기열 상태 확인 — active=false 동안 반복, active=true 되면 중단"""
        if self.active or not self.token:
            return

        with self.client.get(
            f"/api/v1/queue/{SCHEDULE_ID}/status",
            params={"userId": self.user_id},
            headers={"X-Queue-Token": self.token},  # 하이픈으로 통일
            name="[Queue] status",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                data = resp.json()
                if data.get("active"):
                    self.active = True
                resp.success()
            elif resp.status_code == 401:
                # 토큰 만료 → 재진입
                self.token = None
                self.active = False
                self._enter_queue()
                resp.failure("token expired, re-entered queue")
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(1)
    def access_seat(self):
        """활성 사용자만 좌석 조회 — 세션 만료 시 자동 재진입"""
        if not self.active or not self.token:
            return

        with self.client.get(
            f"/api/v1/schedule/{SCHEDULE_ID}/seat-status/snapshot",
            params={"userId": self.user_id},
            headers={"X-Queue-Token": self.token},
            name="[Seat] snapshot (queued)",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            elif resp.status_code == 410:
                # SESSION_EXPIRED: 5분 세션 종료 → 재진입
                self.active = False
                self.token = None
                self._enter_queue()
                resp.failure("SESSION_EXPIRED, re-entered queue")
            elif resp.status_code in (400, 401):
                self.active = False
                resp.failure(f"auth error: {resp.status_code}")
            else:
                resp.failure(f"HTTP {resp.status_code}")


# ── 커스텀 통계 출력 ──────────────────────────────────────────────────────────

@events.quitting.add_listener
def print_summary(environment, **kwargs):
    stats = environment.stats
    snapshot = stats.get("[Seat] snapshot (queued)", "GET")
    status = stats.get("[Queue] status", "GET")
    entry = stats.get("[Queue] entry", "POST")

    print("\n" + "=" * 55)
    print("  [With Queue] 결과 요약")
    print("=" * 55)

    if entry:
        print("  [대기열 진입]")
        print(f"    총 요청수      : {entry.num_requests:,}")
        print(f"    실패           : {entry.num_failures:,}")
        print()

    if status:
        print("  [대기열 폴링]")
        print(f"    총 폴링 수     : {status.num_requests:,}")
        print(f"    실패           : {status.num_failures:,}")
        print(f"    평균 응답 (ms) : {status.avg_response_time:.0f}")
        print()

    if snapshot:
        total = snapshot.num_requests
        success = total - snapshot.num_failures
        print("  [좌석 조회]")
        print(f"    총 요청수      : {total:,}")
        if total:
            print(f"    성공           : {success:,}  ({success / total * 100:.1f}%)")
        print(f"    실패           : {snapshot.num_failures:,}")
        print(f"    평균 응답 (ms) : {snapshot.avg_response_time:.0f}")
        print(f"    p95  응답 (ms) : {snapshot.get_response_time_percentile(0.95):.0f}")
        print(f"    p99  응답 (ms) : {snapshot.get_response_time_percentile(0.99):.0f}")
    else:
        print("  (좌석 조회 요청 없음 — 아직 활성화된 유저 없음)")

    print("=" * 55 + "\n")