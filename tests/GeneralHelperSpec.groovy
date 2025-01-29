import spock.lang.Specification

class GeneralHelperSpec extends Specification {

    def "parseTicketNumber should extract ticket numbers"() {
        setup:
        def helper = new GroovyShell().parse(new File('groovy/generalHelper.groovy'))

        expect:
        helper.parseTicketNumber(branchName) == expected

        where:
        branchName         | expected
        "feature/ABC-123"  | "ABC-123"
        "hotfix/DEF-456"   | "DEF-456"
        "release/no-match" | null
    }
}
