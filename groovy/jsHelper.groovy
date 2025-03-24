#!groovy
import static resource.ResultStatus.STAGE_STATUS
import static resource.ResultStatus.BUILD_STATUS

/**
 * A helper method for logging messages. If the script is running in a Jenkins pipeline,
 * it will use the 'echo' step; otherwise, it falls back to println.
 *
 * @param String message        The message to log/print
 */
void logMessage(String message) {
    try {
        // If running in Jenkins and 'echo' is available, use it
        echo message
    } catch (MissingMethodException e) {
        // Otherwise, fallback to standard output
        //groovylint-disable-next-line Println
        println message
    }
}

/**
 * Identifies subdirectories within a project folder that contain a `package.json` file.
 * This function is typically used to find directories that require testing or dependency installation.
 *
 * @param String projectFolder The path to the root project folder to scan for subdirectories.
 * @return String A comma-separated string of absolute paths to the subdirectories containing a `package.json` file.
 */
String findTestingDirs(String projectFolder) {
    def directoriesToTest = []
    def projectDir = new File(projectFolder)

    // Check if the subdirectories have pacakge.json file
    def subDirs = projectDir.listFiles().findAll { it.isDirectory() && new File(it, 'package.json').exists() }

    if (subDirs) {
        for (def dir:subDirs) {
            logMessage("Directory with package.json: ${dir}")
            directoriesToTest.add(dir.absolutePath)
        }
    } else {
        logMessage('No directories with package.json found')
    }
    return directoriesToTest.join(',')
}

/**
 * Helper function to reduce code reuse, finds the version number of the associated package.json
 *
 * @return String the found value for the version number withing the ppackage.json
 */
String getPackageJsonVersion() {
    ret = sh(
        script: "node -p \"require('./package.json').version\"",
        returnStdout: true
    ).trim()
    return ret
}

/**
 * Installs npm dependencies in the specified testing directories.
 * This function performs an npm audit before installation, processes the audit report using a Python script,
 * and then runs `npm install` to install dependencies.
 *
 * @param testingDirs A comma-separated string of directory paths where npm dependencies need to be installed.
 *                    If null or empty, the function will exit without performing any operations.
 */
void installNpmInTestingDirs(String testingDirs) {
    if (testingDirs == null || testingDirs.isEmpty()) {
        echo "Testing directories don't exist."
        return
    }
    //groovylint-disable-next-line DuplicateStringLiteral
    List<String> testDirs = testingDirs.split(',') as List<String>
    for (String dirPath : testDirs) {
        // Check if directory exists
        File dir = new File(dirPath)
        if (!dir.exists() || !dir.isDirectory()) {
            echo "Directory does not exist: ${dirPath}. Skipping..."
            continue
        }

        // Run npm audit before installing dependencies
        String npmAuditCommand = "cd '${dirPath}' && npm audit --json > audit-report.json"
        echo "Running command: ${npmAuditCommand}"
        int exitCode = runCommandReturnStatus(npmAuditCommand) // Run audit first
        if (exitCode != 0) {
            echo "npm audit failed in directory: ${dirPath} with exit code: ${exitCode}. Proceeding with caution."
        }

        // Check and read the audit report
        File reportFile = new File("${dirPath}/audit-report.json")
        if (reportFile.exists()) {
            echo 'Audit Report Content:'
            echo reportFile.text

            // Call the Python script to process the audit report
            String pythonCommand = """python '${WORKSPACE}/python/npm_audit.py'
            '${COMMIT_HASH}' '${dirPath}/audit-report.json'
            """
            echo "Executing Python script for audit analysis: ${pythonCommand}"
            exitCode = sh(script: pythonCommand, returnStatus: true)

            if (exitCode != 0) {
                echo "npm_audit.py script encountered an issue. Exit code: ${exitCode}"
            }
        } else {
            echo "Audit report not generated for path: ${dirPath}"
        }

        // Run npm install with error handling
        String npmCommand = "cd '${dirPath}' && npm install"
        echo "Running command: ${npmCommand}"
        exitCode = runCommandReturnStatus(npmCommand) // Run npm install
        if (exitCode != 0) {
            echo "npm install failed in directory: ${dirPath} with exit code: ${exitCode}. Skipping further operations."
            continue
        }
    }
}

/**
 * Runs unit tests in the specified testing directories using npm.
 * This function iterates over the provided directories, runs `npm run test` in each,
 * and handles errors gracefully. If `deploymentBuild` is true, the pipeline will abort
 * on test failures.
 *
 * @param testingDirs A comma-separated string of directory paths where unit tests should be executed.
 *                    If null or empty, the function will exit without performing any operations.
 * @param deploymentBuild A boolean flag indicating whether the pipeline should abort on test failure.
 *                        If true, the pipeline will halt when a test fails; otherwise, it will continue.
 */
void runUnitTestsInTestingDirs(String testingDirs, boolean deploymentBuild) {
    if (testingDirs == null || testingDirs.isEmpty()) {
        //groovylint-disable-next-line DuplicateStringLiteral
        echo "Testing directories don't exist."
        return
    }

    // Split testingDirs into a list of directories
    //groovylint-disable-next-line DuplicateStringLiteral
    List<String> testDirs = testingDirs.split(',') as List<String>

    for (String dirPath : testDirs) {
        // Check if the directory exists
        File dir = new File(dirPath)
        if (!dir.exists() || !dir.isDirectory()) {
            echo "Directory does not exist: ${dirPath}. Skipping..."
            continue
        }

        echo "Currently working on ${dirPath} directory."

        // Construct the test command
        String testCommand = "cd ${dirPath} && npm run test"
        echo "Running command: ${testCommand}"

        // Run the unit testing command and capture the exit code
        int exitCode = runCommandReturnStatus(testCommand) // Run Jest unit testing
        if (exitCode != 0) {
            echo "npx jest failed with exit code: ${exitCode}."
            if (deploymentBuild) {
                error("npx jest failed with exit code: ${exitCode}. Aborting the deployment pipeline...")
            }
            continue
        }
    }
}

/**
 * Checks the installed versions of Node.js and NPM, and retrieves the current NPM configuration.
 * This function handles the commands for Linux. since this repo is for Linux specifically.
 *
 * The versions and configuration are echoed to the console for verification.
 */
void checkNodeVersion() {
    try {
        def nodeVersion = sh(script: 'node -v', returnStdout: true).trim()
        def npmVersion = sh(script: 'npm -v', returnStdout: true).trim()
        def npmConfig = sh(script: 'npm config ls', returnStdout: true).trim()

        echo "Node.js version: ${nodeVersion}"
        echo "NPM version: ${npmVersion}"
        echo "NPM config: ${npmConfig}"
    } catch (Exception e) {
        error "An error occurred while checking Node.js and NPM versions: ${e.getMessage()}"
    }
}

/**
 * This is a helper function to run commands based on OS, bat for windows and sh for Linux
 * It will return the status of the command, such as 0 or 1.
 *
 * @param command This is the command to run
 * @param workingDir (Optional) This is the directory to run the command in (default: ".")
 * @return 0 for command success, 1 for command fail.
 */
int runCommandReturnStatus(command, String workingDir = '.') {
    if (isUnix()) {
        return sh(script: "cd \"${workingDir}\" && ${command}", returnStatus: true)
    }
    //Windows must use bat otherwise issues are caused when calling node and npm
    return bat(script: command, returnStatus: true)
}

/**
 * Executes linting in the specified testing directories using npm.
 * This function iterates through the given directories, runs `npm run lint` in each,
 * and handles errors gracefully. If `deploymentBuild` is true, the pipeline will abort
 * on linting failures.
 *
 * @param testingDirs A comma-separated string of directory paths where linting should be executed.
 *                    If null or empty, the function will exit without performing any operations.
 * @param deploymentBuild A boolean flag indicating whether the pipeline should abort on linting failure.
 *                        If true, the pipeline will halt when linting fails; otherwise, it will continue.
 */
void executeLintingInTestingDirs(String testingDirs, boolean deploymentBuild) {
    if (testingDirs == null || testingDirs.isEmpty()) {
        //groovylint-disable-next-line DuplicateStringLiteral
        echo "Testing directories don't exist."
        return
    }

    // Split testingDirs into a list of directories
    //groovylint-disable-next-line DuplicateStringLiteral
    List<String> testDirs = testingDirs.split(',') as List<String>

    for (String dirPath : testDirs) {
        // Extract the directory name for logging
        String dirName = dirPath.split('\\\\')[-1]
        echo "Currently working on ${dirName} directory."

        // Error handling for lint command execution
        catchError(buildResult: BUILD_STATUS.SUCCESS, stageResult: STAGE_STATUS.FAILURE) {
            // Construct the lint command
            String lintCommand = "cd ${dirPath} && npm run lint"
            int exitCode = sh(script: lintCommand, returnStatus: true)

            // Handle linting results
            if (exitCode == 0) {
                echo "Linting completed successfully for \"${dirPath}\""
            } else {
                echo "Linting failed with exit code ${exitCode}."
                if (deploymentBuild) {
                    error("Linting failed with exit code ${exitCode}. Aborting the deployment pipeline...")
                }
            }
        }
    }
}

/**
 * <strong>Overview</strong>
 * <br> Compares two version strings and determines if the current project version is up-to-date
 * with the deployed ACR (Azure Container Registry) version.
 * <br> This function is commonly used to verify version consistency between the deployment
 * pipeline and the project build process.
 *
 * <br><strong>Function Description</strong>
 * <br>- This function compares the deployed version (`azContainerVersion`) from ACR with
 * the current project version (`projectVersion`).
 * <br>- Versions are expected to follow the semantic versioning format (e.g., 1.0.0, 2.1.3).
 * <br>- Each version string is split into major, minor, and patch components and compared numerically.
 *
 * <br><strong>Comparison Logic</strong>
 * <br>- The version strings are tokenized using the dot (`.`) delimiter.
 * <br>- Each token is validated to ensure it represents a positive integer.
 * <br>- Missing version segments are treated as `0` for comparison purposes (e.g., `1.0` is treated as `1.0.0`).
 * <br>- If the current deployed version is newer, the function returns `true`.
 * <br>- If the project version is newer or versions are identical, it returns `false`.
 *
 * @param String azContainerVersion : The deployed container version from Azure Container Registry (ACR).
 * @param String projectVersion     : The current project version to be compared.
 *
 * @return boolean
 * <br>&nbsp;&nbsp;&nbsp;&nbsp; - <strong>true</strong>  : If the deployed ACR version is newer than the project version.
 * <br>&nbsp;&nbsp;&nbsp;&nbsp; - <strong>false</strong> : If the project version is up-to-date or newer than the deployed version.
 */
boolean versionCompare(String azContainerVersion, String projectVersion) {
    logMessage('versionCompare() executed')
    // Closure Function
    // : Check whether the string can be converted to an positive integer
    Closure<Boolean> isInteger = { String value ->
        value ==~ /^\d+$/  // Regex for integer validation (Only allow one or more positive int)
    }

    // Closure Function
    // : Split the version string by '.' and convert to int array
    Closure<int[]> parseVersion = { String version ->
        // Split the version string based on .
        def parts = version.tokenize('.')

        // Check if all elements of a list are integers
        if (parts.every { isInteger(it) }) {
            // Transfer string to int and return as an int[]
            return parts.collect { it as int } as int[]  // Convert to int[]
    }
        // with return in if throw error if get to end
        error "Invalid version format: '${version}'. All parts must be integers."
}

    logMessage("Latest Version on ACR: ${azContainerVersion}")
    logMessage("Project Version      : ${projectVersion}")

    // Parse the version strings into int arrays
    int[] currentParts = parseVersion(azContainerVersion)
    int[] newParts = parseVersion(projectVersion)

    // Compare Major.Minor.Patch versions
    for (int i = 0; i < Math.max(currentParts.length, newParts.length); i++) {
        // Handle missing version parts by treating them as 0
        def v1 = (i < currentParts.length) ? currentParts[i] : 0
        def v2 = (i < newParts.length) ? newParts[i] : 0

        if (v1 < v2) {
            return true  // Project version is newer
        } else if (v1 > v2) {
            return false // Project version is out-dated
        }
    // Compare the next index if the numbers are the same
    }
    return false  // Versions are identical
}

/**
 * **Overview**
 * This function searches for the file directories of `coverage-summary.json` and `test-results.json`.
 *
 * @param projectDir              Base directory of the project (server or client).
 * @param coverageSummaryFileName Expected to be `"coverage-summary.json"`.
 * @param testSummaryFileName     Expected to be `"test-results.json"`.
 *
 * @return Map
 * <br>&nbsp;&nbsp;&nbsp;&nbsp; - <strong> Failrue </strong> : Returns an empty Map if one or both files are missing.
 * <br>&nbsp;&nbsp;&nbsp;&nbsp; - <strong> Success </strong> : Returns a Map containing the absolute paths of both
 * the coverage and test result files.
 *
 **/
Map retrieveReportSummaryDirs(String projectDir, String coverageSummaryFileName, String testSummaryFileName) {
    logMessage("Trying to find paths of ${coverageSummaryFileName} and ${testSummaryFileName} in the ${projectDir} base directory...")

    // find the path of "coverage-summary.json"
    String coverageSummaryDir = sh(script: "find '${projectDir}' -type f -name '${coverageSummaryFileName}'", returnStdout: true).trim()
    if (!coverageSummaryDir) {
        logMessage("Failed to find coverage summary file: '${coverageSummaryFileName}' in '${projectDir}'")
    }

    // find the path of "test-results.json"
    String testSummaryDir = sh(script: "find '${projectDir}' -type f -name '${testSummaryFileName}'", returnStdout: true).trim()
    if (!testSummaryDir) {
        logMessage("Failed to find test summary file: '${testSummaryFileName}' in '${projectDir}'")
    }

    // Return empty map if either file is not found, otherwise return the map with file paths
    return (!coverageSummaryDir || !testSummaryDir)
           ? [:]
           : [coverageSummaryDir: coverageSummaryDir, testSummaryDir: testSummaryDir]
}

return this
