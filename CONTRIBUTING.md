# nf-prov-jds

Contributions are welcome. Fork [this repository](https://github.com/TheJacksonLaboratory/nf-prov-jds) and open a pull request to propose changes. Consider submitting an [issue](https://github.com/TheJacksonLaboratory/nf-prov-jds/issues/new) to discuss any proposed changes with the maintainers before submitting a pull request.

This project is derived from [nf-prov](https://github.com/nextflow-io/nf-prov).
Retain upstream license, copyright and author notices when modifying inherited
code, and identify changes in the changelog.

## Development

Build and install the plugin to your local Nextflow installation:

```bash
make install
```

Run with Nextflow as usual:

```bash
nextflow run nf-prov-jds-test -plugins nf-prov-jds@<version>
```

Run plugin tests with `./gradlew test`. The explorer's graph/metric logic has
dependency-free JavaScript tests (Node.js 18+):

```bash
node --test src/test/javascript/metadata-explorer.test.cjs
```

For viewer changes, also open a generated preview in a browser and check output
selection, process/task drill-down, resource filters, local import, portable
export, and missing-metadata states.

## Publishing

Follow these steps to package, upload, and publish the plugin:

1. Update the [version file](./VERSION).

2. Update the [changelog](./CHANGELOG.md).

3. Run `make release` to build and publish the plugin.

4. Make a [GitHub release](https://github.com/TheJacksonLaboratory/nf-prov-jds/releases).
