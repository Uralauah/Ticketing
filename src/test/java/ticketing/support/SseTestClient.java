package ticketing.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** 실제 HTTP로 SSE 스트림을 열고 받은 이벤트를 모으는 테스트용 클라이언트 */
public final class SseTestClient implements AutoCloseable {

    /** name이 ":"이면 주석(heartbeat) 줄 */
    public record Event(
            String name,
            String id,
            String data
    ) {
        public boolean isComment() {
            return ":".equals(name);
        }
    }

    private final int statusCode;
    private final java.net.http.HttpHeaders headers;
    private final Stream<String> lines;
    private final BlockingQueue<Event> pending = new LinkedBlockingQueue<>();
    private final java.util.concurrent.CountDownLatch closed =
            new java.util.concurrent.CountDownLatch(1);
    private final List<Event> received = new CopyOnWriteArrayList<>();

    private SseTestClient(HttpResponse<Stream<String>> response) {
        this.statusCode = response.statusCode();
        this.headers = response.headers();
        this.lines = response.body();
        Thread reader = new Thread(this::readLoop, "sse-test-client");
        reader.setDaemon(true);
        reader.start();
    }

    public static SseTestClient connect(URI uri) {
        return connect(uri, Map.of());
    }

    public static SseTestClient connect(URI uri, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .GET();
        headers.forEach(request::header);
        try {
            // 헤더를 받는 즉시 반환, 본문은 도착하는 대로 읽음
            return new SseTestClient(HttpClient.newHttpClient().send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofLines()
            ));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public int statusCode() {
        return statusCode;
    }

    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    /** 조건에 맞는 이벤트가 올 때까지 대기, 앞서 온 다른 이벤트는 건너뜀 */
    public Event awaitEvent(Predicate<Event> condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                Event event = remaining > 0 ? pending.poll(remaining, TimeUnit.NANOSECONDS) : null;
                if (event == null) {
                    throw new AssertionError(
                            "이벤트를 " + timeout + " 안에 받지 못했습니다. 받은 이벤트: " + received
                    );
                }
                if (condition.test(event)) {
                    return event;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public Event awaitEvent(String name, Duration timeout) {
        return awaitEvent(e -> name.equals(e.name()), timeout);
    }

    public List<Event> received() {
        return List.copyOf(received);
    }

    @Override
    public void close() {
        lines.close();
    }

    /** 서버가 스트림을 끝낼 때까지 대기 */
    public boolean awaitClosed(Duration timeout) {
        try {
            return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void readLoop() {
        try {
            readLines();
        } finally {
            closed.countDown();
        }
    }

    private void readLines() {
        List<String> block = new ArrayList<>();
        try {
            lines.forEach(line -> {
                if (!line.isEmpty()) {
                    block.add(line);
                    return;
                }
                dispatch(block);
                block.clear();
            });
        } catch (RuntimeException ignored) {
            // close()로 스트림을 닫으면 읽기가 예외로 끝남
        }
    }

    private void dispatch(List<String> block) {
        if (block.isEmpty()) {
            return;
        }
        String name = "message";
        String id = null;
        StringBuilder data = new StringBuilder();
        boolean commentOnly = true;
        for (String line : block) {
            if (line.startsWith(":")) {
                continue;
            }
            commentOnly = false;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            switch (field) {
                case "event" -> name = value;
                case "id" -> id = value;
                case "data" -> data.append(data.isEmpty() ? "" : "\n").append(value);
                default -> {}
            }
        }
        Event event = commentOnly ? new Event(":", null, String.join("\n", block))
                : new Event(name, id, data.toString());
        received.add(event);
        pending.add(event);
    }
}
