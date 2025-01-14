import groovy.json.JsonSlurper

// Checks the subdirectories 1 level under the project root folder, to find the correct testing directories and  store the folder paths in env.  
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

// This function will change directory to testing directories to install dependencies.
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
        int exitCode = runCommand(npmAuditCommand) // Run audit first
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
        exitCode = runCommand(npmCommand) // Run npm install
        if (exitCode != 0) {
            echo "npm install failed in directory: ${dirPath} with exit code: ${exitCode}. Skipping further operations."
            continue
        }
    }
}

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
        int exitCode = runCommand(testCommand) // Run Jest unit testing
        if (exitCode != 0) {
            echo "npx jest failed with exit code: ${exitCode}."
            if (deploymentBuild) {
                error("npx jest failed with exit code: ${exitCode}. Aborting the deployment pipeline...")
            }
            continue
        }
    }
}

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
            def nodeVersion = sh(script: 'node -v', returnStdout: true).trim()
            def npmVersion = sh(script: 'npm -v', returnStdout: true).trim()
            def npmConfig = sh(script: 'npm config ls', returnStdout: true).trim()

            echo "Node.js version: ${nodeVersion}"
            echo "NPM version: ${npmVersion}"
            echo "NPM config: ${npmConfig}"
        }
    } catch (Exception e) {
        error "An error occurred while checking Node.js and NPM versions: ${e.getMessage()}"
    }
}

// Helper function to run commands with platform-specific handling.
def runCommand(command, String workingDir = ".") {
    if (isWindows()) {
        return sh(script: command, returnStatus: true)
    } else {
        return sh(script: "cd ${workingDir} && ${command}", returnStatus: true)
    }
}

// Will return true if running on windows.
def isWindows() {
    return System.properties['os.name'].toLowerCase().contains('windows')
}

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