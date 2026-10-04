FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/libs/*.jar app.jar
COPY deploy/backend-entrypoint.sh /app/backend-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["sh", "/app/backend-entrypoint.sh"]
