pipeline {
    agent any

    tools {
        maven 'M3.3.9'
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    stages {
        stage('build') {
            steps {
                withMaven(options: [junitPublisher(ignoreAttachments: false), artifactsPublisher()]) {
                    sh 'mvn -e install'
                }
            }
            post {
                success {
                    sh 'curl -H "Content-Type: application/json" --data "{build: true}" -X POST https://registry.hub.docker.com/u/dmadk/tiler-service/trigger/da192d9c-854f-43b0-acfb-d19ec80cedc6/'
                }
            }
        }
    }

    post {
        failure {
            // notify users when the Pipeline fails
            mail to: 'steen@lundogbendsen.dk',
                    subject: "Failed Pipeline: ${currentBuild.fullDisplayName}",
                    body: "Something is wrong with ${env.BUILD_URL}"
        }

    }
}
