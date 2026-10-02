FROM gradle:9.8.0-jdk21 AS build
WORKDIR /app/services/platform-runtime

COPY services/platform-runtime/gradle gradle
COPY services/platform-runtime/gradlew services/platform-runtime/settings.gradle.kts services/platform-runtime/build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies

COPY services/platform-runtime/src src
COPY scripts/dev/db/migrations/runtime/0073_runtime_order_run_identity.sql /app/scripts/dev/db/migrations/runtime/0073_runtime_order_run_identity.sql
COPY scripts/dev/db/migrations/runtime/0074_matching_ioc_cancellation.sql /app/scripts/dev/db/migrations/runtime/0074_matching_ioc_cancellation.sql
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
COPY --from=build --chown=reef:reef /app/services/platform-runtime/build/install/platform-runtime /app/platform-runtime
RUN install -d -o reef -g reef /app/calcify-state
USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["/app/platform-runtime/bin/platform-runtime"]
