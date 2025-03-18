import spock.lang.Specification

class UnityHelper extends Specification {

    def helper

    def setup(){
        helper = new GroovyShell().parse(new File('groovy/unityHelper.groovy'))
    }

    // THIS GPT code but use it as foundation to understand 
    def "should pass when both lighting and reflection probe files exist"() {
        
        given:
        helper.sh(_) >> "true"

        when:
        helper.validateBuildLightingFiles()

        then:
        noExceptionThrown()
    }

    def "should fail when lighting file is missing"() {
        given:
        helper.sh({ it.script.contains("LightingData.asset") }) >> "false"
        helper.sh({ it.script.contains("ReflectionProbe-0.exr") }) >> "true"

        when:
        helper.validateBuildLightingFiles()

        then:
        def e = thrown(Exception)
        e.message.contains("Lighting file NOT found")
    }

    def "should fail when reflection probe lighting file is missing"() {
        given:
        helper.sh({ it.script.contains("LightingData.asset") }) >> "true"
        helper.sh({ it.script.contains("ReflectionProbe-0.exr") }) >> "false"

        when:
        helper.validateBuildLightingFiles()

        then:
        def e = thrown(Exception)
        e.message.contains("Reflection Probe Lighting file NOT found")
    }

    def "should fail when both files are missing"() {
        given:
        helper.sh(_) >> "false"

        when:
        scriptMock.validateBuildLightingFiles()

        then:
        def e = thrown(Exception)
        e.message.contains("Lighting file NOT found") && e.message.contains("Reflection Probe Lighting file NOT found")
    }
}
