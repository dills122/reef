FROM gradle:9.6.1-jdk21 AS build
WORKDIR /app/services/platform-runtime

COPY services/platform-runtime/gradle gradle
COPY services/platform-runtime/gradlew services/platform-runtime/settings.gradle.kts services/platform-runtime/build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY services/platform-runtime/src src
RUN ./gradlew --no-daemon installDist \
    && rm -f build/install/platform-runtime/bin/*.bat \
    && jlink \
        --add-modules java.se,java.net.http,jdk.charsets,jdk.crypto.ec,jdk.httpserver,jdk.management,jdk.naming.dns,jdk.security.auth,jdk.unsupported,jdk.zipfs \
        --strip-debug \
        --no-header-files \
        --no-man-pages \
        --compress=zip-6 \
        --output /opt/jre

WORKDIR /app/services/arena-control-plane
COPY services/arena-control-plane/settings.gradle.kts services/arena-control-plane/build.gradle.kts ./
COPY services/arena-control-plane/src src
RUN ../platform-runtime/gradlew --no-daemon -p . jar

FROM debian:bookworm-slim
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef platform runtime with the optional Bot Arena control plane"
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /app
RUN groupadd --system --gid 10001 reef \
    && useradd --system --uid 10001 --gid reef --home-dir /app --shell /usr/sbin/nologin reef \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build --chown=reef:reef /opt/jre /opt/java/openjdk
COPY --from=build --chown=reef:reef /app/services/platform-runtime/build/install/platform-runtime /app/platform-runtime
COPY --from=build --chown=reef:reef /app/services/arena-control-plane/build/libs/arena-control-plane.jar /app/platform-runtime/lib/arena-control-plane.jar
COPY --chown=reef:reef services/arena-control-plane/docker-entrypoint.sh /app/arena-platform-runtime
RUN chmod 0555 /app/arena-platform-runtime
USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["/app/arena-platform-runtime"]
