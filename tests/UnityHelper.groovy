import spock.lang.Specification
import groovy.io.FileType

class UnityHelper extends Specification {

    def helper
    def projectDIR

    def setup(){
        helper = new GroovyShell().parse(new File('groovy/unityHelper.groovy'))
        projectDIR = System.getenv("PROJECT_DIR")
    }

    def "checking file existence or warning should return when given correct path"() {
        given:
        def tempDir = new File('tempTestDir')
        tempDir.mkdirs()

        when:
        helper.ensureFileExistOrWarn()

        then:
        true

        cleanUp:
        tempDir.deleteDir()
    }

    def "checking file existence or warning should return with error and log of file path not existing with warn message"() {
        given:
        def fakePath = "/FakeGibberishForTest"
        def warnMessage = "Essential Fake Gibberish file not found"
        
        when:
        helper.ensureFileExistOrWarn()

        then:
        def e = thrown(Exception)
        e.message.contains("${warnMessage}: Path given ${filepath}}")
    }

}
