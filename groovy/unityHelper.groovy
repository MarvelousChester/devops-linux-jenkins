/* groovylint-disable NglParseError */

import groovy.json.JsonSlurper
import hudson.FilePath

// constants for stage names
// groovylint-disable VariableName, UnusedVariable
this.EDIT_MODE = 'EditMode'
this.PLAY_MODE = 'PlayMode'
this.COVERAGE   = 'Coverage'
this.WEBGL     = 'Webgl'
this.RIDER     = 'Rider'
// groovylint-enable VariableName, UnusedVariable

/**
 * This function encapsulates the calling of our helper python script which will create and send
 * the results of the Unity tests back to the BitBucket PR for developers to read.
 *
 * @param workspace The workspace directory where the Jenkins Files and Project Files are located.
 * @param reportDir The directory that holds the test reports.
 * @param commitHash The short commit hash that triggered this pipeline run.
 */
void sendTestReport(workspace, reportDir, commitHash) {
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
String parseLogsForError(logPath) {
    return sh(script: "python \'${workspace}/python/get_unity_failure.py\' \'${logPath}\'", returnStdout: true)
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
String getUnityExecutable(workspace, projectDir) {
    try {
        def unityExecutable = sh(script: "python '${workspace}/python/get_unity_version.py' '${projectDir}' executable-path",
        returnStdout: true).trim()

        if (!fileExists(unityExecutable)) {
            def version = sh(script: "python '${workspace}/python/get_unity_version.py' '${projectDir}' version",
             returnStdout: true).trim()

            def revision = sh(script: "python '${workspace}/python/get_unity_version.py' '${projectDir}' revision",
             returnStdout: true).trim()

            echo "Unity Editor version ${version} not found. Attempting installation..."
            def installCommand = """\"C:\\Program Files\\Unity Hub\\Unity Hub.exe\" \\
            -- --headless install \\
            --version ${version} \\
            --changeset ${revision}"""
            def exitCode = sh(script: installCommand, returnStatus: true)
            if (exitCode != 0) {
                error("Failed to install Unity Editor version ${version}.")
            }

            echo 'Installing WebGL Build Support...'
            def webglInstallCommand = """\"C:\\Program Files\\Unity Hub\\Unity Hub.exe\" \\
            -- --headless install-modules \\
            --version ${version} \\
            -m webgl"""
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
 * Executes a Unity batch mode operation for the specified stage and handles errors.
 *
 * @param stageName The name of the stage to execute (e.g., "EditMode", "PlayMode").
 * @param errorMessage The error message to display if an exception occurs or the exit code is non-zero.
 * @return void
 */
void runUnityStage(String stageName, String errorMessage) {
    // Initialize exit code to -1 (indicating failure by default)
    int exitCode = -1
    try {
        // Run Unity batch mode and capture the exit code
        exitCode = runUnityBatchMode(env.UNITY_EXECUTABLE, env.PROJECT_DIR, env.REPORT_DIR, stageName)
    } catch (Exception e) {
        // Log exception details and terminate the pipeline with an error message
        echo "Exception type: ${e.class.name}"
        echo "Stack trace: ${e.getStackTrace().join('\n')}"
        error "${errorMessage}: ${e.message}"
    }
    // Check the exit code and handle success/failure
    if (exitCode != 0) {
        catchError(stageResult: 'FAILURE') {
            error("${stageName} Unity batch mode failed with exit code: ${exitCode}")
        }
    } else {
        echo "${stageName} completed successfully."
    }
}

/**
 * Runs Unity in batch mode with the specified parameters and returns the exit code.
 *
 * @param unityExecutable The path to the Unity executable.
 * @param projectDirectory The directory containing the Unity project.
 * @param reportDirectory The directory where test reports and logs will be stored.
 * @param stageName The name of the stage to execute (e.g., "EditMode", "PlayMode").
 * @return int The exit code of the Unity batch mode process.
 */
 // groovylint-disable-next-line MethodSize
int runUnityBatchMode(String unityExecutable, String projectDirectory, String reportDirectory, String stageName) {
    String batchModeBaseCommand = '' // Base command for Unity batch mode in any stage
    String logFilePath = '' // Path to the log file
    String logFileUrl = '' // Jenkins URL to access the log file
    String testRunArgs = '' // Arguments for running tests (Edit/PlayMode only)
    String codeCoverageArgs = '' // Arguments for code coverage (EditMode, PlayMode, and Coverage Stage only)
    String additionalArgs = '' // Additional arguments for specific stages(initial and WebGl Build Stage)
    String finalCommand = '' // Final command to execute
    String logMessage = '' // Log message to display including logFileUrl

    /**
     * Sets the log file path and URL based on the stage and CI pipeline configuration.
     *
     * @param prBranch The pull request branch name.
     * @param reportDir The directory where reports are stored.
     * @param stage The stage name (e.g., "EditMode", "PlayMode").
     */
    Closure setLogFilePathAndUrl = { String prBranch, String reportDir, String stage ->
    // groovylint-disable-next-line DuplicateStringLiteral
        String jobName = CI_PIPELINE == 'true' ? 'PRJob' : 'DeploymentJob'

        Map logConfig = [
            (EDIT_MODE): [
                path: "${reportDir}/test_results/${stage}-tests.log",
                url: "${env.BUILD_URL}execution/node/3/ws/${jobName}/${prBranch}/test_results/${stage}-tests.log"
            ],
            (PLAY_MODE): [
                path: "${reportDir}/test_results/${stage}-tests.log",
                url: "${env.BUILD_URL}execution/node/3/ws/${jobName}/${prBranch}/test_results/${stage}-tests.log"
            ],
            (COVERAGE): [
                path: "${reportDir}/coverage_results/coverage_report.log",
                url: "${env.BUILD_URL}execution/node/3/ws/${jobName}/${prBranch}/coverage_results/coverage_report.log"
            ],
            (WEBGL): [
                path: "${reportDir}/build_project_results/build_project.log",
                url: "${env.BUILD_URL}execution/node/3/ws/${jobName}/${prBranch}/build_project_results/build_project.log"
           ],
            (RIDER): [
                path: "${reportDir}/batchmode_results/batch_mode_execution.log",
                url: "${env.BUILD_URL}execution/node/3/ws/${jobName}/${prBranch}/batchmode_results/batch_mode_execution.log"
            ]
        ]

        if (!logConfig.containsKey(stage)) {
            throw new IllegalArgumentException("""
            Invalid stageName: ${stage}.
            Valid options are '${EDIT_MODE}', '${PLAY_MODE}', '${COVERAGE}', '${WEBGL}', or '${RIDER}'.
            """.stripIndent())
        }
        logFilePath = logConfig[stage].path
        logFileUrl = logConfig[stage].url
    }

    /**
     * Generates arguments for running Unity tests (EditMode and PlayMode only).
     *
     * @param reportDir The directory where test reports are stored.
     * @param stage The stage name (e.g., "EditMode", "PlayMode").
     * @return String The arguments for running Unity tests.
     */
    Closure<String> getTestRunArgs = { String reportDir, String stage ->
        return "-testPlatform ${stage} \
        -runTests \
        -testResults ${reportDir}/test_results/${stage}-results.xml"
    }

    /**
     * Generates additional arguments for specific stages ("Webgl", "Rider" only).
     *
     * @param stage The stage name (e.g., "Webgl", "Rider").
     * @return String The additional arguments for the specified stage.
     */
    Closure<String> getAdditionalArgs = { String stage ->
        Map argsMap = [
            Webgl: '-buildTarget WebGL -executeMethod Builder.BuildWebGL',
            Rider: '-executeMethod Packages.Rider.Editor.RiderScriptEditor.SyncSolution'
        ]
        return argsMap[stage] ?: ''
    }

    // Set log file path and URL for any stages
    setLogFilePathAndUrl(PR_BRANCH, reportDirectory, stageName)

    // Build the base command for Unity batch mode
    batchModeBaseCommand = "${unityExecutable} \
        -projectPath ${projectDirectory} \
        -batchmode \
        -logFile ${logFilePath}"

    // Generate test run arguments
    testRunArgs = [EDIT_MODE, PLAY_MODE].contains(stageName) ? getTestRunArgs(reportDirectory, stageName) : ''

    // Generate code coverage arguments
    codeCoverageArgs = [EDIT_MODE, PLAY_MODE, COVERAGE].contains(stageName) ?
    getCodeCoverageArguments(projectDirectory, reportDirectory, stageName) : ''

    // Generate additional arguments for WebGL build and Synchronizing Unity and Rider
    additionalArgs = [WEBGL, RIDER].contains(stageName) ? getAdditionalArgs(stageName) : ''

    // Build the final command based on the stage
    finalCommand = batchModeBaseCommand
    if ([EDIT_MODE, PLAY_MODE].contains(stageName)) {
        finalCommand += " ${testRunArgs} ${codeCoverageArgs}"
    } else if (stageName == COVERAGE) {
        finalCommand += " ${codeCoverageArgs}"
    } else if ([WEBGL, RIDER].contains(stageName)) {
        finalCommand += " ${additionalArgs}"
    }

    // Add graphics and quit options
    finalCommand = (stageName != WEBGL && stageName != PLAY_MODE)
        ? (finalCommand + ' -nographics')
        : ('/usr/bin/xvfb-run -a ' + finalCommand)
    // Caution: if -quit is used for EditMode and PlayMode, it would not generate OpenCov files
    finalCommand += (stageName != PLAY_MODE && stageName != EDIT_MODE) ? ' -quit' : ''

    // Execute the final command and capture the exit code
    int exitCode = sh(script: "${finalCommand}", returnStatus: true)

    logMessage = "${stageName} Unity Batch-Mode Log file link: ${logFileUrl}"

    echo logMessage

    return exitCode
}

/**
 * Generates code coverage arguments for Unity batch mode.
 *
 * @param projectDirectory The directory containing the Unity project.
 * @param reportDirectory The directory where test reports are stored.
 * @param stageName The name of the stage (e.g., "EditMode", "PlayMode").
 * @return String The arguments for enabling code coverage.
 */
String getCodeCoverageArguments(String projectDirectory, String reportDirectory, String stageName) {
    String coverageResultPath = "${reportDirectory}/coverage_results"
    String codeCoverageBaseArgs = "-enableCodeCoverage \
    -debugCodeOptimization \
    -coverageResultsPath ${coverageResultPath}"
    String coverageOptionsKeyAndValue = ''
    String assemblyFiltersValue = ''

    /**
     * Retrieves the name of the first .asmdef file in the Scripts directory.
     * For Code Coverage analysis, it is essential to reference the correct assembly that contains the scripts being tested.
     * @param projectDir The directory containing the Unity project.
     * @return String The name of the first .asmdef file without the extension.
     */
    Closure<String> fetAsmdefFileName = { String projectDir ->
        // Define the target directory: ${projectDir}/Assets/Scripts
        String targetDir = "${projectDir}/Assets/Scripts"
        // Create a FilePath object for the target directory
        FilePath scriptsDir = new FilePath(new File(targetDir))
        // List only .asmdef files in the target directory (non-recursive)
        FilePath[] files = scriptsDir.list('*.asmdef')
        // Check if any .asmdef files were found
        if (files.length > 0) {
            // Return the name of the first .asmdef file without the extension
            return files[0].getName().replace('.asmdef', '')
        }
        // Return an empty string if no .asmdef files are found
        return ''
    }

    assemblyFiltersValue = fetAsmdefFileName(projectDirectory)
    coverageOptionsKeyAndValue = fetCoverageOptionsKeyAndValue(assemblyFiltersValue, projectDirectory, stageName)

    return "${codeCoverageBaseArgs} ${coverageOptionsKeyAndValue}"
}

/**
 * Generates key-value pairs for code coverage options based on the stage and project settings.
 *
 * @param assemblyName The name of the assembly to filter.
 * @param projectDir The directory containing the Unity project.
 * @param stageName The name of the stage (e.g., "EditMode", "PlayMode", "Coverage").
 * @return String The formatted code coverage options.
 */
String fetCoverageOptionsKeyAndValue(String assemblyName, String projectDir, String stageName) {
    // Common options
    String assemblyFiltersOption = "assemblyFilters:${assemblyName}"
    String sourcePathsOption = "sourcePaths:${projectDir}"
    String pathFiltersOption = 'pathFilters:+Assets/Scripts/**'

    if ([EDIT_MODE, PLAY_MODE].contains(stageName)) {
        // Load PathsToExclude from Unity's Code Coverage settings JSON file
        String pathsToExclude = loadPathsToExclude(projectDir)
        return buildCoverageOptions(assemblyFiltersOption, sourcePathsOption, pathFiltersOption, pathsToExclude)
    } else if (stageName == COVERAGE) {
        // For Coverage stage, generate HTML and badge reports
        return "-coverageOptions \"${assemblyFiltersOption};generateHtmlReport;generateBadgeReport\""
    }
    return ''
}

/**
 * Loads the PathsToExclude value from Unity's Code Coverage settings JSON file.
 *
 * @param projectDir The directory containing the Unity project.
 * @return String The formatted PathsToExclude value, or an empty string if not found.
 */
String loadPathsToExclude(String projectDir) {
    String codeCoverageSettingsFilePath = "${projectDir}/ProjectSettings/Packages/com.unity.testtools.codecoverage/Settings.json"
    try {
        JsonSlurper jsonSlurper = new JsonSlurper()
        String codeCoverageSettingsContent = new File(codeCoverageSettingsFilePath).text
        echo "codeCoverageSettingsContent: ${codeCoverageSettingsContent}"

        Map codeCoverageSettingsJsonObject = (Map) jsonSlurper.parseText(codeCoverageSettingsContent)
        List<Map> codeCoverageSettingItem = (List<Map>) (codeCoverageSettingsJsonObject.m_Dictionary?.m_DictionaryValues ?: [])
        Map pathsToExcludeItem = codeCoverageSettingItem.find { it.key == 'PathsToExclude' }

        if (pathsToExcludeItem != null) {
            Map pathsToExcludeValueObject = jsonSlurper.parseText(pathsToExcludeItem.value)
            String pathsToExcludeRawValue = pathsToExcludeValueObject.m_Value?.trim() ?: ''
            return pathsToExcludeRawValue.replace('{ProjectPath}/', '-')
        }
    } catch (Exception e) {
        error "Error parsing JSON file: ${e.message}"
    }
    return ''
}

/**
 * Builds the final coverage options string based on the provided parameters.
 *
 * @param assemblyFiltersOption The assembly filters option.
 * @param sourcePathsOption The source paths option.
 * @param pathFiltersOption The path filters option.
 * @param pathsToExclude The paths to exclude option.
 * @return String The formatted coverage options string.
 */
String buildCoverageOptions(String assemblyFiltersOption, String sourcePathsOption, String pathFiltersOption, String pathsToExclude) {
    return pathsToExclude ?
        "-coverageOptions \"${assemblyFiltersOption};${sourcePathsOption};${pathFiltersOption},${pathsToExclude}\"" :
        "-coverageOptions \"${assemblyFiltersOption};${sourcePathsOption};${pathFiltersOption}\""
}

return this
