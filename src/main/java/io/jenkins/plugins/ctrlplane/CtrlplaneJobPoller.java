package io.jenkins.plugins.ctrlplane;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.util.HashMap;
import java.util.Iterator;
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
 * It also includes logic to reconcile the status of Jenkins jobs that were triggered
 * by this poller but might have finished while Jenkins was restarting, ensuring their
 * final status is reported back to Ctrlplane.
 */
@Extension
public class CtrlplaneJobPoller extends AsyncPeriodicWork {
    private static final Logger LOGGER = LoggerFactory.getLogger(CtrlplaneJobPoller.class);
    private final ConcurrentHashMap<String, ActiveJobInfo> activeJenkinsJobs = new ConcurrentHashMap<>();
    protected JobAgent jobAgent;

    public CtrlplaneJobPoller() {
        super("Ctrlplane Job Poller");
    }

    @Override
    public long getRecurrencePeriod() {
        CtrlplaneGlobalConfiguration config = CtrlplaneGlobalConfiguration.get();
        int intervalSeconds = config.getPollingIntervalSeconds();
        LOGGER.debug("Using polling interval: {} seconds", intervalSeconds);
        if (intervalSeconds < 10) {
            LOGGER.warn("Polling interval {}s is too low, using minimum 10s.", intervalSeconds);
            intervalSeconds = 10;
        }
        return TimeUnit.SECONDS.toMillis(intervalSeconds);
    }

    @Override
    protected void execute(TaskListener listener) {
        Jenkins jenkins = Jenkins.get();
        if (jenkins.isTerminating()) {
            LOGGER.info("Jenkins is terminating, skipping Ctrlplane job polling cycle.");
            return;
        }

        LOGGER.debug("Starting Ctrlplane job polling cycle.");

        CtrlplaneConfig config = getAndValidateConfig();
        if (config == null) {
            return;
        }

        if (!initializeAndRegisterAgent(config)) {
            return;
        }

        reconcileInProgressJobs();

        if (jenkins.isQuietingDown()) {
            LOGGER.info("Jenkins is quieting down, skipping polling for new Ctrlplane jobs.");
            return;
        }

        List<Map<String, Object>> pendingJobs = pollForJobs();
        if (pendingJobs == null || pendingJobs.isEmpty()) {
            return;
        }
        LOGGER.info("Polled Ctrlplane API. Found {} job(s) to process.", pendingJobs.size());

        processJobs(pendingJobs);

        LOGGER.debug("Finished Ctrlplane job polling cycle.");
    }

    /**
     * Fetches and validates the global configuration.
     * @return A valid CtrlplaneConfig instance, or null if configuration is invalid.
     */
    private CtrlplaneConfig getAndValidateConfig() {
        CtrlplaneGlobalConfiguration globalConfig = CtrlplaneGlobalConfiguration.get();

        CtrlplaneConfig ctrlConfig = new CtrlplaneConfig(
                globalConfig.getApiUrl(),
                globalConfig.getApiKey(),
                globalConfig.getAgentId(),
                globalConfig.getAgentWorkspaceId());

        if (!ctrlConfig.validate()) {
            return null;
        }
        return ctrlConfig;
    }

    /**
     * Initializes the JobAgent if needed and ensures it's registered.
     * Uses early returns on failure.
     * @param config The validated Ctrlplane configuration.
     * @return true if initialization and registration are successful, false otherwise.
     */
    private boolean initializeAndRegisterAgent(CtrlplaneConfig config) {
        if (jobAgent == null) {
            jobAgent = createJobAgent(config.apiUrl, config.apiKey, config.agentName, config.agentWorkspaceId);
            LOGGER.debug("Created new JobAgent instance");
        }

        boolean registered = jobAgent.ensureRegistered();
        if (!registered) {
            LOGGER.error("Agent registration check failed.");
            return false;
        }

        String currentAgentId = jobAgent.getAgentId();
        if (currentAgentId == null || currentAgentId.isBlank()) {
            LOGGER.error("Agent ID not available after registration attempt.");
            return false;
        }

        LOGGER.debug("Polling jobs for registered agent ID: {}", currentAgentId);
        return true;
    }

    /**
     * Polls the Ctrlplane API for the next available jobs.
     * @return A list of pending jobs (can be empty), or null if a critical error occurred.
     */
    private List<Map<String, Object>> pollForJobs() {
        if (jobAgent == null) {
            LOGGER.error("JobAgent not initialized before polling for jobs.");
            return null;
        }

        List<Map<String, Object>> jobs = jobAgent.getNextJobs();
        if (jobs == null) {
            LOGGER.warn("Polling for jobs failed or returned null.");
            return null;
        }

        if (jobs.isEmpty()) {
            LOGGER.debug("No pending Ctrlplane jobs found.");
        }

        return jobs;
    }

    /**
     * Checks the status of Jenkins jobs that were previously triggered by this poller.
     * If a job has completed (potentially during a Jenkins restart when the RunListener
     * wouldn't fire), this method updates its status in Ctrlplane.
     * This method logs errors but does not throw exceptions, as reconciliation is best-effort.
     */
    private void reconcileInProgressJobs() {
        if (activeJenkinsJobs.isEmpty()) {
            LOGGER.debug("No active Jenkins jobs to reconcile.");
            return;
        }

        LOGGER.debug("Reconciling status of {} active Jenkins job(s)...", activeJenkinsJobs.size());
        Iterator<Map.Entry<String, ActiveJobInfo>> iterator =
                activeJenkinsJobs.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, ActiveJobInfo> entry = iterator.next();
            String ctrlplaneJobId = entry.getKey();
            ActiveJobInfo activeJob = entry.getValue();

            Job<?, ?> jenkinsJob = Jenkins.get().getItemByFullName(activeJob.jenkinsJobName, Job.class);
            if (jenkinsJob == null) {
                LOGGER.warn(
                        "Jenkins job '{}' for Ctrlplane job {} not found during reconciliation. Assuming failure.",
                        activeJob.jenkinsJobName,
                        ctrlplaneJobId);
                updateCtrlplaneJobStatus(
                        ctrlplaneJobId,
                        activeJob.ctrlplaneJobUUID,
                        "failure",
                        Map.of("message", "Jenkins job not found during reconciliation"));
                iterator.remove();
                continue;
            }

            boolean removeJob;
            if (activeJob.jenkinsBuildNumber < 0) {
                removeJob = reconcileQueuedJob(activeJob);
            } else {
                removeJob = reconcileRunningOrCompletedJob(jenkinsJob, activeJob);
            }

            if (removeJob) {
                iterator.remove();
            }
        }
        LOGGER.debug("Finished reconciling job statuses.");
    }

    /**
     * Reconciles the state of a job that is believed to be in the Jenkins queue.
     *
     * @param activeJob The job information being tracked.
     * @return {@code true} if the job reached a final state (e.g., cancelled) and should be removed from tracking,
     *         {@code false} otherwise (still queued or state indeterminate).
     */
    private boolean reconcileQueuedJob(ActiveJobInfo activeJob) {
        Queue.Item item = Jenkins.get().getQueue().getItem(activeJob.queueId);

        if (item == null) {
            // Item not in queue anymore. Might have started, might have been deleted.
            // The main loop will check for a Run using the build number next time (if onStarted updated it).
            // Or if onStarted was missed (e.g., restart), the main loop might find the Run directly.
            LOGGER.debug(
                    "Queue item {} for Ctrlplane job {} is no longer in queue. Will check for Run later.",
                    activeJob.queueId,
                    activeJob.ctrlplaneJobUUID); // Log UUID for Ctrlplane context
            return false;
        }

        if (item.getFuture().isCancelled()) {
            LOGGER.warn(
                    "Queue item {} for Ctrlplane job {} was cancelled.", activeJob.queueId, activeJob.ctrlplaneJobUUID);
            updateCtrlplaneJobStatus(
                    activeJob.ctrlplaneJobUUID.toString(), // Need String ID here
                    activeJob.ctrlplaneJobUUID,
                    "cancelled",
                    Map.of("message", "Jenkins queue item cancelled"));
            return true;
        }

        LOGGER.debug(
                "Job {} (Queue ID {}) is still pending in the Jenkins queue.",
                activeJob.ctrlplaneJobUUID,
                activeJob.queueId);
        return false;
    }

    /**
     * Reconciles the state of a job that has potentially started running or has completed.
     *
     * @param jenkinsJob The Jenkins Job object.
     * @param activeJob The job information being tracked.
     * @return {@code true} if the job reached a final state (completed, failed) and should be removed from tracking,
     *         {@code false} otherwise (still running or state indeterminate).
     */
    private boolean reconcileRunningOrCompletedJob(Job<?, ?> jenkinsJob, ActiveJobInfo activeJob) {
        Run<?, ?> run = jenkinsJob.getBuildByNumber(activeJob.jenkinsBuildNumber);

        if (run == null) {
            LOGGER.warn(
                    "Jenkins build #{} for job '{}' (Ctrlplane job {}) not found during reconciliation, despite having build number. Assuming failure.",
                    activeJob.jenkinsBuildNumber,
                    activeJob.jenkinsJobName,
                    activeJob.ctrlplaneJobUUID);
            updateCtrlplaneJobStatus(
                    activeJob.ctrlplaneJobUUID.toString(), // Need String ID here
                    activeJob.ctrlplaneJobUUID,
                    "failure",
                    Map.of(
                            "message",
                            "Jenkins build not found during reconciliation",
                            "externalId",
                            String.valueOf(activeJob.jenkinsBuildNumber)));
            return true;
        }

        if (run.isBuilding()) {
            LOGGER.debug(
                    "Jenkins job '{}' #{} (Ctrlplane job {}) is still running.",
                    activeJob.jenkinsJobName,
                    activeJob.jenkinsBuildNumber,
                    activeJob.ctrlplaneJobUUID);
            return false;
        }

        Result result = run.getResult();
        if (result == null) {
            LOGGER.debug(
                    "Jenkins build #{} for job '{}' (Ctrlplane job {}) is not building but result is null. Checking again next cycle.",
                    activeJob.jenkinsBuildNumber,
                    activeJob.jenkinsJobName,
                    activeJob.ctrlplaneJobUUID);
            return false;
        }

        String finalStatus = "failure";
        if (result.isBetterOrEqualTo(Result.SUCCESS)) {
            finalStatus = "successful";
        } else if (result == Result.ABORTED) {
            finalStatus = "cancelled";
        }

        String message = "Jenkins job " + run.getFullDisplayName() + " completed with result: " + result.toString();
        LOGGER.info(
                "Reconciling completed Jenkins job '{}' #{} (Ctrlplane job {}). Updating status to {}",
                activeJob.jenkinsJobName,
                activeJob.jenkinsBuildNumber,
                activeJob.ctrlplaneJobUUID,
                finalStatus);

        Map<String, Object> details = buildCompletionDetails(activeJob, run, message);
        updateCtrlplaneJobStatus(
                activeJob.ctrlplaneJobUUID.toString(), activeJob.ctrlplaneJobUUID, finalStatus, details);

        return true;
    }

    /**
     * Processes the list of pending jobs polled from Ctrlplane.
     * Uses a try-catch around each job's processing to isolate errors.
     */
    private void processJobs(List<Map<String, Object>> pendingJobs) {
        JobProcessingStats stats = new JobProcessingStats();
        for (Map<String, Object> jobMap : pendingJobs) {
            try {
                processSingleJob(jobMap, stats);
            } catch (Exception e) {
                handleJobError(jobMap, e, stats);
            }
        }
        LOGGER.info(
                "Ctrlplane job processing finished. Triggered: {}, Skipped: {}, Errors: {}",
                stats.triggered,
                stats.skipped,
                stats.errors);
    }

    /**
     * Processes a single job fetched from Ctrlplane using early returns.
     */
    private void processSingleJob(Map<String, Object> jobMap, JobProcessingStats stats) {
        JobInfo jobInfo = extractJobInfo(jobMap);
        if (jobInfo == null) {
            stats.skipped++;
            return;
        }

        if (activeJenkinsJobs.containsKey(jobInfo.jobId)) {
            LOGGER.debug("Skipping already tracked active Ctrlplane job ID: {}", jobInfo.jobId);
            stats.skipped++;
            return;
        }

        boolean statusUpdated = updateCtrlplaneJobStatus(
                jobInfo.jobId, jobInfo.jobUUID, "in_progress", Map.of("message", "Triggering Jenkins job"));
        if (!statusUpdated) {
            LOGGER.warn(
                    "Failed to update Ctrlplane status to in_progress for job {}, proceeding with trigger attempt anyway.",
                    jobInfo.jobId);
        }

        triggerJenkinsJob(jobInfo, stats);
        updateJobStatusWithInitialLink(jobInfo);
    }

    /**
     * Extracts and validates job information from the raw map data.
     * Uses early returns for validation checks.
     */
    protected JobInfo extractJobInfo(Map<String, Object> jobMap) {
        Object idObj = jobMap.get("id");
        if (!(idObj instanceof String)) {
            LOGGER.warn("Skipping job: Missing or invalid 'id' field type. Job Data: {}", jobMap);
            return null;
        }
        String jobId = (String) idObj;
        if (jobId.isBlank()) {
            LOGGER.warn("Skipping job: Blank 'id' field. Job Data: {}", jobMap);
            return null;
        }

        UUID jobUUID;
        try {
            jobUUID = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Skipping job: Invalid UUID format for job ID '{}'.", jobId);
            return null;
        }

        Object statusObj = jobMap.get("status");
        if (!(statusObj instanceof String status) || !"pending".equals(status)) {
            LOGGER.debug(
                    "Skipping job ID {}: Status is not pending. Current status: {}",
                    jobId,
                    statusObj != null ? statusObj.toString() : "unknown");
            return null;
        }

        Object configObj = jobMap.get("jobAgentConfig");
        if (!(configObj instanceof Map)) {
            LOGGER.warn("Skipping job ID {}: Missing or invalid 'jobAgentConfig' field.", jobId);
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> jobConfig = (Map<String, Object>) configObj;

        Object jobUrlObj = jobConfig.get("jobUrl");
        if (!(jobUrlObj instanceof String)) {
            LOGGER.warn("Skipping job ID {}: Missing or invalid 'jobUrl' field type in jobAgentConfig.", jobId);
            return null;
        }
        String jobUrl = (String) jobUrlObj;
        if (jobUrl.isBlank()) {
            LOGGER.warn("Skipping job ID {}: Blank 'jobUrl' in jobAgentConfig.", jobId);
            return null;
        }

        if (extractJobNameFromUrl(jobUrl) == null) {
            LOGGER.warn("Skipping job ID {}: Invalid Jenkins job URL format: {}", jobId, jobUrl);
            return null;
        }

        return new JobInfo(jobId, jobUUID, jobUrl);
    }

    /**
     * Triggers the corresponding Jenkins job for a Ctrlplane job.
     * Handles validation, job lookup, parameter creation, and queuing.
     * Updates stats and Ctrlplane status based on success/failure.
     *
     * @param jobInfo The Ctrlplane job information
     * @param stats Statistics object to track processing results
     */
    private void triggerJenkinsJob(JobInfo jobInfo, JobProcessingStats stats) {
        String jenkinsJobName = extractJobNameFromUrl(jobInfo.jobUrl);
        if (jenkinsJobName == null) {
            LOGGER.error("Internal error: Jenkins job name is null after validation for URL: {}", jobInfo.jobUrl);
            updateCtrlplaneJobStatus(
                    jobInfo.jobId,
                    jobInfo.jobUUID,
                    "failure",
                    Map.of("message", "Internal error parsing Jenkins job URL"));
            stats.errors++;
            return;
        }

        Job<?, ?> jenkinsItem = Jenkins.get().getItemByFullName(jenkinsJobName, Job.class);
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

        QueueTaskFuture<?> future = jenkinsJob.scheduleBuild2(0, paramsAction);
        if (future == null) {
            handleFailedTrigger(jobInfo);
            stats.errors++;
            return;
        }

        Queue.Item scheduledItem = null;
        Queue queue = Jenkins.get().getQueue();
        for (Queue.Item item : queue.getItems(jenkinsJob)) {
            ParametersAction action = item.getAction(ParametersAction.class);
            if (action != null) {
                ParameterValue param = action.getParameter("JOB_ID");
                if (param instanceof StringParameterValue
                        && jobInfo.jobId.equals(((StringParameterValue) param).getValue())) {
                    scheduledItem = item;
                    break;
                }
            }
        }

        if (scheduledItem == null) {
            LOGGER.error(
                    "Failed to find matching Queue.Item for job '{}', Ctrlplane job ID {}, even though scheduleBuild2 returned a future.",
                    jenkinsJobName,
                    jobInfo.jobId);
            handleFailedTrigger(jobInfo);
            stats.errors++;
            return;
        }

        ActiveJobInfo activeJob =
                new ActiveJobInfo(jobInfo.jobUUID, jenkinsJobName, scheduledItem.getId(), jobInfo.jobUrl);
        activeJenkinsJobs.put(jobInfo.jobId, activeJob);
        handleSuccessfulTrigger(jobInfo, -1);
        stats.triggered++;
    }

    /**
     * Handles errors caught during the processing loop for a single job.
     * Updates stats and attempts to update Ctrlplane status to failure.
     */
    private void handleJobError(Map<String, Object> jobMap, Exception e, JobProcessingStats stats) {
        // Log the primary error and increment stats immediately
        String initialJobIdGuess = "unknown"; // Best guess for logging before validation
        Object initialIdObj = jobMap.get("id");
        if (initialIdObj instanceof String) {
            initialJobIdGuess = (String) initialIdObj;
        }
        LOGGER.error("Error processing Ctrlplane job ID '{}': {}", initialJobIdGuess, e.getMessage(), e);
        stats.errors++;

        Object idObj = jobMap.get("id");
        if (!(idObj instanceof String)) {
            LOGGER.error("Cannot update error status: Job ID is missing or not a String in the job map.");
            return;
        }

        String jobId = (String) idObj;
        if (jobId.isBlank()) {
            LOGGER.error("Cannot update error status: Job ID is blank.");
            return;
        }

        UUID jobUUID;
        try {
            jobUUID = UUID.fromString(jobId);
        } catch (IllegalArgumentException idEx) {
            LOGGER.error("Cannot update error status: Invalid UUID format '{}'.", jobId);
            return; // Return early if UUID is invalid
        }

        updateCtrlplaneJobStatus(
                jobId, jobUUID, "failure", Map.of("message", "Exception during processing: " + e.getMessage()));
    }

    /**
     * Helper method to update Ctrlplane job status. Includes null check for jobAgent.
     * Returns true if the API call was attempted successfully (regardless of API response code).
     * Returns false if jobAgent is null or an exception occurs during the call.
     */
    private boolean updateCtrlplaneJobStatus(
            String ctrlplaneJobId, UUID ctrlplaneJobUUID, String status, Map<String, Object> details) {
        if (jobAgent == null) {
            LOGGER.error("JobAgent not initialized. Cannot update status for job {}", ctrlplaneJobId);
            return false;
        }

        return jobAgent.updateJobStatus(ctrlplaneJobUUID, status, details);
    }

    /** Handler for when the Jenkins job cannot be found. Updates status and logs. */
    private void handleMissingJenkinsJob(JobInfo jobInfo) {
        String jenkinsJobName = extractJobNameFromUrl(jobInfo.jobUrl);
        String msg = "Jenkins job not found: " + (jenkinsJobName != null ? jenkinsJobName : jobInfo.jobUrl);
        LOGGER.warn("{} (Ctrlplane job ID {})", msg, jobInfo.jobId);
        updateCtrlplaneJobStatus(jobInfo.jobId, jobInfo.jobUUID, "failure", Map.of("message", msg));
    }

    /** Handler for when the Jenkins job is not parameterized. Updates status and logs. */
    private void handleNonParameterizedJob(JobInfo jobInfo) {
        String jenkinsJobName = extractJobNameFromUrl(jobInfo.jobUrl);
        String msg = "Jenkins job not parameterizable: " + (jenkinsJobName != null ? jenkinsJobName : jobInfo.jobUrl);
        LOGGER.warn("{} (Ctrlplane job ID {})", msg, jobInfo.jobId);
        updateCtrlplaneJobStatus(jobInfo.jobId, jobInfo.jobUUID, "failure", Map.of("message", msg));
    }

    /** Handler for logging successful scheduling. */
    private void handleSuccessfulTrigger(JobInfo jobInfo, int buildNumber) {
        if (buildNumber > 0) {
            LOGGER.info(
                    "Successfully scheduled Jenkins job '{}' (build #{}) for Ctrlplane job ID {}",
                    jobInfo.jobUrl,
                    buildNumber,
                    jobInfo.jobId);
            return;
        }

        LOGGER.info(
                "Successfully scheduled Jenkins job '{}' (Ctrlplane job ID {}) - waiting in queue.",
                jobInfo.jobUrl,
                jobInfo.jobId);
    }

    /** Handler for when scheduleBuild2 returns null or queue item cannot be found/retrieved. Updates status and logs. */
    private void handleFailedTrigger(JobInfo jobInfo) {
        String msg = "Failed to schedule Jenkins job '" + jobInfo.jobUrl + "' or retrieve queue item ID.";
        LOGGER.error("{} (Ctrlplane job ID {})", msg, jobInfo.jobId);
        updateCtrlplaneJobStatus(jobInfo.jobId, jobInfo.jobUUID, "failure", Map.of("message", msg));
    }

    /**
     * Factory method for creating the JobAgent. Can be overridden for testing.
     * Throws IllegalStateException if required configuration is missing.
     */
    protected JobAgent createJobAgent(String apiUrl, String apiKey, String agentName, String agentWorkspaceId) {
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Cannot create JobAgent: API URL or API Key is missing.");
        }
        return new JobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
    }

    /**
     * Extracts the Jenkins job name from a Jenkins job URL.
     * Returns null and logs a warning if the URL is invalid or cannot be parsed.
     * Uses early returns for validation.
     */
    protected String extractJobNameFromUrl(String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) {
            LOGGER.warn("Cannot extract job name from null or blank URL.");
            return null;
        }

        java.net.URL url;
        try {
            url = new java.net.URL(jobUrl);
        } catch (java.net.MalformedURLException e) {
            LOGGER.warn("Invalid URL format, cannot parse job name: {}", jobUrl, e);
            return null;
        }

        String path = url.getPath();
        if (path == null || path.isBlank() || !path.contains("/job/")) {
            LOGGER.warn("URL path is invalid or does not contain '/job/': {}", jobUrl);
            return null;
        }

        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        int jobSegmentIndex = path.indexOf("/job/");

        path = path.substring(jobSegmentIndex);

        if (!path.startsWith("/job/")) {
            LOGGER.error("Internal logic error: Path processing failed for URL {}", jobUrl);
            return null;
        }

        String jobPath = path.substring(5);
        if (jobPath.isBlank()) {
            LOGGER.warn("Extracted blank job path component from URL: {}", jobUrl);
            return null;
        }

        String fullName = jobPath.replace("/job/", "/");
        if (fullName.isBlank() || fullName.contains("//") || fullName.startsWith("/") || fullName.endsWith("/")) {
            LOGGER.warn("Extracted invalid full job name '{}' from URL: {}", fullName, jobUrl);
            return null;
        }

        return fullName;
    }

    /** Configuration details for the poller. */
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

        /** Validates that essential configuration fields are present. */
        boolean validate() {
            if (apiUrl == null || apiUrl.isBlank()) {
                LOGGER.error("Ctrlplane API URL not configured. Skipping polling cycle.");
                return false;
            }
            if (apiKey == null || apiKey.isBlank()) {
                LOGGER.error("Ctrlplane API key not configured. Skipping polling cycle.");
                return false;
            }
            if (agentName == null || agentName.isBlank()) {
                LOGGER.error("Ctrlplane Agent ID (Name) not configured. Skipping polling cycle.");
                return false;
            }
            if (agentWorkspaceId == null || agentWorkspaceId.isBlank()) {
                LOGGER.warn("Ctrlplane Agent Workspace ID not configured. Agent registration might fail.");
            }
            return true;
        }
    }

    /** Information about a job received from Ctrlplane. */
    public static class JobInfo {
        final String jobId;
        final UUID jobUUID;
        final String jobUrl;

        JobInfo(String jobId, UUID jobUUID, String jobUrl) {
            this.jobId = jobId;
            this.jobUUID = jobUUID;
            this.jobUrl = jobUrl;
        }
    }

    /** Information needed to track an active Jenkins job triggered by Ctrlplane. */
    private static class ActiveJobInfo {
        final UUID ctrlplaneJobUUID;
        final String jenkinsJobName;
        final String jobUrl;
        final long queueId;
        int jenkinsBuildNumber = -1;

        ActiveJobInfo(UUID ctrlplaneJobUUID, String jenkinsJobName, long queueId, String jobUrl) {
            this.ctrlplaneJobUUID = ctrlplaneJobUUID;
            this.jenkinsJobName = jenkinsJobName;
            this.queueId = queueId;
            this.jobUrl = jobUrl;
        }
    }

    /** Statistics for a single polling cycle. */
    public static class JobProcessingStats {
        int triggered = 0;
        int skipped = 0;
        int errors = 0;
    }

    /**
     * Builds the details map for sending completion status to Ctrlplane,
     * including the message, externalId, and constructed Jenkins links.
     */
    private Map<String, Object> buildCompletionDetails(ActiveJobInfo activeJob, Run<?, ?> run, String message) {
        Map<String, Object> details = new HashMap<>();
        String buildNumberStr = String.valueOf(run.getNumber());
        details.put("message", message);
        details.put("externalId", buildNumberStr);

        if (activeJob.jobUrl == null || activeJob.jobUrl.isBlank() || buildNumberStr.isBlank()) {
            LOGGER.warn(
                    "Cannot construct Jenkins links for job UUID {}: Missing original jobUrl ('{}') or build number ('{}')",
                    activeJob.ctrlplaneJobUUID,
                    activeJob.jobUrl,
                    buildNumberStr);
            return details;
        }

        String baseUrl = activeJob.jobUrl.endsWith("/") ? activeJob.jobUrl : activeJob.jobUrl + "/";
        String statusUrl = baseUrl + buildNumberStr + "/";
        String consoleUrl = statusUrl + "console";

        Map<String, String> links = Map.of("Status", statusUrl, "Logs", consoleUrl);
        details.put("ctrlplane/links", links);

        return details;
    }

    /**
     * Updates the Ctrlplane job status immediately after triggering/queuing in Jenkins
     * to provide an initial link to the main Jenkins job page.
     *
     * @param jobInfo Information about the Ctrlplane job.
     */
    private void updateJobStatusWithInitialLink(JobInfo jobInfo) {
        ActiveJobInfo activeJob = activeJenkinsJobs.get(jobInfo.jobId);
        if (activeJob == null) {
            LOGGER.warn(
                    "Could not find ActiveJobInfo for {} immediately after triggering to add initial link.",
                    jobInfo.jobId);
            return;
        }

        if (activeJob.jobUrl == null || activeJob.jobUrl.isBlank()) {
            LOGGER.warn("ActiveJobInfo for {} is missing the jobUrl, cannot add initial link.", jobInfo.jobId);
            return;
        }

        Map<String, String> initialLinks = Map.of("Job", activeJob.jobUrl);
        Map<String, Object> queuedDetails = new HashMap<>();
        queuedDetails.put("message", "Jenkins job queued"); // Update message to reflect queued status
        queuedDetails.put("ctrlplane/links", initialLinks);
        // Note: externalId (build number) is not known yet.

        // Update status again, keeping it in_progress, but adding the link
        updateCtrlplaneJobStatus(jobInfo.jobId, jobInfo.jobUUID, "in_progress", queuedDetails);
    }
}
