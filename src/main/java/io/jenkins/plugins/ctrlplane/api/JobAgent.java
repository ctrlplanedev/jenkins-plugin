package io.jenkins.plugins.ctrlplane.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
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
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main agent class that manages agent registration, job polling, and status updates via Ctrlplane API.
 * Consolidates HTTP client logic using java.net.http.HttpClient.
 */
public class JobAgent {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobAgent.class);
    private static final ObjectMapper objectMapper = new ObjectMapper(); // Shared Jackson mapper

    // Use Java 11+ HttpClient
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // Or HTTP_2 if server supports
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiUrl;
    private final String apiKey;
    private final String name;
    private final String agentWorkspaceId; // Added

    private final AtomicReference<String> agentIdRef = new AtomicReference<>(null); // Store agent ID directly

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
            return true; // Already registered in this session
        }

        // Use PATCH /v1/job-agents/name for upsert
        String path = "/v1/job-agents/name";
        // Map<String, Object> config = createAgentConfig(); // Config not sent in this request
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("name", this.name);
        requestBody.put("type", "jenkins"); // Add agent type
        // Workspace ID is required according to the Go spec
        if (this.agentWorkspaceId != null && !this.agentWorkspaceId.isBlank()) {
            requestBody.put("workspaceId", this.agentWorkspaceId);
        } else {
            LOGGER.error("Cannot register agent: Workspace ID is missing.");
            return false; // Workspace ID is required
        }
        // Config map is not part of this payload
        // requestBody.put("config", config);

        // Make the PATCH request, parse the response to get the agent ID
        AgentResponse agentResponse = makeHttpRequest("PATCH", path, requestBody, AgentResponse.class);

        if (agentResponse != null && agentResponse.getId() != null) {
            String agentId = agentResponse.getId();
            agentIdRef.set(agentId); // Set the ID directly from the response
            LOGGER.info("Agent upsert via PATCH {} succeeded. Agent ID: {}", path, agentId);
            return true;
        }
        // Log error based on whether response was null or ID was null
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
     * Creates the agent configuration for registration.
     *
     * @return the agent configuration
     */
    private Map<String, Object> createAgentConfig() {
        Map<String, Object> config = new HashMap<>();
        // Temporarily hardcoded to exec-windows to make sure it is wokring.
        config.put("type", "exec-windows");
        // Consider adding plugin version
        // config.put("version", "...");

        try {
            Jenkins jenkins = Jenkins.get();
            String rootUrl = jenkins.getRootUrl();
            if (rootUrl != null) {
                config.put("jenkinsUrl", rootUrl);
            }
            // Jenkins.VERSION is usually available
            config.put("jenkinsVersion", Jenkins.VERSION);
        } catch (Exception e) { // Catch broader exceptions for robustness
            LOGGER.warn("Could not gather Jenkins instance information for agent config", e);
        }

        // Add system/environment info if desired
        // config.put("os", System.getProperty("os.name"));
        // config.put("arch", System.getProperty("os.arch"));

        return config;
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
     * Gets the next jobs for this agent.
     *
     * @return a list of jobs (represented as Maps), empty if none are available or if the agent is not registered
     */
    public List<Map<String, Object>> getNextJobs() {
        String agentId = agentIdRef.get();
        if (agentId == null) {
            // ensureRegistered will now attempt registration AND set the ID if successful.
            if (!ensureRegistered()) {
                // ensureRegistered logs the specific error (request failed or no ID in response)
                LOGGER.error("Cannot get jobs: Agent registration/upsert failed or did not provide an ID.");
                return Collections.emptyList();
            }
            // Re-check if agentId was set by ensureRegistered
            agentId = agentIdRef.get();
            if (agentId == null) {
                // This condition should technically not be reachable if ensureRegistered returned true,
                // as true implies the agentIdRef was set. Log an internal error if it happens.
                LOGGER.error(
                        "Internal error: ensureRegistered returned true but agent ID is still null. Cannot get jobs.");
                return Collections.emptyList();
            }
        }

        // Use the correct endpoint from the API spec: /v1/job-agents/{agentId}/queue/next
        String path = String.format("/v1/job-agents/%s/queue/next", agentId);

        // The response structure is different - it has a "jobs" property containing the array
        Map<String, Object> response = makeHttpRequest("GET", path, null, new TypeReference<Map<String, Object>>() {});

        if (response != null && response.containsKey("jobs")) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> jobs = (List<Map<String, Object>>) response.get("jobs");
                LOGGER.debug("Successfully fetched {} jobs from Ctrlplane for agent: {}", jobs.size(), agentId);
                return jobs;
            } catch (ClassCastException e) {
                LOGGER.error("Unexpected response format from jobs endpoint: {}", e.getMessage());
                return Collections.emptyList();
            }
        } else {
            LOGGER.warn("Failed to fetch jobs or no jobs available for agent: {}", agentId);
            return Collections.emptyList(); // Return empty list on failure or empty response
        }
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
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("status", status);

        // Extract message and externalId from details if present
        String message = null;
        String externalId = null;

        if (details != null && !details.isEmpty()) {
            // Check for external ID in details
            if (details.containsKey("externalId")) {
                externalId = details.get("externalId").toString();
                requestBody.put("externalId", externalId);
            }

            // Extract message if present, otherwise use trigger as message
            if (details.containsKey("message")) {
                message = details.get("message").toString();
            } else if (details.containsKey("trigger")) {
                message = "Triggered by: " + details.get("trigger");
            } else if (details.containsKey("reason")) {
                message = details.get("reason").toString();
            }

            if (message != null) {
                requestBody.put("message", message);
            }
        }

        // Check for ctrlplane/links metadata
        if (details != null && details.containsKey("ctrlplane/links")) {
            Object linksObj = details.get("ctrlplane/links");
            if (linksObj instanceof Map) {
                // Assuming the value is Map<String, String>, but API expects Map<String, Object>
                // No explicit cast needed as Map is compatible.
                requestBody.put("ctrlplane/links", linksObj);
            } else {
                LOGGER.warn(
                        "Value for 'ctrlplane/links' in details map is not a Map for job {}. Skipping links.", jobId);
            }
        }

        // Use PATCH method according to the API spec
        Integer responseCode = makeHttpRequestAndGetCode("PATCH", path, requestBody);

        boolean success = responseCode != null && responseCode >= 200 && responseCode < 300;
        if (success) {
            LOGGER.info("Successfully updated status for job {} to {}", jobId, status);
        } else {
            LOGGER.error(
                    "Failed to update status for job {} to {}. Response code: {}",
                    jobId,
                    status,
                    responseCode != null ? responseCode : "N/A");
        }
        return success;
    }

    /**
     * Gets the details of a specific job by its UUID.
     *
     * @param jobId The UUID of the job to retrieve.
     * @return A map containing the job details, or null if the job is not found or an error occurs.
     */
    public Map<String, Object> getJob(UUID jobId) {
        if (jobId == null) {
            LOGGER.error("Invalid input for getJob: Job ID cannot be null.");
            return null;
        }

        String path = String.format("/v1/jobs/%s", jobId);
        LOGGER.debug("Attempting to GET job details from path: {}", path);

        // Use the existing makeHttpRequest helper that handles generic Maps
        try {
            // The response for GET /v1/jobs/{jobId} is the Job object directly (Map)
            Map<String, Object> jobData =
                    makeHttpRequest("GET", path, null, new TypeReference<Map<String, Object>>() {});

            if (jobData != null) {
                LOGGER.info("Successfully retrieved details for job {}", jobId);
                return jobData;
            } else {
                // makeHttpRequest logs errors, but we can add context here
                LOGGER.warn("Failed to retrieve details for job {}, makeHttpRequest returned null.", jobId);
                return null;
            }
        } catch (Exception e) {
            // Catch any unexpected exceptions during the process
            LOGGER.error("Exception occurred while retrieving job details for job {}: {}", jobId, e.getMessage(), e);
            return null;
        }
    }

    // --- Internal HTTP Helper Methods (using java.net.http) ---

    /**
     * Makes an HTTP request and parses the JSON response body.
     *
     * @param method       HTTP method (GET, POST, PUT, PATCH, etc.)
     * @param path         API endpoint path
     * @param requestBody  Object to serialize as JSON body (null for methods without body)
     * @param responseType Class of the expected response object
     * @return Deserialized response object, or null on error
     */
    private <T> T makeHttpRequest(String method, String path, Object requestBody, Class<T> responseType) {
        try {
            HttpRequest.Builder requestBuilder = buildRequest(path, method, requestBody);
            HttpRequest request = requestBuilder.build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            return handleResponse(response, responseType);
        } catch (URISyntaxException | IOException | InterruptedException e) {
            LOGGER.error("Error during {} request to {}{}: {}", method, this.apiUrl, path, e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
            return null;
        }
    }

    /**
     * Overload for handling generic types like List<T>.
     * @param responseType TypeReference describing the expected response type
     */
    private <T> T makeHttpRequest(String method, String path, Object requestBody, TypeReference<T> responseType) {
        try {
            HttpRequest.Builder requestBuilder = buildRequest(path, method, requestBody);
            HttpRequest request = requestBuilder.build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            return handleResponse(response, responseType);
        } catch (URISyntaxException | IOException | InterruptedException e) {
            LOGGER.error("Error during {} request to {}{}: {}", method, this.apiUrl, path, e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Makes an HTTP request and returns only the response code.
     * @param method HTTP method (e.g., PUT, POST)
     * @param path API endpoint path
     * @param requestBody Object to serialize as JSON body
     * @return HTTP status code, or null on error
     */
    private Integer makeHttpRequestAndGetCode(String method, String path, Object requestBody) {
        try {
            HttpRequest.Builder requestBuilder = buildRequest(path, method, requestBody);
            HttpRequest request = requestBuilder.build();

            // Send request and discard body, just get status code
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();

            // Log non-2xx responses slightly differently here if needed, or rely on caller
            if (statusCode < 200 || statusCode >= 300) {
                LOGGER.warn("HTTP request to {}{} returned non-success status: {}", this.apiUrl, path, statusCode);
            }
            return statusCode;

        } catch (URISyntaxException | IOException | InterruptedException e) {
            LOGGER.error(
                    "Error during {} request (status check) to {}{}: {}", method, this.apiUrl, path, e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    // --- URI and Request Building ---
    private URI buildUri(String path) throws URISyntaxException, MalformedURLException {
        String cleanApiUrl =
                this.apiUrl.endsWith("/") ? this.apiUrl.substring(0, this.apiUrl.length() - 1) : this.apiUrl;
        String cleanPath = path.startsWith("/") ? path : "/" + path;

        // Ensure /api/v1 structure
        String finalUrlString;
        if (cleanApiUrl.endsWith("/api")) {
            finalUrlString = cleanApiUrl + cleanPath; // Assumes path starts with /v1
        } else {
            finalUrlString = cleanApiUrl + "/api" + cleanPath;
        }
        return new URI(finalUrlString);
    }

    private HttpRequest.Builder buildRequest(String path, String method, Object requestBody)
            throws URISyntaxException, IOException, MalformedURLException {

        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();
        if (requestBody != null && requiresBody(method)) {
            byte[] jsonBytes = objectMapper.writeValueAsBytes(requestBody);
            bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(jsonBytes);
        }

        return HttpRequest.newBuilder()
                .uri(buildUri(path))
                .header("X-API-Key", this.apiKey)
                .header("Content-Type", "application/json; utf-8")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15)) // Request timeout
                .method(method, bodyPublisher);
    }

    /** Helper to determine if a method typically sends a body */
    private boolean requiresBody(String method) {
        return method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
    }

    // --- Response Handling ---

    private <T> T handleResponse(HttpResponse<InputStream> response, Class<T> responseType) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            try (InputStream is = response.body()) {
                if (statusCode == 204 || is == null) { // 204 No Content or null body
                    // Try creating a default instance if possible and sensible
                    try {
                        return responseType.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        LOGGER.debug("Cannot instantiate default for empty response type {}", responseType.getName());
                        return null;
                    }
                }
                return objectMapper.readValue(is, responseType);
            }
        } else {
            handleErrorResponse(response, statusCode);
            return null;
        }
    }

    private <T> T handleResponse(HttpResponse<InputStream> response, TypeReference<T> responseType) throws IOException {
        int statusCode = response.statusCode();
        if (statusCode >= 200 && statusCode < 300) {
            try (InputStream is = response.body()) {
                if (statusCode == 204 || is == null) { // 204 No Content or null body
                    return null; // Cannot default instantiate generics easily
                }
                return objectMapper.readValue(is, responseType);
            }
        } else {
            handleErrorResponse(response, statusCode);
            return null;
        }
    }

    private void handleErrorResponse(HttpResponse<InputStream> response, int statusCode) {
        String errorBody = "<Could not read error body>";
        try (InputStream es = response.body()) { // Body might contain error details even on non-2xx
            if (es != null) {
                errorBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read error response body: {}", e.getMessage());
        }

        LOGGER.error(
                "HTTP Error: {} - URL: {} - Response: {}",
                statusCode,
                response.uri(), // Use URI from response
                errorBody);
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
