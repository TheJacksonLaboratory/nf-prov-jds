# Deployment notes

These notes describe the deployment methods used for `nf-prov-jds`. This file
is intentionally local-only and is excluded through `.git/info/exclude`.

## Prerequisites

- A clean or understood working tree.
- JDK 21. The Gradle build uses a Java 21 toolchain.
- An approved Seqera ownership claim for `nf-prov-jds`.
- The provider value in `build.gradle` must exactly match the claim. The
  current provider is `The Jackson Laboratory`.
- A Nextflow Registry access token.

## Local release

1. Make and push the source changes first so GitHub reflects the code that will
   be published.
2. Run the checks:

   ```bash
   make test
   make assemble
   ```

3. Store the registry token locally in
   `$HOME/.gradle/gradle.properties`:

   ```text
   npr.apiKey=<token>
   ```

   Keep this file out of Git and use restrictive permissions:

   ```bash
   chmod 600 "$HOME/.gradle/gradle.properties"
   ```

4. Publish from the repository root:

   ```bash
   make release
   ```

   This invokes `releasePluginIfNotExists`, which publishes the assembled
   artifact and skips an already-published version.

5. Test installation from a clean pipeline:

   ```bash
   nextflow run <pipeline> -plugins nf-prov-jds@$(cat VERSION)
   ```

After publication, confirm that the GitHub repository contains the same source
used to build the artifact and that CI passes.

## Local development installation

To install a build without publishing it:

```bash
make install
nextflow run <pipeline> -plugins nf-prov-jds@$(cat VERSION)
```

This installs the plugin in the local Nextflow plugins directory.

## CI validation

GitHub Actions runs the unit tests, installs the plugin locally, runs the
bundled example pipeline, and validates the generated RO-Crate and BCO
outputs. CI is validation only; publication is currently performed manually
from a local checkout.

## Credential safety

Never commit the registry token, place it in `build.gradle`, or pass it on a
command line. Revoke and replace a token if it is exposed.
