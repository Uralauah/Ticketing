package ticketing.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 같은 작업을 N개 스레드에서 동시에 출발시키고 성공 수와 실패 예외 수집 */
public final class ConcurrentRunner {

    private ConcurrentRunner() {}

    @FunctionalInterface
    public interface Task {
        void run(int index) throws Exception;
    }

    public record Result(
            int successCount,
            List<Throwable> failures
    ) {}

    public static Result run(int threads, Task task) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.run(index);
                        success.incrementAndGet();
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            start.countDown();
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행이 30초 안에 끝나지 않았습니다.");
            }
        } finally {
            executor.shutdownNow();
        }
        return new Result(success.get(), List.copyOf(failures));
    }
}
