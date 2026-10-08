FROM grafana/k6:2.3.0 AS loadtool
FROM node:22-bookworm-slim AS reporttool
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
COPY --from=loadtool /usr/bin/k6 /usr/local/bin/k6
COPY --from=reporttool /usr/local/bin/node /usr/local/bin/node
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src ./src
RUN ./gradlew testClasses --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx512m
COPY k6/redis-comparison.js ./k6/redis-comparison.js
COPY scripts/benchmark/redis-report.mjs scripts/benchmark/container-entrypoint.sh ./scripts/benchmark/
RUN sed -i 's/\r$//' scripts/benchmark/container-entrypoint.sh && node --version && k6 version
ENTRYPOINT ["bash", "/app/scripts/benchmark/container-entrypoint.sh"]
