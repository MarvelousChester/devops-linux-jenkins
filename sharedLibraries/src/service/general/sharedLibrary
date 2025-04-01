def loadSharedLibrary() {
    try {
        def LIBRARY_PATH = 'sharedLibraries'
        echo "Loading local shared library from ${WORKSPACE}/${LIBRARY_PATH}"

        // Create temporary Git repository
        sh """
            cd ${WORKSPACE}/${LIBRARY_PATH} && \
            (rm -rf .git || true) && \
            git init && \
            git add --all && \
            git commit -m 'init'
        """

        // Get the path of the temporary Git repository
        def repoPath = sh(returnStdout: true, script: 'pwd').trim() + "/${LIBRARY_PATH}"

        // Shared Library Registration
        sharedLib = library identifier: 'local-lib@master',
                retriever: modernSCM([$class: 'GitSCMSource', remote: "${repoPath}"]),
                changelog: false
        echo 'Successfully loaded shared library'
        return sharedLib
    } catch (Exception e) {
        echo "Failed to load shared library: ${e.message}"
        error('Shared Library Loading Failed')
    }
}