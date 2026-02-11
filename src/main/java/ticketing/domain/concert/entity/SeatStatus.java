package ticketing.domain.concert.entity;

public enum SeatStatus {
    AVAILABLE, // 예약 가능
    RESERVED,  // 예약 중 (결제 대기)
    SOLD_OUT   // 판매 완료 (결제 완료)
}