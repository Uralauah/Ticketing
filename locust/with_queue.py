"""
대기열 있는 시나리오 (After)
────────────────────────────────────────────────────────
유저가 대기열에 진입 → 입장 대기(pollAfterMs 간격으로 조회) → 좌석 상태 스냅샷 조회
서버에 동시에 입장하는 유저 수는 ticketing.queue.max-active-users(기본 2,000명)로 제한

상태 흐름:
  WAITING ──(active=true)──▶ ACTIVE ──(세션 5분 경과)──▶ 다시 WAITING

기대 결과:
  - 좌석 조회 성공률 ~100% (입장한 유저만 요청하므로)
  - 대기 중 조회는 순번에 따라 1~10초 간격 → 조회 부하 감소

실행:
  locust -f with_queue.py --users 2000 --spawn-rate 100 --run-time 2m
"""

from locust import HttpUser, between, events, task

from common import SCHEDULE_ID, QueueSession


class QueuedUser(HttpUser):
    host = "http://localhost:8080"
    wait_time = between(0.5, 1.5)  # no_queue와 동일한 대기 시간

    def on_start(self):
        self.queue = QueueSession(self.client)
        self.queue.enter()

    @task(3)
    def poll_queue_status(self):
        """입장 전까지 상태 확인"""
        self.queue.poll()

    @task(1)
    def access_seat(self):
        """입장한 유저만 좌석 상태 조회"""
        if not self.queue.active:
            return

        params, headers = self.queue.auth()
        with self.client.get(
            f"/api/v1/schedule/{SCHEDULE_ID}/seat-status/snapshot",
            params=params,
            headers=headers,
            name="[Seat] snapshot (queued)",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            elif self.queue.handle_session_end(resp):
                resp.success()  # 세션 종료 → 재진입(정상 흐름)
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
