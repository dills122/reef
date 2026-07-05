FROM golang:1.24-alpine AS build
WORKDIR /app

ARG TARGETOS
ARG TARGETARCH

RUN apk add --no-cache git
ENV GOPROXY=https://proxy.golang.org,direct

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -o /out/matching-engine ./cmd/matching-engine

FROM alpine:3.20
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef matching engine"
RUN apk add --no-cache ca-certificates wget
WORKDIR /app
COPY --from=build /out/matching-engine /usr/local/bin/matching-engine

EXPOSE 8081 9081
ENTRYPOINT ["/usr/local/bin/matching-engine"]
