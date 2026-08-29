# Alpine 기반 Temurin 17은 amd64만 제공 → Ubuntu 기반 이미지 사용

# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

# 의존성 레이어 캐싱 — src 변경 시에도 의존성 재다운로드 방지
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q

COPY src src
RUN ./gradlew bootJar -x test --no-daemon -q

# ── Stage 2: Run ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system appgroup && useradd --system --gid appgroup appuser
USER appuser

COPY --from=builder /workspace/build/libs/*.jar app.jar

# -XX:+UseContainerSupport  : 컨테이너 메모리 제한 자동 인식
# -XX:MaxRAMPercentage=75   : 컨테이너 RAM의 75%를 힙으로 사용
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75", \
  "-jar", "app.jar"]
