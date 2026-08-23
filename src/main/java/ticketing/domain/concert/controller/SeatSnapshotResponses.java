package ticketing.domain.concert.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import ticketing.domain.concert.service.SeatSnapshotCache.Snapshot;

/** 미리 직렬화한 스냅샷 바이트 응답, ETag가 같으면 304 */
final class SeatSnapshotResponses {

    private SeatSnapshotResponses() {}

    /** 304면 null 반환 */
    static ResponseEntity<byte[]> of(Snapshot snapshot, String acceptEncoding, WebRequest request) {
        if (request.checkNotModified(snapshot.eTag())) {
            return null;
        }

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .eTag(snapshot.eTag())
                // 입장한 사용자 전용 → 공유 캐시 금지, 매번 ETag로 재검증
                .cacheControl(CacheControl.noCache().cachePrivate())
                .varyBy(HttpHeaders.ACCEPT_ENCODING)
                .contentType(MediaType.APPLICATION_JSON);

        // 미리 압축해 둔 본문 사용
        if (acceptsGzip(acceptEncoding)) {
            return ok.header(HttpHeaders.CONTENT_ENCODING, "gzip").body(snapshot.gzipped());
        }
        return ok.body(snapshot.json());
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) return false;
        for (String part : acceptEncoding.split(",")) {
            String[] tokens = part.trim().split(";");
            String coding = tokens[0].trim();
            if (!coding.equalsIgnoreCase("gzip") && !coding.equals("*")) continue;
            return !hasZeroQuality(tokens);
        }
        return false;
    }

    private static boolean hasZeroQuality(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            String param = tokens[i].trim();
            if (!param.startsWith("q=")) continue;
            try {
                return Double.parseDouble(param.substring(2)) == 0.0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
