package resource

class ResultStatus {
    static final Map<String, String> STAGE_STATUS = [
        'SUCCESS': 'SUCCESS',
        'FAILURE': 'FAILURE',
        'ABORTED': 'ABORTED',
        'SKIPPED': 'SKIPPED'
    ]

    static final Map<String, String> BUILD_STATUS = [
        'SUCCESS': 'SUCCESS',
        'UNSTABLE': 'UNSTABLE',
        'FAILURE': 'FAILURE',
        'ABORTED': 'ABORTED',
        'NOT_BUILT': 'NOT_BUILT'
    ]
}