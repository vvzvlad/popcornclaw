# PicoClaw v0.3.1 for the TV box (linux/armv7) with tool-status.patch applied.
# Build flags mirror the "picoclaw" build in upstream's .goreleaser.yaml; its
# `go generate ./...` hook is skipped because v0.3.1 has no go:generate directives.
FROM golang:1.25.14-bookworm AS build
RUN git clone --depth 1 --branch v0.3.1 https://github.com/sipeed/picoclaw.git /src
WORKDIR /src
COPY tool-status.patch /tmp/tool-status.patch
RUN git apply /tmp/tool-status.patch
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -tags goolm,stdjson \
    -ldflags "-s -w -X github.com/sipeed/picoclaw/pkg/config.Version=0.3.1-tvagent" \
    -o /picoclaw ./cmd/picoclaw

FROM scratch
COPY --from=build /picoclaw /picoclaw
