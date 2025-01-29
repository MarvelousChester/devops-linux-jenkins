import spock.lang.Specification
import groovy.io.FileType

class JsHelperSpec extends Specification {

    def "findTestingDirs should return directories containing package.json"() {
        setup:
        // Create a temporary directory structure for testing
        def tempDir = new File('tempTestDir')
        tempDir.mkdirs()

        // Create subdirectories and files
        def dir1 = new File(tempDir, 'subDir1')
        dir1.mkdirs()
        new File(dir1, 'package.json').text = '{"name": "test-package-1"}'

        def dir2 = new File(tempDir, 'subDir2')
        dir2.mkdirs()
        new File(dir2, 'package.json').text = '{"name": "test-package-2"}'

        def dir3 = new File(tempDir, 'subDir3')
        dir3.mkdirs()
        // No package.json in this directory

        def helper = new GroovyScriptEngine('./groovy').with {
            loadScriptByName('jsHelper.groovy').newInstance()
        }
        println "Helper methods: ${helper.metaClass.methods*.name}"

        when:
        // Call the method under test
        def result = helper.findTestingDirs(tempDir.absolutePath)

        then:
        // Verify the result
        result.split(',') as Set == [dir1.absolutePath, dir2.absolutePath] as Set

        cleanup:
        // Clean up temporary files and directories
        tempDir.deleteDir()
    }

    def "findTestingDirs should return an empty string if no package.json is found"() {
        setup:
        // Create an empty temporary directory
        def tempDir = new File('tempEmptyDir')
        tempDir.mkdirs()

        def helper = new GroovyScriptEngine('./groovy').with {
            loadScriptByName('jsHelper.groovy').newInstance()
        }
        println "Helper methods: ${helper.metaClass.methods*.name}"

        when:
        // Call the method under test
        def result = helper.findTestingDirs(tempDir.absolutePath)

        then:
        // Verify the result
        result == ""

        cleanup:
        // Clean up temporary files and directories
        tempDir.deleteDir()
    }
}
