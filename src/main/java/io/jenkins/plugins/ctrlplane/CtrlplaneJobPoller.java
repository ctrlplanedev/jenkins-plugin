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

        // Get and validate configuration
        CtrlplaneConfig config = getAndValidateConfig();
        if (config == null) {
            return;
        }

        // Initialize and register agent
        if (!initializeAndRegisterAgent(config)) {
            return;
        }

        // Poll for jobs
        List<Map<String, Object>> pendingJobs = pollForJobs();
        if (pendingJobs == null || pendingJobs.isEmpty()) {
            return;
        }

        // Process jobs
        processJobs(pendingJobs);
    }

    private CtrlplaneConfig getAndValidateConfig() {
        CtrlplaneGlobalConfiguration config = CtrlplaneGlobalConfiguration.get();
        CtrlplaneConfig ctrlConfig = new CtrlplaneConfig(
            config.getApiUrl(),
            config.getApiKey(), 
            config.getAgentId(),
            config.getAgentWorkspaceId()
        );

        if (!ctrlConfig.validate()) {
            return null;
        }

        return ctrlConfig;
    }

    private boolean initializeAndRegisterAgent(CtrlplaneConfig config) {
        if (jobAgent == null) {
            jobAgent = createJobAgent(
                config.apiUrl, 
                config.apiKey,
                config.agentName,
                config.agentWorkspaceId
            );
        }

        if (!jobAgent.ensureRegistered()) {
            LOGGER.error("Agent registration check failed. Skipping polling cycle.");
            return false;
        }

        String currentAgentId = jobAgent.getAgentId();
        if (currentAgentId == null) {
            LOGGER.error("Agent ID not available after registration attempt. Skipping polling cycle.");
            return false;
        }

        LOGGER.debug("Polling jobs for registered agent ID: {}", currentAgentId);
        return true;
    }

    private List<Map<String, Object>> pollForJobs() {
        List<Map<String, Object>> pendingJobs = jobAgent.getNextJobs();

        if (pendingJobs == null || pendingJobs.isEmpty()) {
            LOGGER.debug("No pending Ctrlplane jobs found or failed to fetch. Finished cycle.");
            return null;
        }

        LOGGER.info("Polled Ctrlplane API. Found {} job(s) to process.", pendingJobs.size());
        return pendingJobs;
    }

    private void processJobs(List<Map<String, Object>> pendingJobs) {
        JobProcessingStats stats = new JobProcessingStats();

        for (Map<String, Object> jobMap : pendingJobs) {
            try {
                processJob(jobMap, stats);
            } catch (Exception e) {
                handleJobError(jobMap, e, stats);
            }
        }

        LOGGER.info(
            "Ctrlplane job polling cycle finished. Triggered: {}, Skipped: {}, Errors: {}",
            stats.triggered,
            stats.skipped,
            stats.errors
        );
    }

    private void processJob(Map<String, Object> jobMap, JobProcessingStats stats) {
        JobInfo jobInfo = extractJobInfo(jobMap);
        if (jobInfo == null) {
            stats.skipped++;
            return;
        }

        if (triggeredJobIds.containsKey(jobInfo.jobId)) {
            LOGGER.debug("Skipping already triggered Ctrlplane job ID: {}", jobInfo.jobId);
            stats.skipped++;
            return;
        }

        updateJobStatus(jobInfo, "RUNNING", "JenkinsPoller");
        triggerJenkinsJob(jobInfo, stats);
    }

    private JobInfo extractJobInfo(Map<String, Object> jobMap) {
        Object idObj = jobMap.get("id");
        if (!(idObj instanceof String jobId)) {
            LOGGER.warn("Skipping job: Missing or invalid 'id' field. Job Data: {}", jobMap);
            return null;
        }

        UUID jobUUID;
        try {
            jobUUID = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Skipping job: Invalid UUID format for job ID '{}'.", jobId);
            return null;
        }

        // Get job config
        Object configObj = jobMap.get("jobAgentConfig");
        if (!(configObj instanceof Map)) {
            LOGGER.warn("Skipping job ID {}: Missing or invalid 'jobAgentConfig' field.", jobId);
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> jobConfig = (Map<String, Object>) configObj;

        // Get Jenkins job name
        Object jenkinsJobNameObj = jobConfig.get("jenkinsJobName");
        if (!(jenkinsJobNameObj instanceof String jenkinsJobName) || jenkinsJobName.isBlank()) {
            LOGGER.warn("Skipping job ID {}: Missing or blank 'jenkinsJobName' in jobAgentConfig.", jobId);
            return null;
        }

        return new JobInfo(jobId, jobUUID, jenkinsJobName);
    }

    private void triggerJenkinsJob(JobInfo jobInfo, JobProcessingStats stats) {
        hudson.model.Job<?, ?> jenkinsItem = Jenkins.get().getItemByFullName(jobInfo.jenkinsJobName, hudson.model.Job.class);

        if (jenkinsItem == null) {
            handleMissingJenkinsJob(jobInfo);
            stats.errors++;
            return;
        }

        if (!(jenkinsItem instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> jenkinsJob)) {
            handleNonParameterizedJob(jobInfo);
            stats.errors++;
            return;
        }

        StringParameterValue jobIdParam = new StringParameterValue("JOB_ID", jobInfo.jobId);
        ParametersAction paramsAction = new ParametersAction(jobIdParam);

        Object future = jenkinsJob.scheduleBuild2(0, paramsAction);
        if (future != null) {
            handleSuccessfulTrigger(jobInfo);
            triggeredJobIds.put(jobInfo.jobId, Boolean.TRUE);
            stats.triggered++;
        } else {
            handleFailedTrigger(jobInfo);
            stats.errors++;
        }
    }

    private void handleJobError(Map<String, Object> jobMap, Exception e, JobProcessingStats stats) {
        String jobId = jobMap.get("id") instanceof String ? (String)jobMap.get("id") : "unknown";
        LOGGER.error("Error processing Ctrlplane job ID {}: {}", jobId, e.getMessage(), e);
        
        try {
            UUID jobUUID = UUID.fromString(jobId);
            jobAgent.updateJobStatus(
                jobUUID,
                "FAILED",
                Collections.singletonMap("reason", "Exception during processing: " + e.getMessage())
            );
        } catch (Exception ex) {
            LOGGER.error("Failed to update error status for job {}", jobId, ex);
        }
        
        stats.errors++;
    }

    private void updateJobStatus(JobInfo jobInfo, String status, String trigger) {
        if (!jobAgent.updateJobStatus(
                jobInfo.jobUUID, 
                status,
                Collections.singletonMap("trigger", trigger))) {
            LOGGER.warn(
                "Failed to update Ctrlplane job status to {} for ID: {}",
                status,
                jobInfo.jobId
            );
        }
    }

    private void handleMissingJenkinsJob(JobInfo jobInfo) {
        LOGGER.warn("Jenkins job '{}' for Ctrlplane job ID {} not found.", jobInfo.jenkinsJobName, jobInfo.jobId);
        updateJobStatus(jobInfo, "FAILED", "Jenkins job not found: " + jobInfo.jenkinsJobName);
    }

    private void handleNonParameterizedJob(JobInfo jobInfo) {
        LOGGER.warn(
            "Jenkins job '{}' for Ctrlplane job ID {} is not a Parameterized job.",
            jobInfo.jenkinsJobName,
            jobInfo.jobId
        );
        updateJobStatus(jobInfo, "FAILED", "Jenkins job not parameterizable: " + jobInfo.jenkinsJobName);
    }

    private void handleSuccessfulTrigger(JobInfo jobInfo) {
        LOGGER.info(
            "Successfully scheduled Jenkins job '{}' for Ctrlplane job ID {}",
            jobInfo.jenkinsJobName,
            jobInfo.jobId
        );
    }

    private void handleFailedTrigger(JobInfo jobInfo) {
        LOGGER.error(
            "Failed to schedule Jenkins job '{}' for Ctrlplane job ID {}",
            jobInfo.jenkinsJobName,
            jobInfo.jobId
        );
        updateJobStatus(jobInfo, "FAILED", "Jenkins scheduleBuild2 returned null");
    }

    /**
     * Factory method for creating the JobAgent. Can be overridden for testing.
     */
    protected JobAgent createJobAgent(String apiUrl, String apiKey, String agentName, String agentWorkspaceId) {
        return new JobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
    }

    private static class CtrlplaneConfig {
        final String apiUrl;
        final String apiKey;
        final String agentName;
        final String agentWorkspaceId;

        CtrlplaneConfig(String apiUrl, String apiKey, String agentName, String agentWorkspaceId) {
            this.apiUrl = apiUrl;
            this.apiKey = apiKey;
            this.agentName = agentName;
            this.agentWorkspaceId = agentWorkspaceId;
        }

        boolean validate() {
            String[] requiredConfigs = {apiUrl, apiKey, agentName};
            String[] configNames = {"API URL", "API key", "Agent ID (Name)"};
            
            for (int i = 0; i < requiredConfigs.length; i++) {
                if (requiredConfigs[i] == null || requiredConfigs[i].isBlank()) {
                    LOGGER.warn("Ctrlplane {} not configured. Skipping polling cycle.", configNames[i]);
                    return false;
                }
            }

            if (agentWorkspaceId == null || agentWorkspaceId.isBlank()) {
                LOGGER.warn("Ctrlplane Agent Workspace ID not configured. Registration might fail or be incomplete.");
            }

            return true;
        }
    }

    private static class JobInfo {
        final String jobId;
        final UUID jobUUID;
        final String jenkinsJobName;

        JobInfo(String jobId, UUID jobUUID, String jenkinsJobName) {
            this.jobId = jobId;
            this.jobUUID = jobUUID;
            this.jenkinsJobName = jenkinsJobName;
        }
    }

    private static class JobProcessingStats {
        int triggered = 0;
        int skipped = 0;
        int errors = 0;
    }
}
