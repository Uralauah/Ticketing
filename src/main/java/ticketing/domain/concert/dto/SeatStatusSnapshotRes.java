package ticketing.domain.concert.dto;

import java.util.List;

public record SeatStatusSnapshotRes(
        long currentVersion,
        List<SeatStatusRes> seats
) {}