pipeline {
    agent any
    
    parameters {
        string(name: 'JOB_ID', defaultValue: '', description: 'Ctrlplane Job ID')
        string(name: 'API_URL', defaultValue: 'https://api.example.com', description: 'API Base URL (optional)')
    }

    stages {
        stage('Deploy') {
            steps {
                script {
                    if (!params.JOB_ID) {
                        error 'JOB_ID parameter is required'
                    }
                    
                    def ctrlplane = load 'src/utils/CtrlplaneClient.groovy'
                    def job = ctrlplane.getJob(
                        params.JOB_ID,
                        params.API_URL,
                        // params.API_KEY
                    )
                    
                    if (!job) {
                        error "Failed to fetch data for job ${params.JOB_ID}"
                    }
                    
                    echo "Job status: ${job.id}"
                }
            }
        }
    }
}