package ticketing.global.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PreSerializedResponsesTest {

    @Test
    @DisplayName("Accept-Encoding에 gzip(또는 *) 포함, q=0이 아니면 압축본 반환")
    void gzip_수용_여부_판별() {
        assertThat(PreSerializedResponses.acceptsGzip("gzip, deflate, br")).isTrue();
        assertThat(PreSerializedResponses.acceptsGzip("br;q=1.0, GZIP;q=0.5")).isTrue();
        assertThat(PreSerializedResponses.acceptsGzip("*")).isTrue();

        assertThat(PreSerializedResponses.acceptsGzip(null)).isFalse();
        assertThat(PreSerializedResponses.acceptsGzip("identity")).isFalse();
        assertThat(PreSerializedResponses.acceptsGzip("gzip;q=0")).isFalse();
        assertThat(PreSerializedResponses.acceptsGzip("deflate, gzip;q=0.0")).isFalse();
    }
}
