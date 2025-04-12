package io.jenkins.plugins.ctrlplane;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.ParametersAction;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A background task that periodically polls the Ctrlplane API for the next job.
 */
@Extension
public class CtrlplaneJobPoller extends AsyncPeriodicWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(CtrlplaneJobPoller.class);

    // In-memory tracking of triggered Ctrlplane job IDs to prevent duplicates
    private final ConcurrentHashMap<String, Boolean> triggeredJobIds = new ConcurrentHashMap<>();

    // The JobAgent for registration and job polling
    private JobAgent jobAgent;

    /**
     * Constructor.
     */
    public CtrlplaneJobPoller() {
        super("Ctrlplane Job Poller");
    }

    @Override
    public long getRecurrencePeriod() {
        // Read interval from global config
        int intervalSeconds = CtrlplaneGlobalConfiguration.get().getPollingIntervalSeconds();
        LOGGER.debug("Using polling interval: {} seconds", intervalSeconds);
        return TimeUnit.SECONDS.toMillis(intervalSeconds);
    }

    @SuppressFBWarnings(
            value = {"REC_CATCH_EXCEPTION"},
            justification = "Catching generic Exception for robust error handling.")
    @Override
    protected void execute(TaskListener listener) {
        LOGGER.debug("Starting Ctrlplane job polling cycle.");

        // 1. Get Global Configuration
        CtrlplaneGlobalConfiguration config = CtrlplaneGlobalConfiguration.get();
        String apiUrl = config.getApiUrl();
        String apiKey = config.getApiKey();
        String agentName = config.getAgentId(); // Use configured Agent ID as name
        String agentWorkspaceId = config.getAgentWorkspaceId(); // Get workspace ID

        // 2. Validate Configuration
        if (apiUrl == null || apiUrl.isBlank()) {
            LOGGER.warn("Ctrlplane API URL not configured. Skipping polling cycle.");
            return;
        }
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn("Ctrlplane API key not configured. Skipping polling cycle.");
            return;
        }
        if (agentName == null || agentName.isBlank()) {
            LOGGER.warn("Ctrlplane Agent ID (Name) not configured. Skipping polling cycle.");
            return;
        }
        // Optionally warn if workspace ID is missing, depending on requirements
        if (agentWorkspaceId == null || agentWorkspaceId.isBlank()) {
            LOGGER.warn("Ctrlplane Agent Workspace ID not configured. Registration might fail or be incomplete.");
            // Decide if this is fatal: return;
        }

        // 3. Create or get the JobAgent and ensure it's registered
        if (jobAgent == null) {
            // Use configured agentId as the name, add workspace ID
            jobAgent = createJobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
        } else {
            // TODO: Consider if JobAgent needs updating if config changes (e.g., API key)
            // Currently, it reuses the existing instance.
        }

        if (!jobAgent.ensureRegistered()) {
            LOGGER.error("Agent registration check failed. Skipping polling cycle.");
            return;
        }

        // Agent ID is now managed internally by JobAgent
        String currentAgentId = jobAgent.getAgentIdString();
        if (currentAgentId == null) {
            LOGGER.error("Agent ID not available after registration attempt. Skipping polling cycle.");
            return;
        }
        LOGGER.debug("Polling jobs for registered agent ID: {}", currentAgentId);

        // 4. Poll Ctrlplane API for jobs using the JobAgent
        List<Map<String, Object>> pendingJobs = jobAgent.getNextJobs();

        if (pendingJobs == null || pendingJobs.isEmpty()) { // Check for null explicitly
            LOGGER.debug("No pending Ctrlplane jobs found or failed to fetch. Finished cycle.");
            return;
        }

        LOGGER.info("Polled Ctrlplane API. Found {} job(s) to process.", pendingJobs.size());

        // 5. Process pending jobs
        int triggeredCount = 0;
        int skippedCount = 0;
        int errorCount = 0;

        for (Map<String, Object> ctrlplaneJobMap : pendingJobs) {
            // Extract job ID - assume it's a String field named 'id'
            Object idObj = ctrlplaneJobMap.get("id");
            if (!(idObj instanceof String ctrlplaneJobId)) {
                LOGGER.warn("Skipping job: Missing or invalid 'id' field. Job Data: {}", ctrlplaneJobMap);
                skippedCount++;
                continue;
            }
            UUID ctrlplaneJobUUID; // Need UUID for status updates later
            try {
                ctrlplaneJobUUID = UUID.fromString(ctrlplaneJobId);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Skipping job: Invalid UUID format for job ID '{}'.", ctrlplaneJobId);
                skippedCount++;
                continue;
            }

            // Get job agent config - assume it's a Map field named 'jobAgentConfig'
            Object configObj = ctrlplaneJobMap.get("jobAgentConfig");
            if (!(configObj instanceof Map)) {
                LOGGER.warn("Skipping job ID {}: Missing or invalid 'jobAgentConfig' field.", ctrlplaneJobId);
                skippedCount++;
                continue;
            }
            @SuppressWarnings("unchecked") // Safe due to instanceof check
            Map<String, Object> jobConfig = (Map<String, Object>) configObj;

            // Get Jenkins job name from the config map
            Object jenkinsJobNameObj = jobConfig.get("jenkinsJobName");
            if (!(jenkinsJobNameObj instanceof String jenkinsJobName) || jenkinsJobName.isBlank()) {
                LOGGER.warn("Skipping job ID {}: Missing or blank 'jenkinsJobName' in jobAgentConfig.", ctrlplaneJobId);
                skippedCount++;
                continue;
            }

            try {
                // Skip already triggered jobs
                if (triggeredJobIds.containsKey(ctrlplaneJobId)) {
                    LOGGER.debug("Skipping already triggered Ctrlplane job ID: {}", ctrlplaneJobId);
                    skippedCount++;
                    continue;
                }

                LOGGER.info("Processing new Ctrlplane job ID: {} -> Jenkins Job: '{}'", ctrlplaneJobId, jenkinsJobName);

                // Attempt to update status to RUNNING before triggering
                // Optional: provide details like Jenkins build URL placeholder if known
                if (!jobAgent.updateJobStatus(
                        ctrlplaneJobUUID, "RUNNING", Collections.singletonMap("trigger", "JenkinsPoller"))) {
                    LOGGER.warn(
                            "Failed to update Ctrlplane job status to RUNNING for ID: {}. Proceeding with trigger attempt anyway.",
                            ctrlplaneJobId);
                    // Decide if this is a fatal error for this job
                }

                hudson.model.Job<?, ?> jenkinsItem =
                        Jenkins.get().getItemByFullName(jenkinsJobName, hudson.model.Job.class);

                if (jenkinsItem == null) {
                    LOGGER.warn("Jenkins job '{}' for Ctrlplane job ID {} not found.", jenkinsJobName, ctrlplaneJobId);
                    // Update status to FAILED
                    jobAgent.updateJobStatus(
                            ctrlplaneJobUUID,
                            "FAILED",
                            Collections.singletonMap("reason", "Jenkins job not found: " + jenkinsJobName));
                    errorCount++;
                    continue;
                }

                // Check if the item can be parameterized
                if (!(jenkinsItem instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> jenkinsJob)) {
                    LOGGER.warn(
                            "Jenkins job '{}' for Ctrlplane job ID {} is not a Parameterized job.",
                            jenkinsJobName,
                            ctrlplaneJobId);
                    // Update status to FAILED
                    jobAgent.updateJobStatus(
                            ctrlplaneJobUUID,
                            "FAILED",
                            Collections.singletonMap("reason", "Jenkins job not parameterizable: " + jenkinsJobName));
                    errorCount++;
                    continue;
                }

                // Prepare parameters
                StringParameterValue jobIdParam = new StringParameterValue("CTRLPLANE_JOB_ID", ctrlplaneJobId);
                // Potentially add other parameters from jobConfig if needed
                ParametersAction paramsAction = new ParametersAction(jobIdParam);

                // Trigger the Jenkins build
                Object future = jenkinsJob.scheduleBuild2(0, paramsAction);

                if (future != null) {
                    LOGGER.info(
                            "Successfully scheduled Jenkins job '{}' for Ctrlplane job ID {}",
                            jenkinsJobName,
                            ctrlplaneJobId);
                    triggeredJobIds.put(ctrlplaneJobId, Boolean.TRUE);
                    triggeredCount++;
                    // Status already updated to RUNNING above
                } else {
                    LOGGER.error(
                            "Failed to schedule Jenkins job '{}' for Ctrlplane job ID {}",
                            jenkinsJobName,
                            ctrlplaneJobId);
                    // Update status to FAILED
                    jobAgent.updateJobStatus(
                            ctrlplaneJobUUID,
                            "FAILED",
                            Collections.singletonMap("reason", "Jenkins scheduleBuild2 returned null"));
                    errorCount++;
                }
            } catch (Exception e) {
                LOGGER.error("Error processing Ctrlplane job ID {}: {}", ctrlplaneJobId, e.getMessage(), e);
                // Attempt to update status to FAILED
                jobAgent.updateJobStatus(
                        ctrlplaneJobUUID,
                        "FAILED",
                        Collections.singletonMap("reason", "Exception during processing: " + e.getMessage()));
                errorCount++;
            }
        }

        LOGGER.info(
                "Ctrlplane job polling cycle finished. Triggered: {}, Skipped: {}, Errors: {}",
                triggeredCount,
                skippedCount,
                errorCount);
    }

    /**
     * Factory method for creating the JobAgent. Can be overridden for testing.
     */
    protected JobAgent createJobAgent(String apiUrl, String apiKey, String agentName, String agentWorkspaceId) {
        return new JobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
    }
}
