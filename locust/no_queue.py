"""
대기열 없는 시나리오 (Before)
────────────────────────────────────────────────────────
모든 유저가 대기열 활성화 여부와 무관하게 즉시 좌석 조회를 시도한다.
'대기열이 없었다면' 서버가 어떤 상태였을지 재현하는 시나리오다.

기대 결과
  - 1000명(MAX_ACTIVE_USERS) 초과 유저는 즉시 400/401 오류
  - 서버는 유저 수만큼 좌석 조회 요청을 동시에 처리해야 함
  - 응답 시간 증가, 오류율 급등

실행:
  locust -f no_queue.py --users 2000 --spawn-rate 100 --run-time 2m
"""

import random
import uuid

from locust import HttpUser, between, events, task

SCHEDULE_ID = 1


class NoQueueUser(HttpUser):
    host = "http://localhost:8080"
    wait_time = between(0.05, 0.2)  # 짧은 대기 → 급격한 부하

    def on_start(self):
        self.user_id = random.randint(1, 10_000_000)
        self.token = None
        self._enter_queue()

    def _enter_queue(self):
        with self.client.post(
            f"/api/v1/queue/{SCHEDULE_ID}/entry",
            params={"userId": self.user_id},
            name="[Queue] entry",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                self.token = resp.json().get("token", str(uuid.uuid4()))
                resp.success()
            elif resp.status_code == 409:
                resp.success()  # 이미 등록 — 정상
            else:
                self.token = str(uuid.uuid4())  # 실패 시 유효하지 않은 토큰 사용
                resp.failure(f"entry failed: {resp.status_code}")

    @task
    def rush_seat_access(self):
        """활성화 대기 없이 즉시 좌석 조회 — 대기열 미준수 행위 재현"""
        if not self.token:
            return

        with self.client.get(
            f"/api/v1/concerts/{SCHEDULE_ID}/seat-status/snapshot/{self.user_id}",
            headers={"X-Queue-Token": self.token},
            name="[Seat] snapshot (no-queue)",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            elif resp.status_code in (400, 401):
                # 대기열 미통과: 큐가 없었다면 발생하지 않았을 오류
                resp.failure(f"queue-rejected: {resp.status_code}")
            else:
                resp.failure(f"HTTP {resp.status_code}")


# ── 커스텀 통계 출력 ──────────────────────────────────────────────────────────

@events.quitting.add_listener
def print_summary(environment, **kwargs):
    stats = environment.stats
    snapshot = stats.get("/api/v1/concerts/1/seat-status/snapshot", "GET")  # type: ignore

    if snapshot:
        success = snapshot.num_requests - snapshot.num_failures
        total   = snapshot.num_requests
        print("\n" + "=" * 55)
        print("  [No Queue] 좌석 조회 결과 요약")
        print("=" * 55)
        print(f"  총 요청수       : {total:,}")
        print(f"  성공            : {success:,}  ({success/total*100:.1f}%)" if total else "  -")
        print(f"  실패(큐 미통과) : {snapshot.num_failures:,}")
        print(f"  평균 응답 (ms)  : {snapshot.avg_response_time:.0f}")
        print(f"  p95  응답 (ms)  : {snapshot.get_response_time_percentile(0.95):.0f}")
        print(f"  p99  응답 (ms)  : {snapshot.get_response_time_percentile(0.99):.0f}")
        print("=" * 55 + "\n")
