-- ============================================================
--  좌석 상태 변경 Outbox
--  예약과 같은 트랜잭션에 기록, 릴레이(SeatStatusPropagator)가 Redis 반영 후 삭제
--  예약 경로 쓰기 비용을 줄이려고 외래 키·보조 인덱스 없음 (릴레이는 PK 순서로 조회)
-- ============================================================

CREATE TABLE seat_status_outbox
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT      NOT NULL,
    seat_id     BIGINT      NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;
