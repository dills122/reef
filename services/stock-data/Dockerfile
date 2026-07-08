FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef stock-data seeding service"
WORKDIR /app
RUN groupadd --system --gid 10001 reef \
    && useradd --system --uid 10001 --gid reef --home-dir /app --shell /usr/sbin/nologin reef \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build --chown=reef:reef /app/build/install/stock-data /app/stock-data
USER 10001:10001

EXPOSE 8081
ENTRYPOINT ["/app/stock-data/bin/stock-data"]
