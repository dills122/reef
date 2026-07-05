FROM golang:1.23-alpine AS build
WORKDIR /app

ARG TARGETOS
ARG TARGETARCH

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} go build -o /out/reef-load-tester ./cmd/load-tester

FROM alpine:3.20
LABEL org.opencontainers.image.source="https://github.com/dills122/reef"
LABEL org.opencontainers.image.description="Reef simulator load tester"
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=build /out/reef-load-tester /usr/local/bin/reef-load-tester

ENTRYPOINT ["/usr/local/bin/reef-load-tester"]
