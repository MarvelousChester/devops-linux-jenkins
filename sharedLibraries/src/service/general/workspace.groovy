def prepareWorkSpace(){
    dir("${PROJECT_DIR}") {
    //send 'In Progress' status to Bitbucket
        script {
            // print jenkins env configurations
            sh 'env'
            try {
                // Load the necessary packages
                resultStatus = sharedLib.resource.ResultStatus

                generalUtil = load("${env.WORKSPACE}/groovy/generalHelper.groovy")
                unityUtil = load("${env.WORKSPACE}/groovy/unityHelper.groovy")

                if (generalUtil.isBranchUpToDateWithRemote(PR_BRANCH) && !TEST_RUN.equals('Y')) {
                    echo 'Local branch commit is up to date with remote branch, no changes. Aborting pipeline.'
                    currentBuild.result = resultStatus.BUILD_STATUS.ABORTED
                    error('Branch is up to date, no changes.')
                }

                COMMIT_HASH = generalUtil.getFullCommitHash(env.WORKSPACE, PR_COMMIT)

                generalUtil.initializeEnvironment(env.WORKSPACE, COMMIT_HASH, PR_BRANCH)

                generalUtil.cloneOrUpdateRepo(PROJECT_TYPE, env.WORKSPACE, PROJECT_DIR, REPO_SSH, PR_BRANCH)

                generalUtil.mergeBranchIfNeeded()
            } catch (Exception e) {
                error "Failed for Prepare WORKSPACE: ${e.message}"
            }
        }
    }

    // Unity Setup: Identify the version of Unity Editor for the project
    echo 'Identifying Unity version...'
    script {
        env.UNITY_EXECUTABLE = unityUtil.getUnityExecutable(env.WORKSPACE, PROJECT_DIR)
    }

    // Initial running the project on Unity Editor
    echo 'Running Unity in batch mode to setup initial files...'
    dir("${PROJECT_DIR}") {
        script {
            env.UNITY_EXECUTABLE = unityUtil.getUnityExecutable(env.WORKSPACE, PROJECT_DIR)
        }

        script {
            String stageName = 'Rider'
            String errorMassage = 'Synchronizing Unity and Rider IDE solution files failed'
            unityUtil.runUnityStage(stageName, errorMassage)
        }
    }
}



return this