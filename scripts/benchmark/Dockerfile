FROM node:22-bookworm-slim AS reporttool
FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
COPY --from=reporttool /usr/local/bin/node /usr/local/bin/node
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
COPY src ./src
RUN ./gradlew testClasses bootJar --no-daemon --console=plain -Dorg.gradle.jvmargs=-Xmx512m
COPY k6 ./k6
COPY scripts/benchmark/redis-report.mjs scripts/benchmark/query-report.mjs scripts/benchmark/suites.json scripts/benchmark/container-entrypoint.sh ./scripts/benchmark/
RUN sed -i 's/\r$//' scripts/benchmark/container-entrypoint.sh && node --version
ENTRYPOINT ["bash", "/app/scripts/benchmark/container-entrypoint.sh"]
