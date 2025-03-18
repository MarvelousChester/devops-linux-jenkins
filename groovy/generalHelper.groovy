/**
 * Clones or updates a Git repository in the specified directory.
 * If the project directory does not exist or does not contain the expected project type,
 * the repository is cloned from scratch. Otherwise, the latest changes are fetched.
 *
 * @param projectType The type of project to check for (used to locate the directory).
 * @param workingDir  The root directory where the project is located.
 * @param projectDir  The full path to the project directory.
 * @param repoSsh     The SSH URL of the repository to clone or update.
 * @param branch      The branch to check out and pull updates from.
 *
 * @throws MissingPropertyException If required parameters are missing.
 * @throws Exception If an invalid git repository exists or cleanup fails.
 */
void cloneOrUpdateRepo(String projectType, String workingDir, String projectDir, String repoSsh, String branch) {
    if (!projectDir || !repoSsh || !branch) {
        error 'Missing required parameters for cloneOrUpdateRepo()'
    }

    echo 'Checking if the project directory exists...'
    int projectExists = sh(script: "/usr/bin/find \"${workingDir}\" -type d -name ${projectType}", returnStatus: true)
    echo "Project directory: ${projectDir}"
    echo "Project exists: ${projectExists}"

    if (projectExists != 0) {
        echo 'Cloning repository...'
        sh "git clone ${repoSsh} ${projectDir}"
    } else {
        def isGitRepo = fileExists("${projectDir}/.git")
        echo "isGitRepo: ${isGitRepo}"
        if (isGitRepo) {
            echo 'Project already exists. Fetching latest changes...'
            dir(projectDir) {
                /* groovylint-disable DuplicateStringLiteral */
                echo 'Current branch before checkout:'
                sh 'git branch --show-current'

                // Remove lock file if it exists
                sh 'rm -f .git/index.lock'
                sh 'git fetch origin'

                // Check if the branch exists and check it out
                checkoutBranch(projectDir, branch)

                echo 'Current branch after checkout:'
                sh 'git branch --show-current'

                sh 'git pull'
                /* groovylint-enable DuplicateStringLiteral */
            }
        } else {
            echo 'Invalid git repository. Cleaning up and cloning a fresh...'
            try {
                def output = sh(script: "rm -rf ${projectDir}/*", returnStdout: true)
                echo "Command output: ${output}"
            } catch (Exception e) {
                echo "Error: ${e.message}"
                error("Failed to execute rm -rf ${projectDir}")
            }
            sh "git clone ${repoSsh} ${projectDir}"
        }
    }
}

/**
 * This function will identify the default branch of a repository, such as main or master.
 *
 * @return string The name of the default branch
 */
String getDefaultBranch() {
    // Use git remote show to get the default branch
    def defaultBranch = sh(script: """git remote show origin | \\
        grep 'HEAD branch' | \\
        awk '{print \$NF}'""",
        returnStdout: true).trim()
    if (!defaultBranch) {
        error 'Failed to determine the default branch from the remote repository.'
    }
    echo "Default branch is determined to be '${defaultBranch}'."
    return defaultBranch
}

/**
 * This function will notify bitbucket that the pipeline is in progress, as well as set up some environment variables.
 *
 * @param workspace The folder containing the Jenkins and Project files
 * @param commitHash The hash for the commit that triggered the pipeline
 * @param prBranch The branch name that triggered the pipeline
 */
void initializeEnvironment(String workspace, String commitHash, String prBranch) {
    echo "Sending 'In Progress' status to Bitbucket..."
    sendBuildStatus(workspace, 'INPROGRESS', commitHash)
    env.TICKET_NUMBER = parseTicketNumber(prBranch)
    env.FOLDER_NAME = "${JOB_NAME}".split('/').first()
}

/**
 * This function is used to encapsulate the git action of checking out a branch into a reusable function
 *
 * @param projectDir The folder containing project files
 * @param targetBranch The branch to check out
 */
void checkoutBranch(String projectDir, String targetBranch) {
    dir(projectDir) {
        echo "Checking out branch ${targetBranch}..."
        boolean branchExists = sh(script: """git show-ref --verify --quiet refs/heads/${targetBranch} || \\
            git show-ref --verify --quiet refs/remotes/origin/${targetBranch}""",
            returnStatus: true) == 0
        if (!branchExists) {
            error "Branch ${targetBranch} does not exist locally or remotely."
        }
        // Clean up added and modified from the local branch before checkout
        sh 'git reset --hard'
        // Clean up all untracked files before checkout
        sh 'git clean -fd'
        // checkout to the target branch
        sh "git checkout ${targetBranch}"
        // Synchronize the target remote branch and local branch
        sh "git reset --hard origin/${targetBranch}"
    }
}

/**
 * This function encapsulates the git action of merging the default branch into the PR branch
 * if it is determined the branch is not up to date.
 */
void mergeBranchIfNeeded() {
    def destinationBranch = getDefaultBranch()
    try {
        echo 'Fetching latest changes from origin...'
        // groovylint-disable-next-line DuplicateStringLiteral
        sh 'git fetch origin'

        // Check if the destination branch exists remotely
        def branchExists = sh(script: "git show-ref --verify --quiet refs/remotes/origin/${destinationBranch}",
        returnStatus: true) == 0

        if (!branchExists) {
            error "Branch ${destinationBranch} does not exist in the remote repository."
        }

        // Check if the branch is up-to-date
        echo "Checking if branch is up-to-date with ${destinationBranch}..."
        if (isBranchUpToDateWithMain(destinationBranch)) {
            echo "Branch is up-to-date with ${destinationBranch}."
            return
        }

        echo "Branch is not up-to-date. Attempting to merge ${destinationBranch}..."
        if (tryMerge(destinationBranch)) {
            echo 'Merge completed successfully.'
        } else {
            echo 'Merge conflicts detected. Aborting the merge.'
            sh 'git merge --abort || true' // Safely abort merge if one is in progress
            error('Merge process failed.')
        }
    } catch (Exception e) {
        echo "An error occurred during the merge process: ${e.getMessage()}"
        // groovylint-disable-next-line DuplicateStringLiteral
        error('Merge process failed.')
    }
}

/**
 * Checks if the current local branch has new commits compared to its remote counterpart.
 *
 * @param branch The branch to check (e.g., pr-branch).
 * @return true if the local branch is up-to-date with the remote branch, false otherwise.
 */
boolean isBranchUpToDateWithRemote(String branch) {
    return sh(
        script: "git fetch origin ${branch} && [ \$(git rev-parse HEAD) = \$(git rev-parse origin/${branch}) ]",
        returnStatus: true
    ) == 0
}

/**
 * This function encapsulates the git action of checking if the current branch is up to date with main
 *
 * @return true if the local branch is up-to-date with the main branch, false otherwise.
 */
boolean isBranchUpToDateWithMain(String destinationBranch) {
    return sh(script: "git merge-base --is-ancestor origin/${destinationBranch} @", returnStatus: true) == 0
}

/**
 * This function encapsulates the git action of checking of merging the destination branch to the local branch
 *
 * @param destinationBranch The target branch to merge into the local branch.
 * @return true if the merge is successful, false otherwise.
 */
boolean tryMerge(String destinationBranch) {
    echo "Attempting to merge origin/${destinationBranch}..."
    return sh(script: "git merge origin/${destinationBranch}", returnStatus: true) == 0
}

/**
 * This function is used to retrieve the full commit hash, this is currently done by calling a python script
 * which retrieves it from bitbucket via web request.
 *
 * @param workspace The local workspace which contains our jenkins files and project files.
 * @param shortCommit The short commit hash.
 * @return string The long commit hash.
 */
String getFullCommitHash(String workspace, String shortCommit) {
    def fullHash = sh(script: "python '${workspace}/python/get_bitbucket_commit_hash.py' ${shortCommit}",
    returnStdout: true).trim()

    if (!fullHash) {
        error "Failed to retrieve the full commit hash for ${shortCommit}."
    }
    return fullHash
}

/**
 * This function is used to retrieve the short commit hash via git.
 *
 * @return string The short commit hash.
 */
String getCurrentCommitHash() {
    return sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
}

/**
 * This function is used to send the build status back to the bitbucket REST API, This is achieved by calling a
 * helper python script to handle the web request.
 *
 * @param workspace The path to the workspace where the Python script is located.
 * @param state The build status to send (e.g., INPROGRESS, SUCCESSFUL, FAILED).
 * @param commitHash The commit hash associated with the build.
 * @param deployment (Optional) A flag indicating whether the build is a deployment build (default: false).
 */
void sendBuildStatus(String workspace, String state, String commitHash, Boolean deployment = false) {
    try {
        // Construct the Python command as a string
        String pythonCommand = "python '${workspace}/python/send_bitbucket_build_status.py' '${commitHash}' '${state}'"

        // Append deployment flag if deployment is true
        if (deployment) {
            pythonCommand += ' -d'
        }
        echo "Executing build status update: ${pythonCommand}"

        def exitCode = sh(script: pythonCommand, returnStatus: true)

        if (exitCode != 0) {
            echo "Build status update script failed with exit code: ${exitCode}."
        }
    } catch (Exception e) {
        echo "An error occurred while updating the build status: ${e.getMessage()}"
    }
}

/**
 * Extracts a ticket number from a branch name using a predefined pattern.
 * The pattern matches strings in the format "ABC-123" (letters followed by a hyphen and digits).
 *
 * @param String branchName The name of the branch from which to extract the ticket number.
 * @return String The extracted ticket number if a match is found, or null if no match is found.
 */
String parseTicketNumber(String branchName) {
    def patternMatches = branchName =~ /[A-Za-z]+-[0-9]+/

    if (patternMatches) {
        // patternMatches[0] is "ABC-123" if branchName is "feature/ABC-123"
        return patternMatches[0]
    }
    return null
}

/**
 * Publishes test result HTML reports to a remote web server.
 * The function creates the necessary directories on the server, adjusts permissions,
 * and uploads the reports using SSH and SCP.
 *
 * @param remoteProjectFolderName The name of the remote project folder on the web server.
 * @param ticketNumber The identifier for the ticket associated with the test results.
 * @param reportDir The local directory containing the HTML test result reports to be uploaded.
 * @param reportType The type of report being published (e.g. CodeCoverage).
 * @param buildNumber Optional. The build number associated with the test results. If provided,
 *                    the reports will be placed under a subdirectory for that build.
 */
void publishTestResultsHtmlToWebServer(
    String remoteProjectFolderName,
    String ticketNumber,
    String reportDir,
    String reportType,
    String buildNumber = null) {
    echo 'Attempting to publish results to web server'
    // groovylint-disable-next-line LineLength
    def destinationDir = buildNumber ? "/var/www/html/${remoteProjectFolderName}/Reports/${ticketNumber}/Build-${buildNumber}/${reportType}-report" : "/var/www/html/${remoteProjectFolderName}/Reports/${ticketNumber}/${reportType}-report"

    sh """ssh -i ${env.SSH_KEY} ${env.DLX_WEB_HOST_URL} \
    \"mkdir -p ${destinationDir} \
    && sudo chown vconadmin:vconadmin ${destinationDir} \
    && sudo chmod 755 /var/www/html/${remoteProjectFolderName} \
    && sudo chmod -R 755 /var/www/html/${remoteProjectFolderName}/Reports \""""

    sh "scp -i ${env.SSH_KEY} -rp ${reportDir}/* ${env.DLX_WEB_HOST_URL}:${destinationDir}"
}

/**
 * Publishes WebGL build and build_project.log to a remote web server.
 * The function creates the necessary directories on the server, adjusts permissions,
 * and uploads the reports using SSH and SCP.
 *
 * @param remoteProjectFolderName : The name of the remote project folder on the web server.
 * @param ticketNumber            : The identifier for the ticket associated with the test results.
 * @param webglDir                : The local directory containing the webgl builds to be uploaded.
 * @param buildLogDir             : The local directory containing the webgl build result log file to be uploaded.
 */
void publishBuildResultsToWebServer(remoteProjectFolderName, ticketNumber = null, webglDir, buildLogDir) {
    echo 'Attempting to publish WebGL build and log file to web server...'
    String destinationDir = "/var/www/html/${remoteProjectFolderName}/PR-Builds/${ticketNumber}"

    sh """ssh -i ${env.SSH_KEY} ${env.DLX_WEB_HOST_URL} \
    \"mkdir -p ${destinationDir}/Build-Log \
    && chown vconadmin:vconadmin ${destinationDir} \
    && chmod 755 /var/www/html/${remoteProjectFolderName} \
    && chmod -R 755 /var/www/html/${remoteProjectFolderName}/PR-Builds \""""

    try {
        echo 'Copying WebGL build to web server...'
        sh "scp -i ${env.SSH_KEY} -rp ${webglDir}/* ${env.DLX_WEB_HOST_URL}:'${destinationDir}'"

        echo 'Copying WebGL log to web server...'
        sh "scp -i ${env.SSH_KEY} -rp ${buildLogDir}/*.log ${env.DLX_WEB_HOST_URL}:'${destinationDir}/Build-Log'"

        echo 'Files copied successfully.'
    } catch (Exception e) {
        echo "ERROR: Failed to copy files to web server: ${e.getMessage()}"
        error('File copy step failed - please check logs.')
    }
}

/**
 * Deletes the WebGL build for a merged branch from the remote web server.
 * This function removes the directory associated with the branch's ticket number
 * to clean up after a successful merge.
 *
 * @param remoteProjectFolderName The name of the remote project folder on the web server.
 * @param ticketNumber The identifier for the ticket associated with the branch whose reports need to be removed.
 */
void cleanMergedBranchFromWebServer(remoteProjectFolderName, ticketNumber) {
    sh """ssh -i ${env.SSH_KEY} ${env.DLX_WEB_HOST_URL} \
    \"rm -r -f /var/www/html/${remoteProjectFolderName}/PR-Builds/${ticketNumber}\""""
}

/**
 * Cleans up directories associated with a pull request branch.
 * This function searches for directories matching the branch name, deletes them,
 * and also removes any associated temporary directories (`@tmp`) if they exist.
 *
 * @param String prBranch The name of the pull request branch to clean up.
 */
void cleanUpPRBranch(String prBranch) {
    // Find the path of 'find' directory searching tool
    def findPath = sh(script: 'command -v find', returnStdout: true).trim()
    if (!findPath) {
        echo "'find' directory searching tool is not found..."
        echo "Installing 'find' directory searching tool..."
        // install 'find' Linux searching tool
        int installStatus = sh(script: 'sudo apt-get update && sudo apt-get install -y findutils', returnStatus: true)
        if (installStatus == 0 ) {
            echo "The 'findutils' package was installed successfully."
        } else {
            echo "Failed to install 'findutils'. Exit code: ${installStatus}"
            error "Installation failed with exit code: ${installStatus}"
        }
    }

    // Find the branch path
    def branchPaths = sh(script: "${findPath} ../ -type d -name \"${prBranch}\"", returnStdout: true).trim()
    // groovylint-disable-next-line InvertedIfElse
    if (!branchPaths.isEmpty()) {
        // Split paths into an array
        def paths = branchPaths.split('\n')

        paths.each { branchPath ->
            // Print the path of the branch directory being deleted
            echo "Deleting Branch Path: ${branchPath}"

            // Safely delete the branch path
            sh(script: "rm -r -f \"${branchPath}\"", returnStatus: true)

            // Safely delete the @tmp directory if it exists
            def tmpPath = "${branchPath}@tmp"
            if (sh(script: "test -d \"${tmpPath}\"", returnStatus: true) == 0) {
                echo "Deleting temporary path: ${tmpPath}"
                sh(script: "rm -r -f \"${tmpPath}\"", returnStatus: true)
            } else {
                echo "Temporary path not found: ${tmpPath}"
            }
        }
    } else {
        echo "No branch path found for ${prBranch}. Nothing to delete."
    }
}

/**
 * Closes open log files in a specified directory by terminating associated processes.
 * This function identifies processes holding open files in the given directory,
 * extracts their PIDs, and forcefully terminates those processes if they still exist.
 *
 * @param String branchPath The path to the directory where log files are checked for open processes.
 */
void closeLogfiles(String branchPath) {
    try {
        // Check whether there are open files in the target directory
        def openFiles = sh(script: "lsof +D ${branchPath}", returnStdout: true).trim()

        // groovylint-disable-next-line InvertedIfElse
        if (!openFiles.isEmpty()) {
            echo "Open files found in the directory: ${branchPath}"
            echo 'List of opened files:'
            echo "${openFiles}"

            // Extract unique PIDs from the open files
            // groovylint-disable-next-line DuplicateStringLiteral
            def pids = openFiles.split('\n').findAll {
                it.contains('pid:') && it.contains("${prBranch}") // Filtering lines related to the target branch
            }.collect { line ->
                def match = line =~ /\b(\d+)\b/ // Regex to extract PIDs
                return match ? match[0][1] : null
            }.unique()

            // Print the PIDs
            echo "List of PIDs of open log files at the \"${prBranch}\": ${pids}"

            // Forcefully terminate the processes
            pids.each { pid ->
                if (pid) {
                    // Check whether the target PID still exists or not
                    echo 'Checking whether the target PID still exists or not...'
                    def pidExists = sh(script: "ps -p ${pid} -o pid=", returnStatus: true) == 0
                    if (pidExists) {
                        // Terminate the process using pid
                        echo "PID ${pid} exists. Proceeding to terminate."
                        sh(script: "kill -9 ${pid}")
                        echo "Terminated PID: ${pid}"
                    } else {
                        echo "PID ${pid} does not exist. Skipping termination."
                    }
                }
            }
        } else {
            echo "No open files found in the directory: ${branchPath}"
        }
    }
    catch (Exception e) {
        echo "Command failed with error: ${e.message}"
        if (e.message.contains('script returned exit code 1')) {
            // This part will be executed if there is no open file at the target PR directory
            echo "Exit code 1 detected. Likely 'No matching handles found'."
            echo "No open files found in the directory: ${branchPath} Proceeding."
        } else {
            throw e // Unexpected Error
        }
    }
}

/**
 * Publishes GroovyDoc HTML reports to a remote web server.
 * The function creates the necessary directories on the server, adjusts permissions,
 * uploads the reports using SSH and SCP, and then recursively updates permissions
 * on the remote folder.
 *
 * @param reportDir    The local directory containing the GroovyDoc HTML reports to be uploaded.
 */
void publishGroovyDocToWebServer(
    String reportDir) {

    echo 'Attempting to publish GroovyDoc reports to web server'

    def destinationDir = '/var/www/html/Jenkins/GroovyDoc'

    // Create the directory on the remote server and adjust its ownership
    sh """ssh -i ${env.SSH_KEY} ${env.DLX_WEB_HOST_URL} \\
    "mkdir -p ${destinationDir} && sudo chown vconadmin:vconadmin ${destinationDir}"
    """

    // Optionally, clean the destination directory if it already exists
    sh """ssh -i ${env.SSH_KEY} ${env.DLX_WEB_HOST_URL} \\
    "if [ -d ${destinationDir} ]; then sudo rm -rf ${destinationDir}/*; fi"
    """

    // Upload the reports via SCP
    sh "scp -i ${env.SSH_KEY} -rp ${reportDir}/* ${env.DLX_WEB_HOST_URL}:${destinationDir}"

    // Recursively set permissions on the remote folder root:
    // Directories: 755, Files: 644
    sh """ssh -i ${env.SSH_KEY} ${env.DLX_WEB_HOST_URL} \\
    "find /var/www/html/Jenkins/ -type d -exec sudo chmod 755 {} \\; && \\
     find /var/www/html/Jenkins/ -type f -exec sudo chmod 644 {} \\;" """
}

return this
