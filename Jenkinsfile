pipeline {
    agent any

    tools {
        maven 'M3.3.9'
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    stages {
        stage('checkout') {
            steps {
                checkout scm
            }
        }

        stage('build') {
            steps {
                withMaven() {
                    sh 'mvn -e -DskipTests install'
                }
            }
        }
    }

    post {
        success {
            sh 'curl -H "Content-Type: application/json" --data '{"build": true}' -X POST https://registry.hub.docker.com/u/dmadk/tiler-service/trigger/da192d9c-854f-43b0-acfb-d19ec80cedc6/'
        }
    }
}
