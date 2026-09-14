# SOCP application images

`Dockerfile.jvm` packages a previously built Spring Boot JAR. It does not
compile source code inside the image, which keeps the runtime image small and
makes the artifact used in a release explicit.

The runtime image argument is required and must be immutable. A release build
therefore looks like:

```bash
image_tag="123456789012.dkr.ecr.ap-southeast-1.amazonaws.com/socp-dev-detect-web:sha-$GITHUB_SHA"
docker build \
  --build-arg RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy@sha256:<verified-digest> \
  --build-arg APP_JAR=services/detect-web/target/detect-web-1.0.0-SNAPSHOT.jar \
  --build-arg BUILD_VERSION="$GITHUB_SHA" \
  --build-arg VCS_REF="$GITHUB_SHA" \
  -f deploy/docker/Dockerfile.jvm \
  -t "$image_tag" .
docker push "$image_tag"
aws ecr describe-images \
  --repository-name socp-dev-detect-web \
  --image-ids "imageTag=sha-$GITHUB_SHA" \
  --query 'imageDetails[0].imageDigest' \
  --output text
```

The registry returns the immutable digest only after push. Helm consumes the
repository and returned digest separately and renders
`repository@sha256:...`; `docker build -t repository@sha256:...` is invalid.

The AWS release pipeline builds the JAR, creates the image, produces a
CycloneDX SBOM, rejects critical vulnerabilities, pushes the image, and deploys
the returned ECR digest. Image signing and provenance attestation remain a
separate policy gate; the repository does not claim they ran until the release
workflow records that evidence.

Images run as UID/GID `10001`, have no shell entrypoint, and do not write to a
host-mounted application directory. Runtime secrets are injected by the
deployment platform rather than copied into an image layer.
