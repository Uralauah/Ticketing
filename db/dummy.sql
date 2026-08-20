-- ============================================================
--  Ticketing 부하 테스트용 더미 데이터
-- ============================================================
--  대상 DB  : ticketing
--  전제 조건 : Spring Boot 기동 시 Flyway(db/migration)가 스키마 생성 완료
--  Locust   : SCHEDULE_ID = 1 (no_queue.py / with_queue.py 공통)
--
--  데이터 규모
--    users            : 100,000건  (포인트 1,000,000원)
--    concert          :       1건
--    concert_schedule :       3건  (id 1·2·3)
--    seat             :  10,500건  (schedule_id=1 기준)
--      Zone A VIP  10행 ×  50열 =   500석  ₩150,000
--      Zone B      20행 × 100열 = 2,000석  ₩ 99,000
--      Zone C      30행 × 100열 = 3,000석  ₩ 77,000
--      Zone D      50행 × 100열 = 5,000석  ₩ 55,000
--  ⚠  재실행 시 TRUNCATE 섹션 주석 해제 후 실행
-- ============================================================

USE ticketing;

-- ------------------------------------------------------------
-- 0. 재실행용 초기화 (최초 실행 시 불필요 — 필요하면 주석 해제)
-- ------------------------------------------------------------
-- SET FOREIGN_KEY_CHECKS = 0;
-- TRUNCATE TABLE reservation;
-- TRUNCATE TABLE seat;
-- TRUNCATE TABLE concert_schedule;
-- TRUNCATE TABLE concert;
-- TRUNCATE TABLE users;
-- SET FOREIGN_KEY_CHECKS = 1;
-- ALTER TABLE users AUTO_INCREMENT = 1;

-- ------------------------------------------------------------
-- 1. 유저 (100,000명)
--    재귀 깊이 제한을 피하기 위해 10짜리 테이블 5개 크로스 조인 사용
--    → 10^5 = 100,000건, 포인트 각 1,000,000원
-- ------------------------------------------------------------
INSERT INTO users (name, point)
WITH
    d1 AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
           UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9),
    d2 AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
           UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9),
    d3 AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
           UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9),
    d4 AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
           UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9),
    d5 AS (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
           UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9)
SELECT
    CONCAT('user_', d1.n + d2.n*10 + d3.n*100 + d4.n*1000 + d5.n*10000 + 1),
    1000000
FROM d1, d2, d3, d4, d5;
-- users.id = 1 ~ 100,000

-- ------------------------------------------------------------
-- 2. 공연
-- ------------------------------------------------------------
INSERT INTO concert (title, description) VALUES (
    '아이유 콘서트 THE GOLDEN HOUR 2026',
    '전국 투어 콘서트 — 서울 KSPO DOME'
);
-- concert.id = 1

-- ------------------------------------------------------------
-- 3. 공연 일정  (Locust SCHEDULE_ID = 1 과 일치)
-- ------------------------------------------------------------
INSERT INTO concert_schedule (concert_id, concert_date, total_seats) VALUES
    (1, '2026-07-12 17:00:00', 10500),   -- schedule_id = 1  ← Locust 기본값
    (1, '2026-07-13 17:00:00', 10500),   -- schedule_id = 2
    (1, '2026-07-19 17:00:00', 10500);   -- schedule_id = 3

-- ------------------------------------------------------------
-- 4. 좌석 생성 (schedule_id = 1)
--    MySQL 8.0 재귀 CTE 사용
-- ------------------------------------------------------------

-- Zone A : VIP  — 10행 × 50열 = 500석
INSERT INTO seat (schedule_id, seat_num, price, status)
WITH RECURSIVE
    r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 10),
    c AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM c WHERE n < 50)
SELECT 1,
       CONCAT('A', r.n, '-', c.n),
       150000,
       'AVAILABLE'
FROM r
         CROSS JOIN c;

-- Zone B : 일반 1등급 — 20행 × 100열 = 2,000석
INSERT INTO seat (schedule_id, seat_num, price, status)
WITH RECURSIVE
    r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 20),
    c AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM c WHERE n < 100)
SELECT 1,
       CONCAT('B', r.n, '-', c.n),
       99000,
       'AVAILABLE'
FROM r
         CROSS JOIN c;

-- Zone C : 일반 2등급 — 30행 × 100열 = 3,000석
INSERT INTO seat (schedule_id, seat_num, price, status)
WITH RECURSIVE
    r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 30),
    c AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM c WHERE n < 100)
SELECT 1,
       CONCAT('C', r.n, '-', c.n),
       77000,
       'AVAILABLE'
FROM r
         CROSS JOIN c;

-- Zone D : 일반 3등급 — 50행 × 100열 = 5,000석
INSERT INTO seat (schedule_id, seat_num, price, status)
WITH RECURSIVE
    r AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM r WHERE n < 50),
    c AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM c WHERE n < 100)
SELECT 1,
       CONCAT('D', r.n, '-', c.n),
       55000,
       'AVAILABLE'
FROM r
         CROSS JOIN c;

-- ------------------------------------------------------------
-- 5. 검증 쿼리
-- ------------------------------------------------------------
SELECT '=== 데이터 생성 완료 ===' AS msg;

SELECT
    'users'            AS 테이블, COUNT(*) AS 건수 FROM users
UNION ALL
SELECT 'concert',          COUNT(*) FROM concert
UNION ALL
SELECT 'concert_schedule', COUNT(*) FROM concert_schedule
UNION ALL
SELECT 'seat (전체)',       COUNT(*) FROM seat
UNION ALL
SELECT 'seat (schedule=1)', COUNT(*) FROM seat WHERE schedule_id = 1
UNION ALL
SELECT 'seat (Zone A)',     COUNT(*) FROM seat WHERE seat_num LIKE 'A%'
UNION ALL
SELECT 'seat (Zone B)',     COUNT(*) FROM seat WHERE seat_num LIKE 'B%'
UNION ALL
SELECT 'seat (Zone C)',     COUNT(*) FROM seat WHERE seat_num LIKE 'C%'
UNION ALL
SELECT 'seat (Zone D)',     COUNT(*) FROM seat WHERE seat_num LIKE 'D%';
