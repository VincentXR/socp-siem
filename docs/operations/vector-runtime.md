# Patched Vector runtime

SOCP pins the local image `socp/vector:0.57.0-socp1-alpine` in
[`infra/middleware-images.env`](../../infra/middleware-images.env). It is built
from Vector 0.57.0 with two disk-buffer fixes. The ordinary upstream 0.57.0
binary is not an equivalent replacement: quiet input can leave a record on
disk without an HTTP attempt. Disk buffering, event identity, retry, and the
existing delivery deadlines remain enabled.

## Source, patches, and maintenance

The authoritative input is
[`agents/vector-runtime/runtime.lock.json`](../../agents/vector-runtime/runtime.lock.json):

- Vector source commit `8832452f57afb536ea0de53a093f9fd1b669ccec`, with source
  archive and unchanged Cargo.lock SHA-256 hashes.
- [Upstream #25872](https://github.com/vectordotdev/vector/pull/25872), commit
  `759cb947eb6ab9989df096c732aafe3529d53c48`: publish disk-ledger progress before
  notifying readers.
- [Upstream #26410](https://github.com/vectordotdev/vector/pull/26410), reviewed
  snapshot `41e9abc6e1c3862b27065cfc35aa892eab4295fa`: complete pending writes
  before reporting records flushed and waking readers. This is a pinned
  backport of an upstream proposal, not a claim that a released Vector includes
  it. The patch also tests deferred write errors.

Patch files and hashes are versioned in `agents/vector-runtime/patches/`.
Neither backport changes the disk record/ledger layout, buffer directory names,
or fsync policy. That narrow source comparison does not certify power-loss
safety or arbitrary version downgrades.

The selected features cover SOCP's file, socket, syslog, Kafka and internal
metrics sources, remap/VRL transforms, and HTTP, console and Prometheus exporter
sinks. This is a SOCP-specific build, not every component from Vector's full
feature distribution. New renderer components require a manifest update and
runtime validation.

## Build and inspect

Run from the repository root with Docker and the prerequisites documented in
[`agents/vector-runtime/README.md`](../../agents/vector-runtime/README.md):

```bash
bash build/vector-runtime.sh verify
bash build/vector-runtime.sh image
bash build/vector-runtime.sh verify-image
```

`image` builds and loads the Linux musl binary into the pinned Alpine runtime
locally; it does not push an image. The image retains the upstream entrypoint,
certificates and diagnostic shell. Verification checks exact source/patch/build
labels and the executable's SOCP build marker, rather than trusting the tag.
The builder and runtime base-image digests, Rust toolchain, release profile,
feature list and build concurrency are pinned in the manifest. Cache state
under `.cache/vector-runtime/` is rebuildable and must not be committed.

For GNU/Linux source-level regression and native proof:

```bash
bash build/vector-runtime.sh test
bash build/vector-runtime.sh native
```

The runtime build retains MPL-2.0 attribution/license material, the exact patch
inputs, provenance, and a CycloneDX 1.5 SBOM of the resolved target Rust dependency graph; the
image SBOM also includes inherited Alpine runtime packages. Follow the runtime
README's artifact recipe to retain source/patch hashes, compiler and binary
identity, and the generated SBOM with the image. If distributing a
build beyond this repository, also make the matching covered source and
modifications available and retain dependency notices. The native GNU SBOM excludes host OS packages. Neither SBOM is a
vulnerability clearance; retain a vulnerability scan as separate evidence when
required by deployment policy. No public registry publication is part of this workflow.

On Windows, use Docker Desktop in Linux-container mode through Git Bash or
WSL. This uses the same Alpine runtime as CI. A patched native `vector.exe`
build has not been validated. Do not automatically select an older binary in
`tooling/vector/bin/` when Docker or source compilation is unavailable.

## Local startup and state preservation

`bash build/run-vector.sh start` verifies the locally built image before
creating a collector. If the image is missing or has wrong provenance, it
fails with the build instruction. It refuses to replace or restart any existing
`socp-vector` container, whether running or stopped.

`bash build/run-vector.sh stop` now performs a graceful stop and **keeps the
container and its state**. It no longer removes the container. This behavior
change is intentional: the reference helper does not mount a persistent Vector
state volume, so removing its container discards buffered events and file
checkpoints. Stopping does not itself prove the remote destination accepted
all pending events.

Before an upgrade, record the actual running image ID, configuration, sink IDs,
input paths, and `data_dir`. Stop collection in a controlled window and retain
a complete, consistent copy of that directory, the input files/checkpoints,
the old image and configuration. The reference config uses `/var/lib/vector`;
custom/generated configurations can use a different path. Preserve the
rendered configuration before another startup overwrites
`.cache/vector.generated.toml`. Configuration copies may contain credentials;
store them securely outside source control.

Recreation, state export/import, and adding a durable volume are explicit
operator actions. This helper does not migrate existing container state, delete
old containers, or attach a new empty volume over old state. For long-lived
collectors, arrange durable storage before first use. Never run two Vector
processes against the same state directory. Use the existing sink IDs and
complete preserved state when testing a replacement, and verify replay and
event identity before resuming ordinary collection.

To resume an unchanged, previously verified patched container, first compare
its actual image ID with the verified image, and confirm its configuration and
state have not changed; an explicit `docker start socp-vector` then retains
that container's state. A current tag pointing at a new image does not change
the image used by an old container.

For rollback, stop the replacement and retain its current state and any
post-upgrade input before selecting the preserved prior image/configuration
and compatible state. Do not blindly replace current state with a pre-upgrade
snapshot, which can omit newer events or replay already acknowledged ones.
Reverting to unpatched upstream 0.57.0 reintroduces the delivery stall; keep
that risk explicit and verify replay rather than treating rollback as a fix.
This document does not authorize deleting a collector or its data.

## Acceptance and removal of backports

Both Change CI and Full Stack E2E build and verify the image before
`VectorConfigRendererContainerTest`. Source compilation has a separate
60-minute limit; the original 60-second delivery assertion is unchanged. Job
budgets include that compilation allowance (120 and 130 minutes). No skipped
Vector test, memory-buffer substitution, synthetic tail traffic or added
observer sink is an acceptable workaround.

The Java fixture proves three independent cold one-event deliveries, sequential
quiet-tail delivery, and process-crash replay from retained private state with
the source file removed. It retains the original eight HTTP 503 responses plus
one 200 response and identical-body assertion. The process-crash test does not
claim power-loss/fsync durability. See [testing](../testing.md#vector-disk-buffer-delivery)
for commands and evidence locations. GNU/Linux delayed-write injection is
separate evidence; it does not substitute for actual Alpine Testcontainers.
Never preload the test fault-injection library into an ordinary collector.

Retire the backports only when a pinned official release contains **both**
fixes (including any final upstream revisions), passes the upstream buffer
regressions, repeated cold/quiet-tail stress and crash/replay checks on the
actual deployment platform, and successfully replays a preserved 0.57 backlog.
Then update the catalog, provenance contract, build path and documentation
together. A newer version number alone is insufficient. Check for upstream
changes deliberately; the build never downloads an unreviewed moving patch.
