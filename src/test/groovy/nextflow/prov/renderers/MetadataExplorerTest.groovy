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

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class MetadataExplorerTest extends Specification {

    @TempDir
    Path root

    def 'should embed unmodified metadata without allowing script termination' () {
        given:
        final input = root.resolve('crate.json')
        final output = root.resolve('preview.html')
        final crate = ['@graph': [['@id': './', '@type': 'Dataset', name: '</script><script>alert("test")</script>']]]
        input.text = JsonOutput.toJson(crate)

        when:
        MetadataExplorer.write(input, output)
        final html = output.text
        final matcher = html =~ /(?s)<script id="embedded-crate" type="application\/json">(.*?)<\/script>/
        assert matcher.find()

        then:
        new JsonSlurper().parseText(matcher.group(1)) == crate
        !html.contains('</script><script>alert')
        !html.contains('<!--__CRATE_DATA__-->')
        new JsonSlurper().parseText(input.text) == crate
    }

    def 'should require explicit overwrite and preserve existing output on invalid metadata' () {
        given:
        final input = root.resolve('crate.json')
        final output = root.resolve('preview.html')
        input.text = '{"@graph":[{"@id":"./"}]}'
        output.text = 'keep this'

        when:
        MetadataExplorer.write(input, output)

        then:
        thrown(FileAlreadyExistsException)
        output.text == 'keep this'

        when:
        input.text = '{"unrelated":"document"}'
        MetadataExplorer.write(input, output, true)

        then:
        thrown(IllegalArgumentException)
        output.text == 'keep this'

        when:
        input.text = '{"@graph":{"./":{"@id":"./"}}}'
        MetadataExplorer.write(input, output, true)

        then:
        output.text.contains('"@graph":{"./"')
    }

    def 'should not overwrite the source metadata' () {
        given:
        final input = root.resolve('crate.json')
        input.text = '{"@graph":[{"@id":"./"}]}'

        when:
        MetadataExplorer.write(input, input, true)

        then:
        thrown(IllegalArgumentException)
        input.text == '{"@graph":[{"@id":"./"}]}'
    }

    @Unroll
    def 'should reject graphs the explorer cannot navigate without replacing output: #graph' () {
        given:
        final input = root.resolve('crate.json')
        final output = root.resolve('preview.html')
        input.text = JsonOutput.toJson(['@graph': graph])
        output.text = 'previous preview'

        when:
        MetadataExplorer.write(input, output, true)

        then:
        thrown(IllegalArgumentException)
        output.text == 'previous preview'

        where:
        graph << [[], [[name: 'missing ID']], [['@id': './'], ['@id': './']]]
    }
}
