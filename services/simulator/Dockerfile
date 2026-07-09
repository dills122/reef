FROM golang:1.23-alpine AS build
WORKDIR /app

ARG TARGETOS
ARG TARGETARCH

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -trimpath -ldflags="-s -w" -o /out/reef-load-tester ./cmd/load-tester

FROM alpine:3.20 AS debug
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef simulator load tester debug image"
RUN apk add --no-cache ca-certificates curl drill iproute2 netcat-openbsd wget
WORKDIR /app
COPY --from=build /out/reef-load-tester /usr/local/bin/reef-load-tester

ENTRYPOINT ["/usr/local/bin/reef-load-tester"]

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef simulator load tester"
WORKDIR /app
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/reef-load-tester /usr/local/bin/reef-load-tester

ENTRYPOINT ["/usr/local/bin/reef-load-tester"]
