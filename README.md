# nf-prov-jds

## Summary

Nextflow plugin to render provenance reports for pipeline runs, maintained as a
fork by The Jackson Laboratory. Supports standard formats such as
[BioCompute Object](https://biocomputeobject.org/) and
[Workflow Run RO-Crate](https://www.researchobject.org/workflow-run-crate/).

`nf-prov-jds` is derived from [nf-prov](https://github.com/nextflow-io/nf-prov),
developed by the Nextflow team and upstream contributors. The upstream project
provides the foundation for this plugin's provenance formats and capture
infrastructure. This fork adds per-tool software annotations, an offline metadata
explorer with native resource charts, a process-grouped workflow DAG and output
lineage, expanded task-script and execution evidence, and reference-only input
packaging. It is a separate plugin, not an official release of `nf-prov`.

Source and issues: [TheJacksonLaboratory/nf-prov-jds](https://github.com/TheJacksonLaboratory/nf-prov-jds).

## Requirements

| Version |	Minimum Nextflow version |
| ------- | ------------------------ |
| 0.1.x   | 26.10 |

The inherited release history in the [changelog](CHANGELOG.md) refers to upstream
`nf-prov`, not releases of this fork. `nf-prov-jds 0.1.1` is based on `nf-prov 1.8.0`.

## Get Started

Build and install from this checkout with `make install` (JDK 21 required).
Registry installation requires a published `nf-prov-jds` release.

To enable and configure `nf-prov-jds`, include the following snippet in your Nextflow config and update as needed.

```groovy
plugins {
  id 'nf-prov-jds@0.1.1'
}

prov {
  enabled = true
  formats {
    bco {
      file = 'bco.json'
      overwrite = true
    }
    wrroc {
      file = 'ro-crate-metadata.json'
      overwrite = true
    }
  }
}
```

Any number of formats can be specified. You do not need to modify your pipeline script in order to use the `nf-prov-jds` plugin.

When you run your Nextflow pipeline, the plugin will automatically produce the specified provenance reports at the end of the run.

The configuration scope remains `prov`, and software annotations remain
`ext.prov`. Replace the `nf-prov` plugin declaration rather than loading both
plugins in the same run.

## Examples

After building and installing the plugin, run the bundled example:

```bash
nextflow run nf-prov-jds-test/ -plugins nf-prov-jds@$(cat VERSION)
```

See [WRROC configuration and examples](docs/WRROC.md) for software annotations,
the HTML explorer, execution evidence and reference-only inputs.

Set `prov.formats.wrroc.preview = true` for the offline viewer. Preview or evidence
also requests Nextflow task usage metrics and retains observed failed attempts.
CPU/memory availability depends on the executor/platform; unavailable metrics
remain explicitly unknown, and cached measurements are identified as historical.
Path-containing tuple staging markers (including Nextflow's internal `$` binding)
are not exported as scalar values; their file lineage remains in normal task
input relationships.

### Tool provenance

For Workflow Run RO-Crate output, processes can declare the tools they use with
`ext.prov.tools`. Each tool is a separate object, and can include:

- `name` (required)
- `version`
- `url`
- `author` (a name or list of names)
- `description`
- `format`
- `keywords` (a string or list of strings)

For example:

```groovy
process ALIGN {
  ext prov: [
    tools: [
      [
        name: 'STAR',
        version: '2.7.9a',
        url: 'https://github.com/alexdobin/STAR',
        author: 'Alexander Dobin',
        format: 'application/x-executable',
        keywords: ['alignment', 'rnaseq'],
        description: 'RNA-Seq read alignment.'
      ]
    ]
  ]

  // input, output and script declarations ...
}
```

The same `ext.prov` map can be assigned in a `process.withName` configuration
selector. Version values must be quoted strings, and URLs must be absolute
HTTP(S) URLs without credentials. Unknown fields and malformed annotations
raise an error and prevent the WRROC report from being written.

These are declared tool annotations, not versions measured from executed
binaries. The plugin does not automatically ingest `versions.yml`, trace every
shell command, or assert that every listed tool ran on every conditional path.
See [Per-process software annotations](docs/WRROC.md#per-process-software-annotations)
for the complete behavior and generated relationships.

## Configuration

*The `file`, `format`, and `overwrite` options have been deprecated since version 1.2.0. Use `formats` instead.*

*The `legacy` format was removed in version 1.5.0. Consider using [data lineage](https://nextflow.io/docs/latest/data-lineage.html) instead.*

The following options are available:

`prov.directory`

*Inherited from nf-prov 1.8.0*

The directory where the provenance reports should be saved. The path is resolved against the workflow output directory (`outputDir`). It must be a relative path within the output directory, and it cannot be used when a report file is an absolute path. By default, it is not used and report files are resolved against the launch directory.

`prov.enabled`

Create the provenance report (default: `true` if plugin is loaded).

`prov.formats`

Configuration scope for the desired output formats. The following formats are available:

- `bco`: Render a [BioCompute Object](https://biocomputeobject.org/). Supports the `file` and `overwrite` options. See [BCO.md](docs/BCO.md) for more information about the additional config options for BCO.

- `dag`: Render the task graph as a Mermaid diagram embedded in an HTML document. Supports the `file` and `overwrite` options.

*New in version 1.4.0*

- `wrroc`: Render a [Workflow Run RO-Crate](https://www.researchobject.org/workflow-run-crate/). Includes all three profiles (Process, Workflow, and Provenance). See [WRROC.md](docs/WRROC.md) for more information about the additional config options for WRROC.

  This fork also supports per-process `ext.prov.tools` annotations for individual
  tool versions and an opt-in offline HTML explorer (`preview = true`). The same
  explorer can be regenerated from existing metadata with
  `./gradlew generateExplorer -Pmetadata=/path/to/ro-crate-metadata.json`.
  See [software annotations and explorer usage](docs/WRROC.md#per-process-software-annotations).
  Opt-in [repeat-analysis evidence](docs/WRROC.md#repeat-analysis-evidence)
  (`evidence = true`) embeds named expanded task scripts in the JSON and offline
  explorer, alongside resolved task values, execution
  settings and source identity, with explicit capture gaps. Large external
  datasets are not bundled by this feature; review scripts/values for sensitive
  information before enabling it.
  Execution wrappers are not collected; the explorer's **Expanded commands**
  section shows the script directly from a task's evidence.
  Set `prov.formats.wrroc.copyInputs = false` to retain external input locations
  and file lineage without automatically bundling input contents, including large
  reference files or project-owned configuration inputs. Evidence and explicitly
  selected source snapshots are independent of this option.
  See [reference-only inputs](docs/WRROC.md#reference-only-inputs).
  For local launches, `prov.formats.wrroc.codeRepository` provides a validated
  repository URL fallback without changing source revision or local file handling.
  See [repository URL configuration](docs/WRROC.md#repository-url-for-local-launches).

See the [nf-prov-jds-test](./nf-prov-jds-test) directory for an example pipeline that produces every provenance format.

*New in version 1.7.0*

- `gexf`: Render the workflow run as a [GEXF](https://gexf.net/) document. Supports the `file` and `overwrite` options.

`prov.patterns`

List of file patterns to include in the provenance report, from the set of published files. By default, all published files are included.

## License

This project is licensed under the [Apache License 2.0](./COPYING).
Upstream copyright and author notices are retained. The inherited release history
is preserved in [CHANGELOG.md](./CHANGELOG.md); fork changes are identified
separately.
