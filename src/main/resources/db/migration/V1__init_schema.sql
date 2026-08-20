-- ============================================================
--  초기 스키마
-- ============================================================

CREATE TABLE users
(
    id    BIGINT       NOT NULL AUTO_INCREMENT,
    name  VARCHAR(255) NOT NULL,
    point INT          NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE concert
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    title       VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE concert_schedule
(
    schedule_id  BIGINT      NOT NULL AUTO_INCREMENT,
    concert_id   BIGINT      NOT NULL,
    concert_date DATETIME(6) NOT NULL,
    total_seats  INT         NOT NULL,
    PRIMARY KEY (schedule_id),
    CONSTRAINT fk_concert_schedule_concert FOREIGN KEY (concert_id) REFERENCES concert (id)
) ENGINE = InnoDB;

CREATE TABLE seat
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT       NOT NULL,
    seat_num    VARCHAR(255) NOT NULL,
    price       INT          NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    -- (schedule_id) 단일 인덱스는 이 인덱스의 접두로 대체
    CONSTRAINT uk_seat_schedule_seat_num UNIQUE (schedule_id, seat_num),
    CONSTRAINT fk_seat_schedule FOREIGN KEY (schedule_id) REFERENCES concert_schedule (schedule_id)
) ENGINE = InnoDB;

CREATE TABLE reservation
(
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    user_id          BIGINT      NOT NULL,
    seat_id          BIGINT      NOT NULL,
    reservation_time DATETIME(6) NOT NULL,
    price            INT         NOT NULL,
    status           VARCHAR(20) NOT NULL,
    -- 활성 예약일 때만 seat_id를 갖는 생성 컬럼 (MySQL은 부분 인덱스 미지원)
    -- NULL은 UNIQUE 중복 허용 → 취소된 예약끼리는 충돌하지 않음
    active_seat_id   BIGINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING', 'PAID') THEN seat_id END) STORED,
    PRIMARY KEY (id),
    -- 더블 부킹 최종 방어선
    CONSTRAINT uk_reservation_active_seat UNIQUE (active_seat_id),
    CONSTRAINT fk_reservation_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_reservation_seat FOREIGN KEY (seat_id) REFERENCES seat (id)
) ENGINE = InnoDB;
