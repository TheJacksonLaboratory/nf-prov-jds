/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.prov.renderers

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskRun
import nextflow.script.ScriptType
import nextflow.script.params.EnvInParam
import nextflow.script.params.EnvOutParam
import nextflow.script.params.EachInParam
import nextflow.script.params.ValueInParam
import nextflow.script.params.ValueOutParam

/**
 * Small, opt-in execution evidence. External datasets and process environments
 * are deliberately not traversed or copied.
 */
@Slf4j
@CompileStatic
class RunEvidence {

    final Path crateDir
    final List<String> sourceFiles
    final List<Map> entities = []
    final Map<String,Map> tasks = [:]
    Map source

    RunEvidence(Path crateDir, List<String> sourceFiles) {
        this.crateDir = crateDir
        this.sourceFiles = sourceFiles
    }

    static String taskKey(TaskRun task) {
        return task.hash?.toString() ?: "unhashed-${task.id}-${task.config?.getAttempt() ?: 1}".toString()
    }

    synchronized Map captureTask(TaskRun task) {
        final key = taskKey(task)
        if( tasks.containsKey(key) )
            return tasks[key]
        final taskId = "#task/${key}".toString()
        final gaps = [] as List<String>
        final parts = [] as List<Map>
        if( task.type == ScriptType.SCRIPTLET ) {
            for( final filename : ['.command.sh', '.command.run'] ) {
                if( task.workDir == null ) {
                    gaps.add("${filename}: task work directory unavailable".toString())
                    continue
                }
                final target = "prov-evidence/tasks/${key}/${filename}".toString()
                final file = copy(task.workDir.resolve(filename), target, gaps)
                if( file ) {
                    file['about'] = ['@id': taskId]
                    parts.add(['@id': file['@id']])
                }
            }
        }
        final inputs = values(task.inputs ?: [:], true, task.hasTypedInputsOutputs(), taskId, gaps)
        final outputs = task.canBind ? values(task.outputs ?: [:], false, task.hasTypedInputsOutputs(), taskId, gaps) : []
        if( !task.canBind )
            gaps.add('Task outputs were not collected; no scalar results recorded')
        final settings = [] as List<Map>
        settings.add(property('cached', task.cached))
        settings.add(property('outputsCollected', task.canBind))
        settings.add(property('scriptType', task.type.toString()))
        if( task.exitStatus != null )
            settings.add(property('exitStatus', task.exitStatus))
        final config = task.config
        if( config ) {
            settings.add(property('cpus', config.getCpus()))
            if( config.getMemory() ) settings.add(property('memoryBytes', config.getMemory().toBytes()))
            if( config.getTime() ) settings.add(property('timeLimitMillis', config.getTime().toMillis()))
            if( config.getAttempt() ) settings.add(property('attempt', config.getAttempt()))
        }
        else
            gaps.add('Resolved task execution settings unavailable')
        final executor = task.processor?.executor?.name
        if( executor ) settings.add(property('executor', executor))
        final container = task.container
        if( container ) {
            settings.add(property('container', container))
            final enabled = task.type == ScriptType.SCRIPTLET && task.isContainerEnabled()
            settings.add(property('containerEnabled', enabled))
            final engine = task.getContainerConfig()?.getEngine()
            if( engine ) settings.add(property('containerEngine', engine))
            settings.add(property('containerIdentity', container ==~ /(?i).*@sha256:[0-9a-f]{64}/ ? 'digest-qualified identifier' : 'tag/path identifier; immutable digest not established'))
            if( !enabled )
                gaps.add('Container identifier is configured/resolved, but this task is not container-enabled')
        }
        else
            gaps.add('No container identifier available; task may run without a container')
        final record = [
            '@id': "${taskId}/evidence".toString(),
            '@type': 'CreativeWork',
            'name': "Execution evidence: ${task.name}".toString(),
            'about': ['@id': taskId],
            'description': 'Generated task scripts, not a trace of every command executed internally by tools. Native exec tasks have no shell wrapper. Scalar values are observations, not inferred cross-task dependency edges.',
            'hasPart': parts,
            'additionalProperty': settings + [property('captureStatus', gaps ? 'partial' : 'captured'),
                property('captureGaps', JsonOutput.toJson(gaps))]
        ] as Map
        entities.add(record)
        final result = [record: record, inputs: inputs, outputs: outputs] as Map
        tasks[key] = result
        for( final gap : gaps )
            log.warn "WRROC evidence for ${task.name}: ${gap}"
        return result
    }

    private List<Map> values(Map parameters, boolean input, boolean typed, String taskId, List<String> gaps) {
        final result = [] as List<Map>
        int index = 0
        parameters.each { param, value ->
            final supported = typed || (input
                ? param instanceof ValueInParam || param instanceof EachInParam || param instanceof EnvInParam
                : param instanceof ValueOutParam || param instanceof EnvOutParam)
            if( !supported )
                return
            final name = input
                ? ((nextflow.script.params.InParam) param).name
                : ((nextflow.script.params.OutParam) param).name
            final id = "${taskId}/value/${input ? 'input' : 'output'}/${index++}".toString()
            try {
                final normalized = jsonValue(value, 0)
                final encoded = JsonOutput.toJson(normalized)
                if( encoded.length() > 65536 )
                    throw new IllegalArgumentException('value exceeds 65536 serialized characters')
                final scalar = normalized instanceof String || normalized instanceof Number || normalized instanceof Boolean
                final entity = [
                    '@id': id, '@type': 'PropertyValue', 'name': name ?: '(unnamed)',
                    'description': "Resolved task ${input ? 'input' : 'output'}; compound and null values use JSON text.".toString(),
                    'value': scalar ? normalized : encoded
                ] as Map
                if( !scalar ) entity['encodingFormat'] = 'application/json'
                entities.add(entity)
                result.add(['@id': id])
            }
            catch( IllegalArgumentException e ) {
                gaps.add("Could not capture ${input ? 'input' : 'output'} ${name}: ${e.message}".toString())
            }
        }
        if( typed )
            gaps.add('Typed task values include path locations as text; scalar-only dependency relationships are not inferred')
        return result
    }

    private static Object jsonValue(Object value, int depth) {
        if( depth > 16 )
            throw new IllegalArgumentException('value nesting exceeds 16 levels')
        if( value == null || value instanceof Boolean || value instanceof Number )
            return value
        if( value instanceof CharSequence || value instanceof Path )
            return value.toString()
        if( value instanceof List )
            return ((List) value).collect { jsonValue(it, depth + 1) }
        if( value instanceof Map ) {
            final result = [:] as Map<String,Object>
            ((Map) value).each { key, item ->
                if( !(key instanceof CharSequence) )
                    throw new IllegalArgumentException('map keys must be strings')
                result[key.toString()] = jsonValue(item, depth + 1)
            }
            return result
        }
        throw new IllegalArgumentException("unsupported value type ${value.class.name}".toString())
    }

    synchronized void captureSource(Session session) {
        if( source != null )
            return
        final project = session.workflowMetadata.projectDir.toAbsolutePath().normalize()
        final gaps = [] as List<String>
        final properties = [] as List<Map>
        final parts = [] as List<Map>
        properties.add(property('capturedAt', java.time.Instant.now().toString()))
        try {
            captureGit(project, properties, gaps)
        }
        catch( IOException e ) {
            gaps.add("Git source inspection failed: ${e.message}".toString())
        }
        if( session.workflowMetadata.repository )
            properties.add(property('nextflowRepository', sanitizeUrl(session.workflowMetadata.repository)))
        if( session.workflowMetadata.commitId )
            properties.add(property('nextflowCommit', session.workflowMetadata.commitId))
        final selected = new HashSet<Path>()
        for( final name : sourceFiles ) {
            final relative = Path.of(name)
            final file = project.resolve(relative).normalize()
            if( relative.isAbsolute() || !file.startsWith(project) || file == project )
                throw new IllegalArgumentException("sourceFiles must stay inside the project: ${name}")
            if( Files.isDirectory(file) )
                throw new IllegalArgumentException("sourceFiles must list individual files, not directories: ${name}")
            if( Files.exists(file) && !file.toRealPath().startsWith(project.toRealPath()) )
                throw new IllegalArgumentException("sourceFiles must not follow links outside the project: ${name}")
            if( !selected.add(file) )
                continue
            final sourcePath = project.relativize(file).toString().replace(File.separator, '/')
            final entity = copy(file, "prov-evidence/source/${sourcePath}".toString(), gaps)
            if( entity ) {
                entity['description'] = "Explicit source snapshot; original project-relative path: ${project.relativize(file)}".toString()
                parts.add(['@id': entity['@id']])
            }
        }
        source = [
            '@id': '#source-evidence', '@type': 'CreativeWork', 'name': 'Workflow source identity',
            'description': 'Repository identity and explicitly selected source snapshots, not a complete source bundle. External inputs/references are not bundled or content-verified. Review source status and capture gaps before claiming repeatability.',
            'hasPart': parts,
            'additionalProperty': properties + [property('captureStatus', gaps ? 'partial' : 'captured'),
                property('captureGaps', JsonOutput.toJson(gaps))]
        ] as Map
        entities.add(source)
        for( final gap : gaps )
            log.warn "WRROC source evidence: ${gap}"
    }

    private static void captureGit(Path project, List<Map> properties, List<String> gaps) {
        final root = git(project, ['rev-parse', '--show-toplevel'])
        if( root.code == 0 ) {
            properties.add(property('projectSubdirectory', Path.of(((String) root.text).trim()).relativize(project).toString()))
            final head = git(project, ['rev-parse', 'HEAD'])
            if( head.code == 0 )
                properties.add(property('commit', ((String) head.text).trim()))
            else
                gaps.add('Git HEAD unavailable (repository may have no commits)')
            final status = git(project, ['status', '--porcelain=v1', '-z', '--untracked-files=normal', '--', '.'])
            if( status.code != 0 )
                throw new IOException("Cannot inspect source status: ${status.text}")
            properties.add(property('workingTreeStatus', JsonOutput.toJson(((String) status.text).tokenize('\u0000'))))
            properties.add(property('dirty', !((String) status.text).isEmpty()))
            final remote = git(project, ['config', '--get', 'remote.origin.url'])
            if( remote.code == 0 )
                properties.add(property('repository', sanitizeUrl(((String) remote.text).trim())))
            else if( remote.code == 1 )
                gaps.add('Git origin URL unavailable')
            else
                throw new IOException("Cannot inspect source remote: ${remote.text}")
            if( status.text )
                gaps.add('Working tree has changes/untracked files; only explicitly selected sourceFiles are snapshotted')
        }
        else if( root.code == 128 )
            gaps.add('Project is not a readable Git working tree; source commit and dirty status unavailable')
        else
            throw new IOException("Cannot inspect source repository: ${root.text}")
    }

    private static String sanitizeUrl(String url) {
        return url.replaceFirst('([A-Za-z][A-Za-z0-9+.-]*://)[^/@]+@', '$1').replaceFirst('[?#].*$', '')
    }

    private static Map git(Path project, List<String> arguments) {
        final command = ['git', '--no-pager', '--no-optional-locks', '-c', 'core.fsmonitor=false', '-C', project.toString()] + arguments
        final process = new ProcessBuilder(command).redirectErrorStream(true).start()
        final text = process.inputStream.withCloseable { it.getText('UTF-8') }
        return [code: process.waitFor(), text: text]
    }

    private Map copy(Path source, String relative, List<String> gaps) {
        final target = crateDir.resolve(relative)
        if( !Files.isRegularFile(source) ) {
            gaps.add("Unavailable file: ${source}".toString())
            return null
        }
        Path ancestor = target
        while( ancestor != crateDir && ancestor != null ) {
            if( Files.isSymbolicLink(ancestor) )
                throw new IOException("Refusing evidence output through symbolic link: ${ancestor}")
            ancestor = ancestor.parent
        }
        Files.createDirectories(target.parent)
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
        catch( NoSuchFileException e ) {
            if( Files.exists(source, LinkOption.NOFOLLOW_LINKS) )
                throw e
            gaps.add("File disappeared before capture: ${source}".toString())
            return null
        }
        final digest = MessageDigest.getInstance('SHA-256')
        target.withInputStream { stream ->
            final buffer = new byte[8192]
            int count
            while( (count = stream.read(buffer)) != -1 )
                digest.update(buffer, 0, count)
        }
        final id = new URI(null, null, relative, null).toASCIIString()
        final entity = [
            '@id': id, '@type': 'File', 'name': source.fileName.toString(),
            'contentUrl': id, 'encodingFormat': 'text/plain',
            'sha256': HexFormat.of().formatHex(digest.digest()), 'contentSize': Files.size(target)
        ] as Map
        entities.add(entity)
        return entity
    }

    private static Map property(String name, Object value) {
        return ['@type': 'PropertyValue', name: name, value: value]
    }
}
