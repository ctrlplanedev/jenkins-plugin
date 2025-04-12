pipeline {
    agent any
    
    parameters {
        string(name: 'JOB_ID', defaultValue: '', description: 'Ctrlplane Job ID')
    }

    stages {
        stage('Deploy') {
            steps {
                script {
                    echo "Processing job with ID: ${params.JOB_ID}"
                }
            }
        }
    }
}