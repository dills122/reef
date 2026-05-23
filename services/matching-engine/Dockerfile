FROM --platform=$BUILDPLATFORM golang:1.23-alpine AS build
WORKDIR /app

ARG TARGETOS
ARG TARGETARCH

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH} go build -o /out/matching-engine ./cmd/matching-engine

FROM alpine:3.20
RUN apk add --no-cache ca-certificates wget
WORKDIR /app
COPY --from=build /out/matching-engine /usr/local/bin/matching-engine

EXPOSE 8081 9081
ENTRYPOINT ["/usr/local/bin/matching-engine"]
