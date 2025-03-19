import spock.lang.Specification
import groovy.io.FileType

class UnityHelper extends Specification {

    def helper

    def setup(){
        helper = new GroovyShell().parse(new File('groovy/unityHelper.groovy'))
    }

    // THIS GPT code but use it as foundation to understand 
    def "should pass when both lighting and reflection probe files exist"() {
        
        given:
        def lightingFile = new File("${env.PROJECT_DIR}/Assets/Scenes/Main Scene/LightingData.asset")
        lightingFile.mkdirs()
        
        def reflectionProbe = new File("${env.PROJECT_DIR}/Assets/Scenes/Main Scene/ReflectionProbe-0.exr.asset")
        reflectionProbe.mkdirs()

        when:
        helper.validateBuildLightingFiles()

        then:
        true
    }

    def "should fail when lighting file is missing"() {
        given:
        def reflectionProbe = new File("${env.PROJECT_DIR}/Assets/Scenes/Main Scene/ReflectionProbe-0.exr.asset")
        reflectionProbe.mkdirs()

        when:
        helper.validateBuildLightingFiles()

        then:
        def e = thrown(Exception)
        e.message.contains("Lighting file NOT found")
    }

    def "should fail when reflection probe lighting file is missing"() {
        given:
        def lightingFile = new File("${env.PROJECT_DIR}/Assets/Scenes/Main Scene/LightingData.asset")
        lightingFile.mkdirs()

        when:
        helper.validateBuildLightingFiles()

        then:
        def e = thrown(Exception)
        e.message.contains("Reflection Probe Lighting file NOT found")
    }

    def "should fail when both files are missing"() {
        given:
        
        when:
        scriptMock.validateBuildLightingFiles()

        then:
        def e = thrown(Exception)
        e.message.contains("Lighting file NOT found") && e.message.contains("Reflection Probe Lighting file NOT found")
    }
}
