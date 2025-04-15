package io.jenkins.plugins.ctrlplane.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main agent class that manages agent registration, job polling, and status updates via Ctrlplane API.
 * Consolidates HTTP client logic using java.net.http.HttpClient.
 */
public class JobAgent {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiUrl;
    private final String apiKey;
    private final String name;
    private final String agentWorkspaceId;

    private final AtomicReference<String> agentIdRef = new AtomicReference<>(null);

    /**
     * Creates a new JobAgent.
     *
     * @param apiUrl            the API URL
     * @param apiKey            the API key
     * @param name              the agent name
     * @param agentWorkspaceId  the workspace ID this agent belongs to
     */
    public JobAgent(String apiUrl, String apiKey, String name, String agentWorkspaceId) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.name = name;
        this.agentWorkspaceId = agentWorkspaceId;
    }

    /**
     * Ensures the agent is registered with Ctrlplane.
     * This will only register the agent once per instance lifecycle unless registration fails.
     *
     * @return true if the agent is considered registered (ID is present), false otherwise
     */
    public boolean ensureRegistered() {
        if (agentIdRef.get() != null) {
            return true;
        }
        String path = "/v1/job-agents/name";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("name", this.name);
        requestBody.put("type", "jenkins");
        if (this.agentWorkspaceId != null && !this.agentWorkspaceId.isBlank()) {
            requestBody.put("workspaceId", this.agentWorkspaceId);
        } else {
            LOGGER.error("Cannot register agent: Workspace ID is missing.");
            return false;
        }

        AgentResponse agentResponse = makeHttpRequest("PATCH", path, requestBody, AgentResponse.class);

        if (agentResponse != null && agentResponse.getId() != null) {
            String agentId = agentResponse.getId();
            agentIdRef.set(agentId);
            LOGGER.info("Agent upsert via PATCH {} succeeded. Agent ID: {}", path, agentId);
            return true;
        }
        if (agentResponse == null) {
            LOGGER.error(
                    "Failed to upsert agent {} via PATCH {}. Request failed or returned unexpected response.",
                    this.name,
                    path);
        } else { // agentResponse != null but agentResponse.getId() == null
            LOGGER.error(
                    "Failed to upsert agent {} via PATCH {}. Response did not contain an agent ID.", this.name, path);
        }
        return false;
    }

    /**
     * Gets the agent ID as a string if the agent is registered.
     *
     * @return the agent ID as a string, or null if not registered
     */
    public String getAgentId() {
        return agentIdRef.get();
    }

    /**
     * Gets the next jobs for this agent from the Ctrlplane API.
     * Handles agent registration if needed and validates the response format.
     *
     * @return a list of jobs (represented as Maps), empty if none are available, if the agent is not registered,
     *         or if there was an error communicating with the API
     */
    public List<Map<String, Object>> getNextJobs() {
        String agentId = agentIdRef.get();

        if (agentId == null) {
            if (!ensureRegistered()) {
                LOGGER.error("Cannot get jobs: Agent registration/upsert failed or did not provide an ID.");
                return Collections.emptyList();
            }

            agentId = agentIdRef.get();

            if (agentId == null) {
                LOGGER.error(
                        "Internal error: ensureRegistered returned true but agent ID is still null. Cannot get jobs.");
                return Collections.emptyList();
            }
        }

        String path = String.format("/v1/job-agents/%s/queue/next", agentId);

        Map<String, Object> response = makeHttpRequest("GET", path, null, new TypeReference<Map<String, Object>>() {});

        if (response == null || !response.containsKey("jobs")) {
            LOGGER.warn("Failed to fetch jobs or no jobs available for agent: {}", agentId);
            return Collections.emptyList();
        }

        Object jobsObj = response.get("jobs");
        if (!(jobsObj instanceof List)) {
            LOGGER.error("Unexpected response format from jobs endpoint: 'jobs' is not a List");
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) jobsObj;
        LOGGER.debug("Successfully fetched {} jobs from Ctrlplane for agent: {}", jobs.size(), agentId);
        return jobs;
    }

    /**
     * Updates the status of a specific job.
     *
     * @param jobId The UUID of the job to update.
     * @param status The new status string (e.g., "in_progress", "successful", "failure").
     * @param details Optional map containing additional details about the status update.
     * @return true if the update was likely successful (e.g., 2xx response), false otherwise.
     */
    public boolean updateJobStatus(UUID jobId, String status, Map<String, Object> details) {
        if (jobId == null || status == null || status.isBlank()) {
            LOGGER.error("Invalid input for updateJobStatus: Job ID and Status are required.");
            return false;
        }

        String path = String.format("/v1/jobs/%s", jobId);
        Map<String, Object> requestBody = buildJobUpdatePayload(status, details);

        Integer responseCode = makeHttpRequestAndGetCode("PATCH", path, requestBody);

        boolean success = responseCode != null && responseCode >= 200 && responseCode < 300;
        logStatusUpdateResult(success, jobId, status, responseCode);
        return success;
    }

    /**
     * Builds the payload for job status updates.
     *
     * @param status The status to set for the job
     * @param details Additional details to include in the update
     * @return A map containing the formatted payload for the API request
     */
    private Map<String, Object> buildJobUpdatePayload(String status, Map<String, Object> details) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status);

        if (details == null || details.isEmpty()) {
            return payload;
        }

        if (details.containsKey("externalId")) {
            payload.put("externalId", details.get("externalId").toString());
        }

        String message = extractMessage(details);
        if (message != null) {
            payload.put("message", message);
        }

        if (details.containsKey("ctrlplane/links") && details.get("ctrlplane/links") instanceof Map) {
            payload.put("ctrlplane/links", details.get("ctrlplane/links"));
        }

        return payload;
    }

    /**
     * Extracts message from details using priority order.
     *
     * @param details Map containing potential message sources
     * @return The extracted message string, or null if no message source is found
     */
    private String extractMessage(Map<String, Object> details) {
        String[] messageSources = {"message", "trigger", "reason"};

        for (String source : messageSources) {
            if (details.containsKey(source)) {
                return source.equals("trigger")
                        ? "Triggered by: " + details.get(source)
                        : details.get(source).toString();
            }
        }

        return null;
    }

    /**
     * Logs the result of a status update.
     *
     * @param success Whether the update was successful
     * @param jobId The UUID of the job that was updated
     * @param status The status that was set
     * @param responseCode The HTTP response code received
     */
    private void logStatusUpdateResult(boolean success, UUID jobId, String status, Integer responseCode) {
        if (success) {
            LOGGER.info("Successfully updated status for job {} to {}", jobId, status);
        } else {
            LOGGER.error(
                    "Failed to update status for job {} to {}. Response code: {}",
                    jobId,
                    status,
                    responseCode != null ? responseCode : "N/A");
        }
    }

    /**
     * Retrieves job details from the Ctrlplane API by job ID.
     *
     * @param jobId UUID identifier of the job to fetch
     * @return Map containing job data or null if the job cannot be retrieved
     */
    public Map<String, Object> getJob(UUID jobId) {
        if (jobId == null) {
            LOGGER.error("Invalid input for getJob: Job ID cannot be null.");
            return null;
        }

        String path = String.format("/v1/jobs/%s", jobId);
        LOGGER.debug("Attempting to GET job details from path: {}", path);

        Map<String, Object> jobData = makeHttpRequest("GET", path, null, new TypeReference<Map<String, Object>>() {});

        if (jobData == null) {
            LOGGER.warn("Failed to retrieve details for job {}", jobId);
            return null;
        }

        LOGGER.info("Successfully retrieved details for job {}", jobId);
        return jobData;
    }

    // --- Internal HTTP Helper Methods (using java.net.http) ---

    /**
     * Makes an HTTP request and deserializes the JSON response to a specific class.
     *
     * @param method HTTP method (GET, POST, PUT, PATCH, etc.)
     * @param path API endpoint path
     * @param requestBody Object to serialize as JSON request body (null for methods without body)
     * @param responseType Class to deserialize the JSON response into
     * @return Deserialized response object, or null if an error occurs
     */
    private <T> T makeHttpRequest(String method, String path, Object requestBody, Class<T> responseType) {
        HttpResponse<InputStream> response = executeRequest(method, path, requestBody);
        if (response == null) {
            return null;
        }

        try {
            return handleResponse(response, responseType);
        } catch (IOException e) {
            LOGGER.error("Error processing response from {} {}: {}", method, path, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Makes an HTTP request and deserializes the JSON response to a generic type.
     *
     * @param method HTTP method (GET, POST, PUT, PATCH, etc.)
     * @param path API endpoint path
     * @param requestBody Object to serialize as JSON request body (null for methods without body)
     * @param responseType TypeReference describing the expected response type
     * @return Deserialized response object, or null if an error occurs
     */
    private <T> T makeHttpRequest(String method, String path, Object requestBody, TypeReference<T> responseType) {
        HttpResponse<InputStream> response = executeRequest(method, path, requestBody);
        if (response == null) {
            return null;
        }

        try {
            return handleResponse(response, responseType);
        } catch (IOException e) {
            LOGGER.error("Error processing response from {} {}: {}", method, path, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Makes an HTTP request and returns only the response HTTP status code.
     *
     * @param method HTTP method (e.g., PUT, POST)
     * @param path API endpoint path
     * @param requestBody Object to serialize as JSON request body
     * @return HTTP status code, or null if an error occurs
     */
    private Integer makeHttpRequestAndGetCode(String method, String path, Object requestBody) {
        try {
            HttpRequest request = buildRequest(path, method, requestBody).build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();

            if (statusCode < 200 || statusCode >= 300) {
                LOGGER.warn("HTTP request to {}{} returned non-success status: {}", this.apiUrl, path, statusCode);
            }

            return statusCode;
        } catch (Exception e) {
            handleRequestException(method, path, e);
            return null;
        }
    }

    /**
     * Executes an HTTP request and returns the response.
     *
     * @param method HTTP method to use
     * @param path API endpoint path
     * @param requestBody Request body to send (may be null)
     * @return HTTP response with input stream, or null if request failed
     */
    private HttpResponse<InputStream> executeRequest(String method, String path, Object requestBody) {
        try {
            HttpRequest request = buildRequest(path, method, requestBody).build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            handleRequestException(method, path, e);
            return null;
        }
    }

    /**
     * Handles exceptions from HTTP requests in a consistent way.
     */
    private void handleRequestException(String method, String path, Exception e) {
        LOGGER.error("Error during {} request to {}{}: {}", method, this.apiUrl, path, e.getMessage(), e);
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    // --- URI and Request Building ---
    /**
     * Builds a URI by properly combining the API URL with the given path.
     *
     * @param path API endpoint path to append
     * @return Fully formed URI for the API request
     * @throws URISyntaxException if the resulting URI is invalid
     */
    private URI buildUri(String path) throws URISyntaxException {
        String cleanApiUrl =
                this.apiUrl.endsWith("/") ? this.apiUrl.substring(0, this.apiUrl.length() - 1) : this.apiUrl;
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        String fullUrl;

        if (cleanApiUrl.endsWith("/api")) {
            fullUrl = cleanApiUrl + cleanPath;
        } else {
            fullUrl = cleanApiUrl + "/api" + cleanPath;
        }

        return new URI(fullUrl);
    }

    /**
     * Builds an HTTP request with proper headers and body for the Ctrlplane API.
     *
     * @param path API endpoint path
     * @param method HTTP method to use (GET, POST, etc.)
     * @param requestBody Object to serialize as request body (may be null)
     * @return Configured HTTP request builder
     * @throws URISyntaxException if the URI is invalid
     * @throws IOException if request body serialization fails
     */
    private HttpRequest.Builder buildRequest(String path, String method, Object requestBody)
            throws URISyntaxException, IOException {

        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

        if (requestBody != null && (method.equals("POST") || method.equals("PUT") || method.equals("PATCH"))) {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(requestBody);
            bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(jsonBytes);
        }

        return HttpRequest.newBuilder()
                .uri(buildUri(path))
                .header("X-API-Key", this.apiKey)
                .header("Content-Type", "application/json; utf-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .method(method, bodyPublisher);
    }

    // --- Response Handling ---

    /**
     * Handles HTTP response by deserializing JSON content to a specified class.
     *
     * @param response HTTP response containing JSON data
     * @param responseType Class to deserialize JSON into
     * @return Deserialized object of requested type or null if response isn't successful
     * @throws IOException if JSON parsing fails
     */
    private <T> T handleResponse(HttpResponse<InputStream> response, Class<T> responseType) throws IOException {
        int statusCode = response.statusCode();

        if (statusCode < 200 || statusCode >= 300) {
            handleErrorResponse(response, statusCode);
            return null;
        }

        try (InputStream is = response.body()) {
            if (statusCode == 204 || is == null) {
                try {
                    return responseType.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    LOGGER.debug("Cannot instantiate default for empty response type {}", responseType.getName());
                    return null;
                }
            }

            return objectMapper.readValue(is, responseType);
        }
    }

    /**
     * Handles HTTP response by deserializing JSON content to a specified generic type.
     *
     * @param response HTTP response containing JSON data
     * @param responseType TypeReference describing the target generic type
     * @return Deserialized object of requested type or null if response isn't successful
     * @throws IOException if JSON parsing fails
     */
    private <T> T handleResponse(HttpResponse<InputStream> response, TypeReference<T> responseType) throws IOException {
        int statusCode = response.statusCode();

        if (statusCode < 200 || statusCode >= 300) {
            handleErrorResponse(response, statusCode);
            return null;
        }

        try (InputStream is = response.body()) {
            if (statusCode == 204 || is == null) {
                return null;
            }

            return objectMapper.readValue(is, responseType);
        }
    }

    /**
     * Logs error details from HTTP responses with error status codes.
     *
     * @param response HTTP response with error status
     * @param statusCode HTTP status code
     */
    private void handleErrorResponse(HttpResponse<InputStream> response, int statusCode) {
        String errorBody = "<Could not read error body>";

        try (InputStream es = response.body()) {
            if (es != null) {
                errorBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read error response body: {}", e.getMessage());
        }

        LOGGER.error("HTTP Error: {} - URL: {} - Response: {}", statusCode, response.uri(), errorBody);
    }

    // --- Simple Inner Class for Agent Registration Response ---
    /** Minimal representation of the Agent registration response. */
    private static class AgentResponse {
        private String id;
        private String name;
        private String workspaceId;
        private String type;
        private Map<String, Object> config; // JSON object representing config

        public String getId() {
            return id;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public void setId(String id) {
            this.id = id;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public String getName() {
            return name;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public void setName(String name) {
            this.name = name;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public String getWorkspaceId() {
            return workspaceId;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public void setWorkspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public String getType() {
            return type;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public void setType(String type) {
            this.type = type;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public Map<String, Object> getConfig() {
            return config;
        }

        @SuppressWarnings("unused") // Used by Jackson deserialization
        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }

    // Job representation is handled via Map<String, Object> for simplicity now.
    // If Job structure becomes complex, a JobResponse inner class could be added.
}
