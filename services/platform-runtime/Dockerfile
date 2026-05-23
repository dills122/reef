FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/build/install/platform-runtime /app/platform-runtime

EXPOSE 8080
ENTRYPOINT ["/app/platform-runtime/bin/platform-runtime"]
