FROM golang:1.25-alpine AS builder

WORKDIR /build
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o calcifer-auth .

FROM alpine:3.19
RUN apk add --no-cache ca-certificates
COPY --from=builder /build/calcifer-auth /usr/local/bin/calcifer-auth

EXPOSE 4181

ENTRYPOINT ["calcifer-auth"]

