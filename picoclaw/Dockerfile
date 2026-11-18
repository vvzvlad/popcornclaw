# PicoClaw upstream main at a pinned commit (linux/armv7 by default; --build-arg GOARCH=arm64 for the TV box's
# 64-bit userspace, GOARCH=amd64 for a server) with our patches applied in order:
# tool-status, asr-proxy, image-shrink, tool-feedback, keep-logs, shared-session, pico-audio, start-to-agent,
# webhook-channel, group-foreign-commands, group-decision, exec-bare-slashes, telegram-answer-as-new-message.
# Main rather than the v0.3.1 release: upstream takes pull requests against main, and our patches are written for it.
# Build flags mirror the "picoclaw" build in upstream's .goreleaser.yaml; its
# `go generate ./...` hook is skipped because this commit has no go:generate directives.
FROM golang:1.25.14-bookworm AS build
ARG PICOCLAW_COMMIT=bbf6893ca7afad27f1d00a0f5a45982a549c6ed6
RUN git init -q /src && cd /src && git fetch -q --depth 1 https://github.com/sipeed/picoclaw.git $PICOCLAW_COMMIT \
    && git checkout -q FETCH_HEAD
WORKDIR /src
COPY tool-status.patch asr-proxy.patch image-shrink.patch tool-feedback.patch keep-logs.patch shared-session.patch pico-audio.patch \
    start-to-agent.patch webhook-channel.patch group-foreign-commands.patch group-decision.patch exec-bare-slashes.patch \
    telegram-answer-as-new-message.patch /tmp/
RUN git apply /tmp/tool-status.patch /tmp/asr-proxy.patch /tmp/image-shrink.patch /tmp/tool-feedback.patch /tmp/keep-logs.patch \
    /tmp/shared-session.patch /tmp/pico-audio.patch /tmp/start-to-agent.patch /tmp/webhook-channel.patch \
    /tmp/group-foreign-commands.patch /tmp/group-decision.patch /tmp/exec-bare-slashes.patch \
    /tmp/telegram-answer-as-new-message.patch
# Declared after the clone and the patches, so all ABIs share those layers; GOARM only applies to GOARCH=arm.
ARG GOARCH=arm
# -p 4: by default go compiles one package per host CPU, and on a many-core shared build host the
# compilers of the heaviest SDK packages together run out of memory and get killed.
RUN CGO_ENABLED=0 GOOS=linux GOARCH=$GOARCH GOARM=7 go build -p 4 -tags goolm,stdjson \
    -ldflags "-s -w -X github.com/sipeed/picoclaw/pkg/config.Version=main-$(echo $PICOCLAW_COMMIT | cut -c1-8)-popcornclaw" \
    -o /picoclaw ./cmd/picoclaw

FROM scratch
COPY --from=build /picoclaw /picoclaw
