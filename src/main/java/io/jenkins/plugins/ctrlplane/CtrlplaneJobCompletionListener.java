package io.jenkins.plugins.ctrlplane;

import hudson.Extension;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for Jenkins job completions and updates the corresponding Ctrlplane job status.
 * This provides faster status updates but is less robust against Jenkins restarts
 * compared to the poller's reconciliation loop.
 */
@Extension
public class CtrlplaneJobCompletionListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CtrlplaneJobCompletionListener.class);

    /**
     * Called when a Jenkins job completes.
     *
     * @param run      The completed run.
     * @param listener The task listener.
     */
    @Override
    public void onCompleted(Run<?, ?> run, @Nonnull TaskListener listener) {
        LOGGER.debug("onCompleted triggered for run: {}", run.getFullDisplayName());

        /**
         * Extract Ctrlplane Job ID from parameters
         */
        String ctrlplaneJobId = extractJobId(run);
        if (ctrlplaneJobId == null) {
            LOGGER.debug(
                    "No Ctrlplane Job ID found for run {}, likely not a Ctrlplane-triggered job.",
                    run.getFullDisplayName());
            return;
        }

        UUID jobUUID;
        try {
            jobUUID = UUID.fromString(ctrlplaneJobId);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid Ctrlplane job ID format found in parameter: {}", ctrlplaneJobId, e);
            return;
        }

        /**
         * Get job status based on Jenkins build result
         */
        String status = getCtrlplaneStatusFromResult(run);

        /**
         * Create API client
         */
        JobAgent jobAgent = createJobAgent();
        if (jobAgent == null) {
            /**
             * createJobAgent logs the reason (config missing)
             */
            LOGGER.error("Cannot update Ctrlplane status for job {}: JobAgent could not be created.", ctrlplaneJobId);
            return;
        }

        /**
         * Prepare details for the update
         */
        Map<String, Object> details = new HashMap<>();
        Result result = run.getResult();
        String resultString = (result != null) ? result.toString() : "UNKNOWN";
        details.put("message", "Jenkins job " + run.getFullDisplayName() + " completed with result: " + resultString);
        details.put("externalId", String.valueOf(run.getNumber()));
        boolean success = jobAgent.updateJobStatus(jobUUID, status, details);

        if (success) {
            LOGGER.info(
                    "Successfully updated Ctrlplane job {} status to {} via listener after Jenkins job {} completed",
                    ctrlplaneJobId,
                    status,
                    run.getFullDisplayName());
        } else {
            LOGGER.error(
                    "Failed attempt to update Ctrlplane job {} status via listener after Jenkins job {} completed (check JobAgent logs for API errors)",
                    ctrlplaneJobId,
                    run.getFullDisplayName());
        }
    }

    /**
     * Maps Jenkins build result to Ctrlplane job status.
     *
     * @param run The Jenkins run.
     * @return The corresponding Ctrlplane status string ("successful", "failure", "cancelled", "in_progress").
     */
    private String getCtrlplaneStatusFromResult(Run<?, ?> run) {
        if (run == null) {
            return "failure";
        }

        Result result = run.getResult();
        if (result == null) {
            LOGGER.warn(
                    "Run {} completed but getResult() returned null. Reporting as failure.", run.getFullDisplayName());
            return "failure";
        }

        String resultString = result.toString();

        switch (resultString) {
            case "SUCCESS":
            case "UNSTABLE": // Treat UNSTABLE as successful for Ctrlplane status
                return "successful";
            case "FAILURE":
                return "failure";
            case "ABORTED":
                return "cancelled";
            default:
                LOGGER.warn(
                        "Unknown Jenkins result '{}' for run {}, reporting as failure.",
                        resultString,
                        run.getFullDisplayName());
                return "failure";
        }
    }

    /**
     * Extracts the Ctrlplane JOB_ID parameter from a Jenkins run.
     */
    private String extractJobId(Run<?, ?> run) {
        if (run == null) {
            return null;
        }

        ParametersAction parametersAction = run.getAction(ParametersAction.class);
        if (parametersAction != null) {
            ParameterValue jobIdParam = parametersAction.getParameter("JOB_ID");
            if (jobIdParam instanceof StringParameterValue) {
                return ((StringParameterValue) jobIdParam).getValue();
            }
        }
        return null;
    }

    /**
     * Creates a JobAgent with configuration from global settings.
     */
    private JobAgent createJobAgent() {
        CtrlplaneGlobalConfiguration config = CtrlplaneGlobalConfiguration.get();
        String apiUrl = config.getApiUrl();
        String apiKey = config.getApiKey();
        String agentName = config.getAgentId();
        String agentWorkspaceId = config.getAgentWorkspaceId();

        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            LOGGER.error("Ctrlplane API URL or API key not configured");
            return null;
        }

        return new JobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
    }
}
