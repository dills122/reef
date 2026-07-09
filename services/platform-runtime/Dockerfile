FROM gradle:9.6.1-jdk21 AS build
WORKDIR /app

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon installDist \
    && rm -f build/install/platform-runtime/bin/*.bat \
    && jlink \
        --add-modules java.se,java.net.http,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.management,jdk.naming.dns,jdk.security.auth,jdk.unsupported,jdk.zipfs \
        --strip-debug \
        --no-header-files \
        --no-man-pages \
        --compress=zip-6 \
        --output /opt/jre

FROM debian:bookworm-slim
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef platform runtime"
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /app
RUN groupadd --system --gid 10001 reef \
    && useradd --system --uid 10001 --gid reef --home-dir /app --shell /usr/sbin/nologin reef \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build --chown=reef:reef /opt/jre /opt/java/openjdk
COPY --from=build --chown=reef:reef /app/build/install/platform-runtime /app/platform-runtime
USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["/app/platform-runtime/bin/platform-runtime"]
