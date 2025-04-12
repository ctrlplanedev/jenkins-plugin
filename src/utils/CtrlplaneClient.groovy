import groovy.json.JsonSlurper

// Example usage with complex responses:
/*
def response = makeHttpRequest('job-123')

// Nested object example
println response.data.details.id

// Array example
response.items.each { item ->
    println "Found item: ${item}"
}

// Array of objects example
def pendingJobs = response.jobs.findAll { it.status == "pending" }
def jobIds = response.jobs.collect { it.id }
*/

// Example usage:
// def response = makeHttpRequest('job-123')
// def response = makeHttpRequest('job-123', 'https://custom-api.example.com') 

/**
 * Client for interacting with the Ctrlplane API
 * 
 * Example response structure:
 * {
 *   "id": "job-123",
 *   "status": "running",
 *   "created_at": "2024-03-20T10:00:00Z"
 * }
 * 
 * @param jobId The ID of the job to fetch
 * @param baseUrl Optional base URL for the API
 * @param apiKey Optional API key for authentication
 * @return Map containing the job data or null if request failed
 */
def getJob(String jobId, String baseUrl = 'https://api.example.com', String apiKey = null) {
    if (!jobId) {
        return null
    }

    def url = "${baseUrl}/jobs/${jobId}"
    def connection = new URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = 'GET'
    connection.setRequestProperty('Accept', 'application/json')
    
    // Add API key if provided
    if (apiKey) {
        connection.setRequestProperty('Authorization', "Bearer ${apiKey}")
    }
    
    try {
        connection.connect()
        
        if (connection.responseCode != 200) {
            return null
        }
        
        def responseStream = connection.inputStream
        def responseBody = responseStream.text
        
        def jsonSlurper = new JsonSlurper()
        return jsonSlurper.parseText(responseBody)
    } catch (Exception e) {
        return null
    } finally {
        connection.disconnect()
    }
} 