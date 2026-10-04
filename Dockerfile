FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY deploy/backend-entrypoint.sh /app/backend-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["sh", "/app/backend-entrypoint.sh"]

# CI and AWS use the exact JAR produced by the backend test job.
FROM runtime AS prebuilt
COPY build/deploy/app.jar /app/app.jar

# Keep source builds available for local docker compose users.
FROM runtime AS source
COPY --from=build /app/build/libs/*.jar /app/app.jar
