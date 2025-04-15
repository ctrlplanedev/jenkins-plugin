import groovy.json.JsonOutput

pipeline {
    agent any
    
    parameters {
        string(name: 'JOB_ID', defaultValue: '', description: 'Ctrlplane Job ID passed by the plugin')
    }

    stages {
        stage('Fetch Ctrlplane Job Details') {
            steps {
                script {
                    if (!params.JOB_ID) {
                        error 'JOB_ID parameter is required'
                    }
                    echo "Fetching details for Job ID: ${params.JOB_ID}"

                    def jobDetails = ctrlplaneGetJob jobId: params.JOB_ID

                    echo "-----------------------------------------"
                    echo "Successfully fetched job details:"
                    echo JsonOutput.prettyPrint(JsonOutput.toJson(jobDetails))
                    echo "-----------------------------------------"

                    // Example: Access specific fields from the returned map
                    // if(jobDetails.variables) {
                    //    echo "Specific Variable: ${jobDetails.variables.your_variable_name}"
                    // }
                    // if(jobDetails.metadata) {
                    //    echo "Metadata Value: ${jobDetails.metadata.your_metadata_key}"
                    // }
                    // if(jobDetails.job_config) {
                    //    echo "Job Config: ${jobDetails.job_config.jobUrl}" 
                    // }
                }
            }
        }
    }
}