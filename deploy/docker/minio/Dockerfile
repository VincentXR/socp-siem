# syntax=docker/dockerfile:1.7
# The community distribution is source-only. Build the security-fixed commit
# and package the static server in a minimal runtime without depending on an
# archived upstream image registry.
ARG BUILD_IMAGE
FROM ${BUILD_IMAGE} AS builder
WORKDIR /src
RUN git init . \
    && git remote add origin https://github.com/minio/minio.git \
    && git fetch --depth=1 origin 9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a \
    && git checkout --detach FETCH_HEAD \
    && test "$(git rev-parse HEAD)" = 9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    mkdir -p /out/data /out/root/.minio /out/tmp \
    && CGO_ENABLED=0 go build -trimpath \
    -ldflags='-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17:29:55Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-10-15T17-29-55Z -X github.com/minio/minio/cmd.CommitID=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a' \
    -o /out/minio .

FROM scratch
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=builder /out/minio /usr/bin/minio
COPY --from=builder /src/LICENSE /licenses/minio/LICENSE
COPY --from=builder /src/NOTICE /licenses/minio/NOTICE
COPY --from=builder /out/data /data
COPY --from=builder /out/root /root
COPY --from=builder /out/tmp /tmp
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a" \
      org.opencontainers.image.version="RELEASE.2025-10-15T17-29-55Z" \
      version="RELEASE.2025-10-15T17-29-55Z" \
      release="RELEASE.2025-10-15T17-29-55Z"
VOLUME ["/data"]
EXPOSE 9000 9001
ENTRYPOINT ["/usr/bin/minio"]
