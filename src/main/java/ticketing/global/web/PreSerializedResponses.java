package ticketing.global.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

/** 미리 직렬화한 바이트 응답, ETag가 같으면 304 */
public final class PreSerializedResponses {
    private PreSerializedResponses() {}

    /** 304면 null 반환 */
    public static ResponseEntity<byte[]> of(PreSerializedBody body,
                                            CacheControl cacheControl,
                                            String acceptEncoding,
                                            WebRequest request) {
        if (request.checkNotModified(body.eTag())) {
            return null;
        }

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .eTag(body.eTag())
                .cacheControl(cacheControl)
                .varyBy(HttpHeaders.ACCEPT_ENCODING)
                .contentType(MediaType.APPLICATION_JSON);

        // 미리 압축해 둔 본문 사용
        if (acceptsGzip(acceptEncoding)) {
            return ok.header(HttpHeaders.CONTENT_ENCODING, "gzip").body(body.gzipped());
        }
        return ok.body(body.json());
    }

    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) {
            return false;
        }
        for (String part : acceptEncoding.split(",")) {
            String[] tokens = part.trim().split(";");
            String coding = tokens[0].trim();
            if (!coding.equalsIgnoreCase("gzip") && !coding.equals("*")) {
                continue;
            }
            return !hasZeroQuality(tokens);
        }
        return false;
    }

    private static boolean hasZeroQuality(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            String param = tokens[i].trim();
            if (!param.startsWith("q=")) {
                continue;
            }
            try {
                return Double.parseDouble(param.substring(2)) == 0.0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
