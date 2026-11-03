# PicoClaw v0.3.1 for the TV box (linux/armv7) with our patches applied in order:
# tool-status, asr-proxy, image-shrink, tool-feedback, keep-logs, shared-session, pico-audio.
# Build flags mirror the "picoclaw" build in upstream's .goreleaser.yaml; its
# `go generate ./...` hook is skipped because v0.3.1 has no go:generate directives.
FROM golang:1.25.14-bookworm AS build
RUN git clone --depth 1 --branch v0.3.1 https://github.com/sipeed/picoclaw.git /src
WORKDIR /src
COPY tool-status.patch asr-proxy.patch image-shrink.patch tool-feedback.patch keep-logs.patch shared-session.patch pico-audio.patch /tmp/
RUN git apply /tmp/tool-status.patch /tmp/asr-proxy.patch /tmp/image-shrink.patch /tmp/tool-feedback.patch /tmp/keep-logs.patch \
    /tmp/shared-session.patch /tmp/pico-audio.patch
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -tags goolm,stdjson \
    -ldflags "-s -w -X github.com/sipeed/picoclaw/pkg/config.Version=0.3.1-popcornclaw" \
    -o /picoclaw ./cmd/picoclaw

FROM scratch
COPY --from=build /picoclaw /picoclaw
