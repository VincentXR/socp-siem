# SOCP Vector runtime

SOCP builds a private `socp/vector:0.57.0-socp1-alpine` image from the exact Vector
0.57.0 source in [runtime.lock.json](runtime.lock.json), with two narrowly scoped
upstream disk-v2 backports:

1. [Vector #25872](https://github.com/vectordotdev/vector/pull/25872), commit
   `759cb947eb6ab9989df096c732aafe3529d53c48`: publish ledger progress before waking readers.
2. [Vector #26410](https://github.com/vectordotdev/vector/pull/26410), fixed PR head
   `41e9abc6e1c3862b27065cfc35aa892eab4295fa`: finish pending file writes before reporting
   records as flushed. This is a pinned backport, not a claim that the PR is merged.

The upstream source commit is `8832452f57afb536ea0de53a093f9fd1b669ccec`.
The original `Cargo.lock`, disk-buffer format, acknowledgment behavior, fsync
policy and package version remain unchanged. `VECTOR_BUILD_DESC` distinguishes
the patched binary. These fixes preserve disk buffering and at-least-once
semantics; they do not establish exactly-once delivery or production capacity.

## Build and verify

From the repository root:

```bash
bash build/vector-runtime.sh verify
bash build/vector-runtime.sh image
bash build/vector-runtime.sh verify-image
```

`image` builds and loads only into the local Docker daemon. It never pushes,
logs into a registry, installs a service or changes credentials. Docker BuildKit
is required. The official Rust 1.95.0 Alpine builder and official Vector 0.57.0
Alpine runtime are digest-pinned. Compilation occurs natively against musl;
the GNU proof binary is never copied into Alpine. The official runtime's
entrypoint, shell, wget, CA certificates, timezone files and paths are retained.
An image-specific `.dockerignore` sends only this kit and the helper, never
credentials, the repository checkout, compiler caches or generated binaries.

The Docker build first runs every enabled upstream `vector-buffers` disk-v2 unit
test and verifies that all five new regression tests exist. The Rust test
harness uses `--test-threads=1`: upstream 0.57.0's `known_errors.rs` reuses the
same parent tracing span in two different corruption tests, and its assertion
registry is global. Running those fixtures concurrently can falsely attribute
one test's corruption to the other. This isolates test fixtures while retaining
every test and assertion and each test's internal Tokio concurrency. No third
production-source patch or test exclusion is introduced. Two upstream tests
already marked ignored remain ignored: `reader_exits_cleanly_when_writer_done_and_in_flight_acks`
and `writer_waits_when_buffer_is_full`; the result must report them separately
rather than count them as passed. It then builds
the binary with `--locked --no-default-features` and the manifest feature list.
A missing image, incorrect provenance label or missing binary marker fails
`verify-image` closed. Expected labels are `io.socp.vector.build`,
`io.socp.vector.source`, `io.socp.vector.patches` and
`io.socp.vector.lock-sha256`. Verification also checks the packaged provenance
against the current lock, its musl target and the actual binary SHA-256.

Builds use at most two compiler jobs, no swap, release optimization level 2,
16 codegen units, no LTO, no debug info, and no incremental compilation. The
fixed source timestamp is exported as `SOURCE_DATE_EPOCH`. These settings avoid
full-release fat-LTO memory pressure. Caches reside under `.cache/vector-runtime`
and Docker BuildKit cache mounts. A clean source/patch change requires a fresh
`SOCP_VECTOR_CACHE`; reused source trees are content-verified and never silently
patched twice. Allow a cold build up to an hour on a standard hosted runner.
CI additionally caches only the verified runtime image (not the compiler cache
or credentials), keyed by OS/architecture and the complete runtime kit, helper
and workflow contents with no prefix fallback. Cache hits still run provenance,
binary-hash and all collector/full-stack tests. Image archives are excluded from
general evidence uploads. A verified build can be reused while later service
tests are being repaired; it is not a cached claim that those tests passed.

### Native GNU proof build

Linux GNU builds need Python 3.11+, curl, git, C/C++ build tools, make, perl,
pkg-config, CMake, protoc and libclang (for zstd-sys/bindgen), plus Rust 1.95.0.
Debian/Ubuntu package names are
`build-essential cmake protobuf-compiler pkg-config perl curl git python3 libclang-dev`.
The Alpine builder installs `clang20-libclang`, sets `LIBCLANG_PATH=/usr/lib`,
and loads its shared library before compilation. libclang is not added to the
final runtime image. See the [bindgen requirements](https://rust-lang.github.io/rust-bindgen/requirements.html)
for other native environments.
The Alpine build uses a hash-pinned `rustc-host-wrapper.py` to dynamically link
host-only build scripts/proc macros so bindgen can load libclang. Explicit
`--target` compilations keep their upstream flags and static musl linkage;
no global `RUSTFLAGS` override drops upstream warning checks. A tiny Rust
`dlopen` probe runs before Cargo to catch host linkage failures early. The
wrapper hash is recorded in binary provenance and checked on image startup.
OpenSSL is built from the locked vendored crate. The local GNU proof recipe uses
workspace-local Rust 1.95.0, CMake 3.31.6 and upstream protoc 21.12; it does not
change host settings or install services.

```bash
bash build/vector-runtime.sh prepare
bash build/vector-runtime.sh test
bash build/vector-runtime.sh native
.cache/vector-runtime/artifacts/vector --version
```

Use `SOCP_VECTOR_CACHE=/absolute/ignored/cache` to relocate all source, outputs
and temp files. Optional workspace-local tools are found in
`$SOCP_VECTOR_CACHE/toolchain/{cargo/bin,protoc/bin,python/bin}`. A toolchain
installed elsewhere can be supplied through `PATH`; Rust must still match
1.95.0. Native GNU success is proof for that target only. The Alpine image must
build and pass the container delivery contracts separately.

## Supported SOCP components

This is a deliberately feature-restricted SOCP runtime, not the full upstream
all-integrations distribution. It supports every current SOCP-rendered source:
file, socket (TCP/UDP), syslog and Kafka; the remap transform and VRL standard
library; HTTP delivery with disk buffers; internal_metrics and
prometheus_exporter for the reference pipeline; console for tests; and the
Vector API. Kafka includes vendored librdkafka, TLS, compression and its built-in
SASL PLAIN/SCRAM/OAUTH mechanisms. The current renderer does not configure
Kerberos/GSSAPI; this build does not enable that optional upstream integration.
Full AWS/GCP sinks, unrelated source/sink families, enrichment providers, API
client/top and jemalloc are not selected. A new renderer component or Kafka
GSSAPI contract requires an explicit feature/pin change and corresponding tests.
Do not point an arbitrary upstream Vector configuration at this image and
assume all optional integrations are available.

## Provenance, source and licenses

`runtime.lock.json` pins the source archive SHA-256, original Cargo.lock SHA-256,
each complete upstream patch SHA-256, commits, toolchain and base image digests.
The build produces:

- `provenance.json`: actual target/toolchain, source/patch pins, feature/profile
  choices, fixed timestamp, binary version and SHA-256, and SBOM SHA-256
- `sbom.cdx.json`: CycloneDX 1.5 resolved target Rust dependency graph, excluding
  dev-only edges; image builds also include inherited Alpine runtime packages
- `buffer-tests.list` and `buffer-tests.passed.json`: required test presence and
  successful disk-v2 command evidence
- `Cargo.lock`, upstream `LICENSE`, `NOTICE`, `LICENSE-3rdparty.csv` and `licenses/`
- The exact source archive plus this build kit and both patch files
- For images, `build-apk-packages.txt`, `runtime-apk-db` and binary `ldd.txt`

Native output is `.cache/vector-runtime/artifacts/`; the image includes the
same package at `/usr/share/vector/socp/`. The GNU SBOM excludes the host OS;
the image SBOM includes runtime Alpine packages. Build tooling packages are
recorded separately and are not claimed as runtime libraries. Registry crate
checksums and locked git revisions are retained. OS package updates and native
host tool differences mean this is a reproducible pinned-source recipe, not a
claim of byte-identical builds across machines. Record the actual binary and
image digest for each build.

Vector is [MPL-2.0](LICENSE-MPL-2.0). Its modified source, exact backports and
upstream notices accompany every packaged binary, including inside the image.
Preserve these when redistributing privately; obey each third-party license.
No publication rights or external publication are implied by this local kit.

To extract small CI evidence after a local image build, create a stopped
container, copy `/usr/share/vector/socp/{provenance.json,sbom.cdx.json,buffer-tests.passed.json}`
with separate `docker cp` calls, and remove only that disposable container.
Do not upload the entire compiler cache or source bundle as a general CI log.
