/**
 * This function encapsulates the calling of our helper python script which will create and send
 * the results of the Unity tests back to the BitBucket PR for developers to read.
 *
 * @param workspace The workspace directory where the Jenkins Files and Project Files are located.
 * @param reportDir The directory that holds the test reports.
 * @param commitHash The short commit hash that triggered this pipeline run.
 */
def sendTestReport(workspace, reportDir, commitHash) {
    sh "python \'${workspace}/python/create_bitbucket_test_report.py\' \'${commitHash}\' \'${reportDir}\'"
}

/**
 * Parses a log file to identify errors using a Python script.
 * This function calls a helper Python script to analyze the specified log file
 * and returns the output from the script.
 * 
 * @param logPath The path to the log file that needs to be analyzed for errors.
 * @return The output from the Python script, which contains the analysis results.
 */
def parseLogsForError(logPath) {
    return sh (script: "python \'${workspace}/python/get_unity_failure.py\' \'${logPath}\'", returnStdout: true)
}

/**
 * Retrieves the path to the Unity executable for the specified project directory.
 * If the Unity executable is not found locally, the function attempts to install the required Unity version
 * and WebGL Build Support using Unity Hub.
 * 
 * @param workspace The workspace directory where the Jenkins Files and Project Files are located
 * @param projectDir The project directory for which the Unity executable path is required.
 * @return The path to the Unity executable as a string.
 * @throws Exception If an error occurs during the retrieval or installation of the Unity executable.
 */
def getUnityExecutable(workspace, projectDir) {
    try {
        def unityExecutable = sh(script: "python '${workspace}/python/get_unity_version.py' '${projectDir}' executable-path", returnStdout: true).trim()
        if (!fileExists(unityExecutable)) {
            def version = sh(script: "python '${workspace}/python/get_unity_version.py' '${projectDir}' version", returnStdout: true).trim()
            def revision = sh(script: "python '${workspace}/python/get_unity_version.py' '${projectDir}' revision", returnStdout: true).trim()

            echo "Unity Editor version ${version} not found. Attempting installation..."
            def installCommand = "\"C:\\Program Files\\Unity Hub\\Unity Hub.exe\" -- --headless install --version ${version} --changeset ${revision}"
            def exitCode = sh(script: installCommand, returnStatus: true)
            if (exitCode != 0) {
                error("Failed to install Unity Editor version ${version}.")
            }

            echo "Installing WebGL Build Support..."
            def webglInstallCommand = "\"C:\\Program Files\\Unity Hub\\Unity Hub.exe\" -- --headless install-modules --version ${version} -m webgl"
            exitCode = sh(script: webglInstallCommand, returnStatus: true)
            if (exitCode != 0) {
                error("Failed to install WebGL Build Support for Unity version ${version}.")
            }
        }
        return unityExecutable
    } catch (Exception e) {
        error("An error occurred while retrieving or installing Unity executable: ${e.getMessage()}")
    }
}

/**
 * Runs a Unity project's tests for a specified test type with optional code coverage and test reporting.
 * This function executes Unity tests in batch mode and provides options for logging, code coverage,
 * and error handling based on the deployment type.
 * 
 * @param unityExecutable The path to the Unity executable to be used for running the tests.
 * @param reportDir The directory where test result logs and reports will be stored.
 * @param projectDir The directory of the Unity project to be tested.
 * @param testType The type of tests to run (e.g., "EditMode" or "PlayMode").
 * @param enableReporting A boolean flag indicating whether code coverage and test reporting should be enabled.
 * @param deploymentBuild A boolean flag indicating whether to fail the build pipeline on test failure.
 *                        If true, the build pipeline will abort on test failures; otherwise, it will proceed.
 */
def runUnityTests(unityExecutable, reportDir, projectDir, testType, enableReporting, deploymentBuild) {
    // Define the log file path for the test results
    def logFile = "${reportDir}/test_results/${testType}-tests.log"
    
    // Function to get report settings based on whether reporting is enabled
    def getReportSettings = { rDir, tType, eReporting ->
        if (eReporting) {
            // Return settings for test results, code optimization, and code coverage
            return """ \
            -testResults ${rDir}/test_results/${tType}-results.xml \
            -debugCodeOptimization \
            -enableCodeCoverage \
            -coverageResultsPath ${rDir}/coverage_results \
            -coverageOptions useProjectSettings"""
        } else {
            // Return settings for test results and code optimization only
            return """ \
            -testResults ${rDir}/test_results/${tType}-results.xml \
            -debugCodeOptimization"""
        }
    }

    // Function to construct flags for the Unity test execution command
    def getFlags = { pDir, tType, lFile, rSettings ->
        def flags = "-projectPath ${pDir} \
            -batchmode \
            -testPlatform ${tType} \
            -runTests \
            -logFile ${lFile}${rSettings}"

        // Add specific flags based on the test type
        if (tType == "EditMode") {
            flags += " -nographics"
        }
        return flags
    }

    // Function to handle the exit code from the Unity test execution
    def handleExitCode = { eCode, dBuild ->
        if (eCode != 0) {
            if (dBuild) {
                // If deployment build, fail the build with an error
                error("Test failed with exit code ${eCode}. Check the log file for more details.")
                sh "exit ${eCode}"
            }
            // Catch error and mark the build as failed
            catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                error("Test failed with exit code ${eCode}. Check the log file for more details.")
            }
        }
    }

    // Get the report settings based on the parameters
    def reportSettings = getReportSettings(reportDir, testType, enableReporting)
    // Get the flags for the Unity command
    def flags = getFlags(projectDir, testType, logFile, reportSettings)

    def exitCode = "";
    // Execute Unity tests based on the test type
    if (testType == "EditMode") {
        exitCode = sh(script: """${unityExecutable} ${flags}""", returnStatus: true)
    }
    else if (testType == "PlayMode") {
        // Use xvfb-run for PlayMode tests to handle graphical requirements
        unityExecutable = "/usr/bin/xvfb-run -a ${unityExecutable}"
        exitCode = sh(script: """${unityExecutable} ${flags}""", returnStatus: true)
    }

    // Handle the exit code from the test execution
    handleExitCode(exitCode, deploymentBuild)
}

/**
 * Builds a Unity project targeting the WebGL platform.
 * This function runs Unity in batch mode to build the specified project and logs the build process.
 * 
 * @param reportDir The directory where the build logs and results will be stored.
 * @param projectDir The directory of the Unity project to be built.
 * @param unityExecutable The path to the Unity executable to use for building the project.
 * @throws Exception If the build process fails, the function exits with the corresponding error code.
 */
def buildProject(reportDir, projectDir, unityExecutable) {
    def logFile = "${reportDir}/build_project_results/build_project.log"

    def exitCode = sh (script:"""\"${unityExecutable}\" \
        -quit \
        -batchmode \
        -nographics \
        -projectPath \"${projectDir}\" \
        -logFile \"${logFile}\" \
        -buildTarget WebGL \
        -executeMethod Builder.BuildWebGL""", returnStatus: true)

    if (exitCode != 0) {
        sh "exit ${exitCode}"
    }
}

return this