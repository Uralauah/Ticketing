package ticketing.domain.concert.dto;

import java.util.List;

public record SeatStatusDeltaRes(
        long currentVersion,
        boolean fullSyncRequired,
        List<SeatStatusChangeRes> changes
) {}