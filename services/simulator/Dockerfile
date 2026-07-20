FROM golang:1.26-alpine AS build
WORKDIR /app

ARG TARGETOS
ARG TARGETARCH

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -trimpath -ldflags="-s -w" -o /out/reef-load-tester ./cmd/load-tester

FROM alpine:3.24 AS debug
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef simulator load tester debug image"
RUN apk add --no-cache ca-certificates curl drill iproute2 netcat-openbsd wget \
    && addgroup -S -g 10001 reef \
    && adduser -S -D -H -u 10001 -G reef reef
WORKDIR /app
COPY --from=build /out/reef-load-tester /usr/local/bin/reef-load-tester
USER 10001:10001

ENTRYPOINT ["/usr/local/bin/reef-load-tester"]

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef simulator load tester"
WORKDIR /app
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/reef-load-tester /usr/local/bin/reef-load-tester
USER 10001:10001

ENTRYPOINT ["/usr/local/bin/reef-load-tester"]
