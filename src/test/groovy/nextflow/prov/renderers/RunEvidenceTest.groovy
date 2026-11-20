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
import java.nio.file.Path

import groovy.json.JsonSlurper
import nextflow.Session
import nextflow.script.WorkflowMetadata
import spock.lang.Specification
import spock.lang.TempDir

class RunEvidenceTest extends Specification {
    @TempDir
    Path root

    Session session(Path project) {
        final metadata = Mock(WorkflowMetadata) {
            getProjectDir() >> project
        }
        Mock(Session) {
            getWorkflowMetadata() >> metadata
        }
    }

    String git(Path project, String... args) {
        final command = ['git', '-C', project.toString()] + args.toList()
        final process = new ProcessBuilder(command).redirectErrorStream(true).start()
        final output = process.inputStream.text
        assert process.waitFor() == 0 : output
        output.trim()
    }

    def 'should retain commit and dirty status with only explicitly selected local changes' () {
        given:
        final project = Files.createDirectories(root.resolve('project'))
        final crate = Files.createDirectories(root.resolve('crate'))
        git(project, 'init')
        project.resolve('main.nf').text = 'original'
        git(project, 'add', 'main.nf')
        git(project, '-c', 'user.name=Test', '-c', 'user.email=test@example.org',
            '-c', 'commit.gpgsign=false', 'commit', '-m',
            'Test fixture\n\nCo-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>')
        final commit = git(project, 'rev-parse', 'HEAD')
        git(project, 'remote', 'add', 'origin', 'https://token@example.org/project.git?credential=secret')
        project.resolve('main.nf').text = 'modified'
        project.resolve('helper file.nf').text = 'untracked helper'
        project.resolve('.env').text = 'not selected'
        final evidence = new RunEvidence(crate, ['main.nf', './main.nf', 'helper file.nf'])

        when:
        evidence.captureSource(session(project))
        final properties = evidence.source.additionalProperty.collectEntries { [(it.name): it.value] }

        then:
        properties.commit == commit
        properties.dirty
        properties.repository == 'https://example.org/project.git'
        new JsonSlurper().parseText(properties.workingTreeStatus).any { it.contains('main.nf') }
        properties.captureGaps.contains('only explicitly selected')
        crate.resolve('prov-evidence/source/main.nf').text == 'modified'
        crate.resolve('prov-evidence/source/helper file.nf').text == 'untracked helper'
        evidence.entities.find { it['@id'] == 'prov-evidence/source/helper%20file.nf' }.contentUrl == 'prov-evidence/source/helper%20file.nf'
        evidence.entities.count { it['@id'] == 'prov-evidence/source/main.nf' } == 1
        !Files.exists(crate.resolve('prov-evidence/source/.env'))
        evidence.entities.find { it['@id'] == 'prov-evidence/source/main.nf' }.sha256.size() == 64

        when:
        project.resolve('main.nf').text = 'changed after capture'
        evidence.captureSource(session(project))

        then:
        crate.resolve('prov-evidence/source/main.nf').text == 'modified'
    }

    def 'should reject source snapshots escaping the project including symlinks' () {
        given:
        final project = Files.createDirectories(root.resolve('project'))
        final outside = root.resolve('private.txt')
        outside.text = 'do not copy'
        Files.createSymbolicLink(project.resolve('link.nf'), outside)

        when:
        new RunEvidence(root.resolve('crate'), [name]).captureSource(session(project))

        then:
        thrown(IllegalArgumentException)

        where:
        name << ['../private.txt', 'link.nf']
    }
}
