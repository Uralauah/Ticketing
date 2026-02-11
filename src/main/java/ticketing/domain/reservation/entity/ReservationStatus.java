package ticketing.domain.reservation.entity;

public enum ReservationStatus {
    PENDING, //결제 대기
    PAID, // 결제 완료
    CANCELLED // 결제 취소
}
