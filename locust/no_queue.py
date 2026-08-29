"""
대기열 없는 시나리오 (Before)
────────────────────────────────────────────────────────
모든 유저가 대기열 없이 즉시 좌석 조회 시도
'대기열이 없었다면' 서버가 어떤 상태였을지 재현하는 시나리오다.

서버는 perf 프로필로 실행 (대기열 검증 없는 비교용 엔드포인트)
  SNAPSHOT_MODE=legacy: 캐시 이전 방식 엔드포인트 호출

기대 결과:
  - 2000명이 동시에 좌석 조회 → 서버 부하 급등
  - 응답 시간 증가, 타임아웃·오류율 급등

실행:
  locust -f no_queue.py --users 2000 --spawn-rate 100 --run-time 2m
"""

import os
import random

from locust import HttpUser, between, events, task

from common import SCHEDULE_ID, USER_ID_RANGE

SNAPSHOT_PATH = f"/api/v1/experiment/schedule/{SCHEDULE_ID}/seat-status/snapshot"
if os.environ.get("SNAPSHOT_MODE") == "legacy":
    SNAPSHOT_PATH += "/legacy"


class NoQueueUser(HttpUser):
    host = "http://localhost:8080"
    wait_time = between(0.5, 1.5)  # with_queue와 동일한 대기 시간

    def on_start(self):
        self.user_id = random.randint(*USER_ID_RANGE)  # 시드 데이터의 유저 범위

    @task
    def rush_seat_access(self):
        """큐 검증 없이 좌석 조회 엔드포인트 직접 호출"""
        with self.client.get(
            SNAPSHOT_PATH,
            name="[Seat] direct (no-queue)",
            catch_response=True,
        ) as resp:
            if resp.status_code == 200:
                resp.success()
            elif resp.status_code == 503:
                resp.failure("server overloaded")
            else:
                resp.failure(f"HTTP {resp.status_code}")


# ── 커스텀 통계 출력 ──────────────────────────────────────────────────────────

@events.quitting.add_listener
def print_summary(environment, **kwargs):
    stats = environment.stats
    snapshot = stats.get("[Seat] direct (no-queue)", "GET")

    print("\n" + "=" * 55)
    print("  [No Queue] 좌석 조회 결과 요약")
    print("=" * 55)

    if snapshot:
        total = snapshot.num_requests
        success = total - snapshot.num_failures

        print(f"  총 요청수       : {total:,}")
        if total:
            print(f"  성공            : {success:,}  ({success / total * 100:.1f}%)")
        print(f"  실패            : {snapshot.num_failures:,}")
        print(f"  평균 응답 (ms)  : {snapshot.avg_response_time:.0f}")
        print(f"  p95  응답 (ms)  : {snapshot.get_response_time_percentile(0.95):.0f}")
        print(f"  p99  응답 (ms)  : {snapshot.get_response_time_percentile(0.99):.0f}")
    else:
        print("  (좌석 조회 요청 없음)")

    print("=" * 55 + "\n")