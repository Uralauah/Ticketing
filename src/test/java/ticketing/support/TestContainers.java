package ticketing.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/** 테스트 JVM 전체에서 공유하는 컨테이너 (한 번만 띄움) */
public final class TestContainers {

    private TestContainers() {}

    public static MySQLContainer<?> mysql() {
        return MySqlHolder.CONTAINER;
    }

    public static GenericContainer<?> redis() {
        return RedisHolder.CONTAINER;
    }

    private static final class MySqlHolder {
        static final MySQLContainer<?> CONTAINER = new MySQLContainer<>("mysql:8.0");

        static {
            CONTAINER.start();
        }
    }

    private static final class RedisHolder {
        static final GenericContainer<?> CONTAINER = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);

        static {
            CONTAINER.start();
        }
    }
}
