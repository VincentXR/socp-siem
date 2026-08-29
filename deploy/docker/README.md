# SOCP application images

`Dockerfile.jvm` packages a previously built Spring Boot JAR. It does not
compile source code inside the image, which keeps the runtime image small and
makes the artifact used in a release explicit.

The runtime image argument is required and must be immutable. A release build
therefore looks like:

```bash
docker build \
  --build-arg RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy@sha256:<verified-digest> \
  --build-arg APP_JAR=services/detect-web/target/detect-web-1.0.0-SNAPSHOT.jar \
  --build-arg BUILD_VERSION="$GITHUB_SHA" \
  --build-arg VCS_REF="$GITHUB_SHA" \
  -f deploy/docker/Dockerfile.jvm \
  -t ghcr.io/vincentxr/socp-detect-web@sha256:<release-digest> .
```

The release pipeline must build the JAR, create the image, produce an
SPDX/CycloneDX SBOM (for example with `syft`), scan the image and dependencies
(`grype` or the registry scanner), sign the image (`cosign sign`), and verify
the signature before a Kubernetes rollout. The repository does not check in
credentials or pretend that an image has been signed before those deployment-
owned steps run.

Images run as UID/GID `10001`, have no shell entrypoint, and do not write to a
host-mounted application directory. Runtime secrets are injected by the
deployment platform rather than copied into an image layer.
