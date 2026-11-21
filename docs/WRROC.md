# Additional WRROC configuration

*New in version 1.4.0*

The `wrroc` format supports additional options to configure certain aspects of the Workflow Run RO-Crate. These fields cannot be inferred automatically from the pipeline or the run, and so must be entered through the config.

The following config options are supported:

- `prov.formats.wrroc.agent.contactType`
- `prov.formats.wrroc.agent.email`
- `prov.formats.wrroc.agent.name`
- `prov.formats.wrroc.agent.orcid`
- `prov.formats.wrroc.agent.phone`
- `prov.formats.wrroc.agent.ror`
- `prov.formats.wrroc.organization.contactType`
- `prov.formats.wrroc.organization.email`
- `prov.formats.wrroc.organization.name`
- `prov.formats.wrroc.organization.phone`
- `prov.formats.wrroc.organization.ror`
- `prov.formats.wrroc.license`
- `prov.formats.wrroc.codeRepository`
- `prov.formats.wrroc.publisher`
- `prov.formats.wrroc.preview`
- `prov.formats.wrroc.evidence`
- `prov.formats.wrroc.sourceFiles`

Refer to the [WRROC User Guide](https://www.researchobject.org/workflow-run-crate/) for more information about the associated RO-Crate entities.

Here is an example config:

```groovy
prov {
    formats {
        wrroc {
            file = "ro-crate-metadata.json"
            overwrite = true
            agent {
                name = "John Doe"
                orcid = "https://orcid.org/0000-0000-0000-0000"
                email = "john.doe@example.org"
                phone = "(0)89-99998 000"
                contactType = "Researcher"
            }
            organization {
                name = "University of XYZ"
                ror = "https://ror.org/000000000"
            }
            license = "https://spdx.org/licenses/MIT"
            publisher = "https://ror.org/000000000"
        }
    }
}
```

## Repository URL for local launches

Use `prov.formats.wrroc.codeRepository` to declare a repository URL when launching
from a local checkout:

```groovy
prov {
    directory = '.'
    formats {
        wrroc {
            file = 'ro-crate-metadata.json'
            overwrite = true
            evidence = true
            preview = true
            license = 'https://spdx.org/licenses/MIT'
            codeRepository = 'https://github.com/OWNER/PIPELINE'
        }
    }
}
```

Use the license that actually applies to your workflow. Both settings are quoted
strings. `codeRepository` must be an absolute HTTP(S) URL without credentials.
Nextflow's detected repository takes precedence; the configured URL is a fallback.
It is written to the workflow entity's `codeRepository` field and resolves the
missing-repository warning without requiring a remote launch.

The fallback does not invent a commit, map local files to unverified remote
revision URLs, or change local file packaging. `manifest.homePage` is not
automatically treated as a code repository. With evidence enabled, the separately
captured Git commit/status still describes the actual checkout.

Valid license/repository settings resolve their missing-metadata warnings, but
genuine capture gaps and dirty working trees continue to warn. A release branch
or tag does not by itself imply a clean working tree. Rebuild/reinstall this fork
after adding the option; older plugin binaries do not consume it.

## Per-process software annotations

Use `ext.prov.tools` to describe the tools a process runs. Each tool is a separate
object, so names, versions, URLs and authors cannot become misaligned:

```groovy
process RSEM_ALIGNMENT_EXPRESSION {
    tag "$sampleID"

    ext prov: [
        tools: [
            [
                name: 'RSEM',
                version: '1.3.1',
                url: 'https://github.com/deweylab/RSEM',
                author: ['Bo Li', 'Colin Dewey'],
                format: 'application/x-executable',
                keywords: ['alignment', 'expression', 'rnaseq'],
                description: 'RNA-Seq expression quantification.'
            ],
            [
                name: 'STAR',
                version: '2.7.9a',
                url: 'https://github.com/alexdobin/STAR'
            ],
            [
                name: 'Bowtie2',
                version: '2.4.5',
                url: 'https://github.com/BenLangmead/bowtie2'
            ]
        ]
    ]

    // input, output and script declarations ...
}
```

The same map can be assigned with `ext.prov = [...]` in a `process.withName`
configuration selector. `name` is required. Optional fields are `version`, `url`,
`author`, `description`, `format`, and `keywords`:

- Quote version strings; numeric values are rejected rather than losing precision.
- `url` must be a single absolute HTTP(S) URL without credentials.
- `author` accepts a person's name or a list of people's names. These are exported
  as inline `Person` objects. `keywords` accepts a string or list of strings.
- Unknown keys and malformed annotations raise an explicit error. The existing
  observer logs report-generation failures in the Nextflow log; an invalid
  annotation prevents that WRROC report from being written.

Annotated processes get their own module software entity, linked to the source
module by `isBasedOn` and to individual tools by `hasPart`. Task `instrument` and
process-step `workExample` point to that annotated entity. Two aliases of a module
can therefore declare different tool versions without mixing them. Unannotated
processes retain their existing `meta.yml`-based behavior.

These are **declared tool versions**, not versions measured from the running
executables. The plugin does not automatically ingest `versions.yml`, trace every
shell command, or assert that every listed tool was invoked on every conditional
execution path. Run parameters, declared file inputs/outputs and their lineage
continue to use the native WRROC representation.

## Repeat-analysis evidence

This fork can preserve a documented basis for repeating an analysis **given access
to the identified inputs, references, software and source revision**. It is not a
self-contained execution archive or a guarantee of identical results.

Evidence capture is **opt-in**. Build/install this fork (Java 21,
`./gradlew install`) and use Nextflow 26.10.0 or later; these fork options are not
provided by the upstream registry version with the same plugin ID/version.

```groovy
prov {
    // Keep metadata beside published outputs, not in a separate subdirectory.
    directory = '.'
    formats {
        wrroc {
            file = 'ro-crate-metadata.json'
            overwrite = true
            preview = true
            evidence = true
            sourceFiles = [
                'workflows/rnaseq.nf',
                'modules/rsem/rsem_alignment_expression.nf',
                'config/rnaseq.config'
            ]
            license = 'https://spdx.org/licenses/MIT'
        }
    }
}
```

Set `outputDir` to the publication root (for example `params.pubdir`) so published
outputs are inside the crate. Outputs outside the crate are excluded with a
warning by the existing renderer.

Files matched by overlapping task output declarations are represented once per
task. For example, `path '*stats'` and a named tuple output matching the same stats
file share a single data entity; distinct outputs and their lineage are retained.

### What is preserved

- **Task scripts:** the available `.command.sh` contents embedded as UTF-8 text in
  a named `SoftwareSourceCode` entity at completion/cache notification, before
  subsequent work-directory cleanup. `.command.run` execution wrappers are not
  collected. Native `exec` tasks do not have shell scripts. Missing
  cached/stored-task scripts and invalid UTF-8 are reported as gaps, not
  reconstructed from a newly evaluated script or silently replaced/truncated.
- **Task values:** resolved legacy `val`/`each`/explicit `env` inputs and
  `val`/`env` outputs, including flattened tuple components. Scalars retain their
  types; lists, maps and null use JSON text with `encodingFormat:
  application/json`. Zero, false and null are retained. Typed task values are also
  recorded, with paths represented as location text. Unsupported objects, nesting
  deeper than 16 levels and values over 65,536 serialized characters produce
  explicit gaps rather than silent truncation. Uncollected outputs of failed
  tasks are not reported as resolved results.
- **Execution settings:** available CPU allocation, memory bytes, time-limit
  milliseconds, executor name, attempt, cache flag, exit status, output-collection
  flag, script type and container identifier/enablement/available engine. A
  configured image is not represented as a container actually used when container
  execution is disabled. These are resolved settings, not measured usage.
  A digest-qualified container identifier is distinguished from a tag/path;
  the plugin does not resolve or invent an immutable image digest.
- **Source identity:** a launch-time Git commit, origin URL (URL credentials/query
  removed), project subdirectory and working-tree status, plus Nextflow's source
  identity when available. Git inspection failures are recorded as gaps. Status
  is scoped to the project and includes untracked files/directories but excludes
  ignored files.
- **Selected source snapshots:** only the explicitly listed `sourceFiles`,
  including modified or untracked files, copied at workflow start. Paths must be
  individual project-relative files; directories and links escaping the project
  are not allowed, and globs are not expanded. The list is not expanded into a full dependency
  bundle. For a dirty checkout, select every relevant changed/new source file;
  also retain the original commit to account for deleted files. Dirty state
  remains flagged because the plugin cannot establish that the selection covers
  all relevant changes. Changes made after launch are outside this snapshot.
- **Integrity of preserved evidence:** original byte sizes and SHA-256 hashes of embedded scripts
  and selected source files. External reads and reference datasets are not
  additionally copied, enumerated or hashed by evidence capture. Existing native
  input-file packaging behavior is unchanged.

Task actions link to an evidence record with `subjectOf`, and that record links
with `hasPart` to the expanded script entity (`#task-script/<task-hash>`). Its
human-readable `name` includes the task/process/sample label; its `text` contains
the expanded shell script, and `about` links back to the task action. Internal
hashes identify executions, not the names shown to readers. Scripts are embedded
in the metadata and portable HTML; no task-script files/directories are written.
The explorer provides an **Expanded commands** section directly on task/evidence
details and a **Task script** type filter.

Only explicitly selected source snapshots are stored under `prov-evidence/source/`.
With no `sourceFiles`, this feature does not create a `prov-evidence` directory.
Scalar input/output
entities are linked through the task action's `object`/`result`. The workflow run
links to `#source-evidence`. Settings and capture gaps use named
`additionalProperty` values. Compound values and gap/status lists use JSON text.
Scalar values do **not** create guessed dependency edges between tasks that happen
to produce/consume equal values.

With evidence enabled, WRROC is also generated for failed workflows and includes
observed failed attempts with failed action status. Other report formats and
the default, evidence-disabled lifecycle are unchanged. Only observed tasks are
represented: a failure before a task is created cannot supply task evidence.

### Internal reference selection

No reference-input refactor or module manifest is required. For example, an RSEM
task receiving read length `101` and strand setting `reverse_stranded` can retain
both values and its generated command containing
`.../STAR/primary_100/...`. The reference base/prefix parameters remain in the
native run configuration. Someone with access to that reference cache can inspect
the resolved selection without the reference files being packaged.

Scripts are **generated command evidence**, not a trace proving every shell
branch ran or identifying every file a tool opened internally. Paths selected
inside an executable or by shell-time substitution may remain opaque; the plugin
does not parse arbitrary shell into verified reference-dependency entities.
Software annotations remain declared versions, not runtime measurements.

### Gaps, safety and overwrite behavior

The explorer shows `captureStatus` and `captureGaps` on source/task evidence
records, and gaps are logged as warnings. `captured` means the requested observable
fields were captured, **not** that the run is certified reproducible. Missing
container identity or Git metadata, absent scripts and unsupported values remain
visible. Unexpected capture errors are logged and prevent WRROC generation rather
than producing an apparently complete report.

**Review before sharing:** expanded scripts, declared task values,
resolved configuration and selected source files can contain credentials,
patient/sample identifiers or other sensitive information. Evidence capture
preserves scripts verbatim; it does not promise automatic redaction. It does not
add log files or environment dumps to the crate. Keep evidence disabled when these
contents must not be exported, or adjust the workflow's handling of sensitive
values before enabling it.

Source snapshot files follow the report's overwrite policy. With
`overwrite = true`, linked source files are replaced; older, unreferenced files
may remain in an existing directory. Reports from older plugin builds used
`prov-evidence/tasks/` with hidden `.command.sh`/`.command.run` files. New reports
do not link to or create those task files, but do not automatically delete existing
archives. Prefer a separate output directory per run for archival/sharing.

The portable HTML embeds the command text along with the metadata, so task scripts
remain readable without accompanying files. Keep the HTML with the crate only
when following links to outputs or explicitly selected source snapshots.

## Offline metadata explorer

Enable the bundled, self-contained HTML explorer:

```groovy
prov {
    formats {
        wrroc {
            file = 'ro-crate-metadata.json'
            overwrite = true
            preview = true
            license = 'https://spdx.org/licenses/MIT'
        }
    }
}
```

`preview` defaults to `false`, preserving existing output behavior. When enabled,
`ro-crate-preview.html` is written beside the metadata and follows the same
`overwrite` policy. A pre-existing preview is checked before the run starts.
The preview embeds the complete graph, not a pruned graph rooted at one output.
It provides search, type filters, incoming/outgoing references, workflow-run
input/output filters, all property values and original entity JSON.

Open the page directly in a browser; no server or network connection is required.
It does not upload metadata or fetch external references. File links are followed
only when clicked, and original-machine paths are displayed rather than treated
as packaged files. The explorer is not a schema validator or completeness audit.

### Regenerate from an existing crate

From this repository, using the build's Java 21 toolchain:

```bash
./gradlew generateExplorer \
  -Pmetadata=/path/to/ro-crate-metadata.json
```

This uses the **same generator and template** as the plugin, without running a
workflow or changing the metadata. The default destination is
`ro-crate-preview.html` beside the input. To choose another destination or
explicitly replace a previous preview:

```bash
./gradlew generateExplorer \
  -Pmetadata=/path/to/ro-crate-metadata.json \
  -Poutput=/path/to/preview.html \
  -Poverwrite=true
```

Use **Load another crate** or drag a metadata JSON file onto an open explorer to
reuse it. **Save portable page** downloads a new HTML page with the currently
loaded metadata embedded. Keep that page beside its corresponding crate metadata
for crate-relative file links to work. A file-picker selection alone does not
give the browser access to the rest of the crate.

The explorer also accepts existing FAIRSCAPE metadata and ID-keyed `@graph`
objects. It indexes explicit ID references; it is not a full JSON-LD expansion
engine. Anything absent from the source metadata remains absent from the view.
