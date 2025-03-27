package resource

import spock.lang.Specification

class ResultStatusTest extends Specification {

    def "STAGE_STATUS map should have correct key-value pairs"() {
        given: 'Expected STAGE_STATUS map'
        def expectedStageStatus = [
            'SUCCESS': 'SUCCESS',
            'UNSTABLE': 'UNSTABLE',
            'FAILURE': 'FAILURE',
            'ABORTED': 'ABORTED',
            'SKIPPED': 'SKIPPED'
        ]

        when: 'Accessing the STAGE_STATUS map'
        def actualStageStatus = ResultStatus.STAGE_STATUS

        then: 'The map should match the expected values'
        actualStageStatus == expectedStageStatus
        actualStageStatus.size() == 5 // Ensure the map has exactly 5 entries
    }

    def "BUILD_STATUS map should have correct key-value pairs"() {
        given: 'Expected BUILD_STATUS map'
        def expectedBuildStatus = [
            'SUCCESS': 'SUCCESS',
            'UNSTABLE': 'UNSTABLE',
            'FAILURE': 'FAILURE',
            'ABORTED': 'ABORTED',
            'NOT_BUILT': 'NOT_BUILT'
        ]

        when: 'Accessing the BUILD_STATUS map'
        def actualBuildStatus = ResultStatus.BUILD_STATUS

        then: 'The map should match the expected values'
        actualBuildStatus == expectedBuildStatus
        actualBuildStatus.size() == 5 // Ensure the map has exactly 5 entries
    }

}
