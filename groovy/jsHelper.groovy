import groovy.json.JsonSlurper

/**
 * Identifies subdirectories within a project folder that contain a `package.json` file.
 * This function is typically used to find directories that require testing or dependency installation.
 * 
 * @param projectFolder The path to the root project folder to scan for subdirectories.
 * @return A comma-separated string of absolute paths to the subdirectories containing a `package.json` file.
 */
def findTestingDirs(projectFolder){
    def directoriesToTest = []
    def projectDir = new File(projectFolder)

    // Check if the subdirectories have pacakge.json file
    def subDirs = projectDir.listFiles().findAll { it.isDirectory() && new File(it, 'package.json').exists() }

    if(subDirs){
        for(def dir:subDirs){
            echo "dir: ${dir}"
            directoriesToTest.add(dir.absolutePath)
        }
    }else{
        echo "No subDirs are available"  
    }
    return directoriesToTest.join(',')
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
            echo "Audit Report Content:"
            echo reportFile.text

            // Call the Python script to process the audit report
            String pythonCommand = "python '${WORKSPACE}/python/npm_audit.py' '${env.COMMIT_HASH}' '${dirPath}/audit-report.json'"
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
        echo "Testing directories don't exist."
        return
    }

    // Split testingDirs into a list of directories
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
 * This function handles the commands for the appropriate environment being Windows, or Linux.
 * 
 * The versions and configuration are echoed to the console for verification.
 */
def checkNodeVersion(){
    // Cross-platform handling for Node and NPM checks
    try {
        if (isUnix()) {
            def nodeVersion = sh(script: 'node -v', returnStdout: true).trim()
            def npmVersion = sh(script: 'npm -v', returnStdout: true).trim()
            def npmConfig = sh(script: 'npm config ls', returnStdout: true).trim()

            echo "Node.js version: ${nodeVersion}"
            echo "NPM version: ${npmVersion}"
            echo "NPM config: ${npmConfig}"
        } else {
            //Windows must use bat otherwise issues are caused when calling node and npm
            def nodeVersion = bat(script: 'node -v', returnStdout: true).trim()
            def npmVersion = bat(script: 'npm -v', returnStdout: true).trim()
            def npmConfig = bat(script: 'npm config ls', returnStdout: true).trim()

            echo "Node.js version: ${nodeVersion}"
            echo "NPM version: ${npmVersion}"
            echo "NPM config: ${npmConfig}"
        }
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
def runCommandReturnStatus(command, String workingDir = ".") {
    if (isUnix()) {
        return sh(script: "cd \"${workingDir}\" && ${command}", returnStatus: true)
    } else {
        //Windows must use bat otherwise issues are caused when calling node and npm
        return bat(script: command, returnStatus: true)
    }
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
        echo "Testing directories don't exist."
        return
    }

    // Split testingDirs into a list of directories
    List<String> testDirs = testingDirs.split(',') as List<String>

    for (String dirPath : testDirs) {
        // Extract the directory name for logging
        String dirName = dirPath.split('\\\\')[-1]
        echo "Currently working on ${dirName} directory."

        // Error handling for lint command execution
        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
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



return this