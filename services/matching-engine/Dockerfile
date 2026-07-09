FROM golang:1.24-alpine AS build
WORKDIR /app

ARG TARGETOS
ARG TARGETARCH

RUN apk add --no-cache ca-certificates git
ENV GOPROXY=https://proxy.golang.org,direct

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -trimpath -ldflags="-s -w" -o /out/matching-engine ./cmd/matching-engine \
    && CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -trimpath -ldflags="-s -w" -o /out/http-healthcheck ./cmd/http-healthcheck

FROM alpine:3.20 AS debug
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef matching engine debug image"
RUN apk add --no-cache ca-certificates curl drill iproute2 netcat-openbsd wget \
    && addgroup -S -g 10001 reef \
    && adduser -S -D -H -u 10001 -G reef reef
WORKDIR /app
COPY --from=build /out/matching-engine /usr/local/bin/matching-engine
COPY --from=build /out/http-healthcheck /usr/local/bin/http-healthcheck
USER 10001:10001

EXPOSE 8081 9081
ENTRYPOINT ["/usr/local/bin/matching-engine"]

FROM scratch
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef matching engine"
WORKDIR /app
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/matching-engine /usr/local/bin/matching-engine
COPY --from=build /out/http-healthcheck /usr/local/bin/http-healthcheck
USER 10001:10001

EXPOSE 8081 9081
ENTRYPOINT ["/usr/local/bin/matching-engine"]
