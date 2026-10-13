# syntax=docker/dockerfile:1.7
ARG BUILDER_IMAGE=docker.io/rust:1.95.0-alpine3.23@sha256:606fd313a0f49743ee2a7bd49a0914bab7deedb12791f3a846a34a4711db7ed2
ARG RUNTIME_IMAGE=docker.io/timberio/vector:0.57.0-alpine@sha256:19e3526faf4d4b1ed0c28a0d68d4cc3a1e13e437099986a5b7a768707907497c
FROM ${RUNTIME_IMAGE} AS upstream
FROM ${BUILDER_IMAGE} AS builder
# Official Alpine packages only. Their exact installed versions are captured as
# build evidence; the base image, Rust version and Cargo dependency graph are pinned.
RUN apk add --no-cache bash build-base cmake curl git linux-headers perl pkgconf \
    protobuf-dev protobuf python3 zlib-dev zlib-static openssl-dev openssl-libs-static \
    clang20-libclang
# zstd-sys uses bindgen during the native build. Load the shared C API now so
# missing libclang/transitive libraries fail before the expensive Rust compile.
# This is a builder-only dependency; the final image keeps the upstream runtime.
ENV LIBCLANG_PATH=/usr/lib
RUN python3 -c "import ctypes; ctypes.CDLL('/usr/lib/libclang.so.20.1')"
WORKDIR /socp
COPY agents/vector-runtime /socp/agents/vector-runtime
COPY build/vector-runtime.sh /socp/build/vector-runtime.sh
COPY --from=upstream /lib/apk/db/installed /socp/runtime-apk-db
ENV SOCP_VECTOR_APK_DB=/socp/runtime-apk-db
ENV RUSTC_WRAPPER=/socp/agents/vector-runtime/rustc-host-wrapper.py
# Cargo's explicit target flags do not reach host build scripts. Exercise the
# same wrapper/loader as bindgen before compiling; target Vector stays static.
RUN chmod +x "$RUSTC_WRAPPER" && \
    "$RUSTC_WRAPPER" rustc --edition=2021 agents/vector-runtime/check-libclang.rs -o /tmp/check-libclang && \
    /tmp/check-libclang /usr/lib/libclang.so.20.1
# Build against musl inside Alpine. Never copy the GNU proof binary into this image.
# The full disk-v2 suite (including both upstream patches) must pass before packaging.
RUN --mount=type=cache,id=socp-vector-cargo-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=socp-vector-cargo-git,target=/usr/local/cargo/git \
    --mount=type=cache,id=socp-vector-target,target=/socp/.cache/vector-runtime/target \
    export SOCP_VECTOR_TARGET="$(rustc -vV | sed -n 's/^host: //p')" && \
    case "$SOCP_VECTOR_TARGET" in *-unknown-linux-musl) ;; *) echo 'Expected musl toolchain' >&2; exit 1;; esac && \
    bash build/vector-runtime.sh test && \
    bash build/vector-runtime.sh native && \
    apk info -v > .cache/vector-runtime/artifacts/build-apk-packages.txt && \
    cp runtime-apk-db .cache/vector-runtime/artifacts/runtime-apk-db && \
    (ldd .cache/vector-runtime/artifacts/vector > .cache/vector-runtime/artifacts/ldd.txt 2>&1 || \
      grep -qE 'Not a valid dynamic program|not a dynamic executable|statically linked' .cache/vector-runtime/artifacts/ldd.txt)

FROM ${RUNTIME_IMAGE}
ARG SOCP_LOCK_SHA256
LABEL io.socp.vector.lock-sha256="${SOCP_LOCK_SHA256}" \
      io.socp.vector.build="0.57.0-socp1" \
      io.socp.vector.source="8832452f57afb536ea0de53a093f9fd1b669ccec" \
      io.socp.vector.patches="759cb947eb6ab9989df096c732aafe3529d53c48,41e9abc6e1c3862b27065cfc35aa892eab4295fa" \
      org.opencontainers.image.title="SOCP patched Vector" \
      org.opencontainers.image.version="0.57.0-socp1" \
      org.opencontainers.image.licenses="MPL-2.0"
COPY --from=builder /socp/.cache/vector-runtime/artifacts/vector /usr/local/bin/vector
COPY --from=builder /socp/.cache/vector-runtime/artifacts /usr/share/vector/socp
# Keep the upstream entrypoint, shell, wget, certificates, tzdata and default paths.
RUN test -n "$SOCP_LOCK_SHA256" && /usr/local/bin/vector --version | grep -F '0.57.0-socp1' && \
    command -v wget && test -s /etc/ssl/certs/ca-certificates.crt
