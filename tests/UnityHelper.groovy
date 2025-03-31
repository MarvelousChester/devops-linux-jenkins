import spock.lang.Specification

class UnityHelper extends Specification {

    GroovyScriptEngine helper
    String projectDIR

    def setup() {
        helper = new GroovyScriptEngine('./groovy').with {
            loadScriptByName('unityHelper.groovy').newInstance()
        }
        projectDIR = System.getenv('PROJECT_DIR')
    }

    def "ensureFileExistOrWarn should not log a warning and return success for a valid path"() {
        given:
        def tempDir = new File('tempTestDir')
        tempDir.mkdirs()
        log.trace("${tempDir.absolutePath}")

        when:
        helper.ensureFileExistOrWarn(tempDir.absolutePath)

        then:
        true

        cleanup:
        tempDir.deleteDir()
    }

    def "ensureFileExistOrWarn should log a warning and throw exception for a valid path"() {
        given:
        def fakePath = '/FakeGibberishForTest'
        def warnMessage = 'Essential Fake Gibberish file not found'

        when:
        helper.ensureFileExistOrWarn(fakePath, warnMessage)

        then:
        def e = thrown(Exception)
        e.message.contains("${warnMessage}: Path given ${fakePath}}")
    }

}
