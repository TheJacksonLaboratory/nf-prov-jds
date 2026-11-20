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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * Generates the same offline explorer during a run or from an existing crate.
 */
@CompileStatic
class MetadataExplorer {

    static void write(Path metadata, Path output, boolean overwrite = false) {
        final input = metadata.toAbsolutePath().normalize()
        final target = output.toAbsolutePath().normalize()
        if( input == target || (Files.exists(target) && Files.isSameFile(input, target)) )
            throw new IllegalArgumentException('The HTML output must not overwrite the metadata')
        if( Files.exists(target) && !overwrite )
            throw new java.nio.file.FileAlreadyExistsException(target.toString())
        final crate = new JsonSlurper().parseText(Files.readString(input, StandardCharsets.UTF_8))
        if( !(crate instanceof Map) || !(((Map) crate)['@graph'] instanceof List || ((Map) crate)['@graph'] instanceof Map) )
            throw new IllegalArgumentException('Expected metadata containing an @graph array or object')
        final graph = ((Map) crate)['@graph']
        final entries = graph instanceof List
            ? ((List) graph).collect { [null, it] }
            : ((Map) graph).containsKey('@id') ? [[null, graph]] : ((Map) graph).collect { key, value -> [key, value] }
        final ids = new HashSet<String>()
        if( !entries )
            throw new IllegalArgumentException('The metadata graph contains no entities')
        for( final entry : entries ) {
            final value = entry[1]
            final id = value instanceof Map ? (((Map) value)['@id'] ?: entry[0]) : null
            if( !(id instanceof String) || !id )
                throw new IllegalArgumentException('Every graph entity requires a nonempty string @id')
            if( !ids.add((String) id) )
                throw new IllegalArgumentException("Duplicate graph entity ID: ${id}")
        }
        final resource = MetadataExplorer.getResourceAsStream('/nextflow/prov/metadata-explorer.html')
        if( resource == null )
            throw new IllegalStateException('Missing bundled metadata explorer template')
        final template = resource.withCloseable { it.getText('UTF-8') }
        final json = JsonOutput.toJson(crate).replace('<', '\\u003c')
        final html = template.replace('<!--__CRATE_DATA__-->', json)
        Files.createDirectories(target.parent)
        final temporary = Files.createTempFile(target.parent, '.metadata-explorer-', '.tmp')
        try {
            Files.writeString(temporary, html, StandardCharsets.UTF_8)
            if( overwrite )
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            else
                Files.move(temporary, target)
        }
        finally {
            Files.deleteIfExists(temporary)
        }
    }

    static void main(String[] args) {
        if( args.length < 1 || args.length > 3 || (args.length == 3 && args[2] != '--overwrite') )
            throw new IllegalArgumentException('Usage: MetadataExplorer METADATA_JSON [OUTPUT_HTML] [--overwrite]')
        final input = Path.of(args[0])
        final output = args.length >= 2 ? Path.of(args[1]) : input.resolveSibling('ro-crate-preview.html')
        write(input, output, args.length == 3)
        println "Metadata explorer: ${output.toAbsolutePath()}"
    }
}
