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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for Jenkins job completions and updates the Ctrlplane job status accordingly.
 */
@Extension
public class CtrlplaneJobCompletionListener extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CtrlplaneJobCompletionListener.class);

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        if (run == null) {
            LOGGER.warn("Received null Run in onCompleted, skipping");
            return;
        }

        // Extract Ctrlplane JOB_ID parameter if present
        String ctrlplaneJobId = extractJobId(run);
        if (ctrlplaneJobId == null) {
            // This job wasn't triggered by Ctrlplane, so we don't need to update anything
            return;
        }

        try {
            UUID jobUUID = UUID.fromString(ctrlplaneJobId);

            // Get job status based on Jenkins build result
            String status = getCtrlplaneStatusFromResult(run);

            // Create API client and update job status
            JobAgent jobAgent = createJobAgent();
            if (jobAgent != null) {
                Map<String, Object> details = new HashMap<>();

                // Safely get result string
                Result result = run.getResult();
                String resultString = (result != null) ? result.toString() : "UNKNOWN";

                details.put(
                        "message",
                        "Jenkins job " + run.getFullDisplayName() + " completed with result: " + resultString);
                details.put("externalId", String.valueOf(run.getNumber()));

                if (jobAgent.updateJobStatus(jobUUID, status, details)) {
                    LOGGER.info(
                            "Successfully updated Ctrlplane job {} status to {} after Jenkins job {} completed",
                            ctrlplaneJobId,
                            status,
                            run.getFullDisplayName());
                } else {
                    LOGGER.error(
                            "Failed to update Ctrlplane job {} status after Jenkins job {} completed",
                            ctrlplaneJobId,
                            run.getFullDisplayName());
                }
            }
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid Ctrlplane job ID format: {}", ctrlplaneJobId, e);
        } catch (Exception e) {
            LOGGER.error("Error updating Ctrlplane job status for job ID {}: {}", ctrlplaneJobId, e.getMessage(), e);
        }
    }

    /**
     * Maps Jenkins build result to Ctrlplane job status.
     */
    private String getCtrlplaneStatusFromResult(Run<?, ?> run) {
        if (run == null) {
            return "failure"; // Treat null run as failure
        }

        Result result = run.getResult();
        if (result == null) {
            return "in_progress"; // Should ideally not happen in onCompleted
        }

        String resultString = result.toString();

        switch (resultString) {
            case "SUCCESS":
                return "successful";
            case "UNSTABLE":
                return "successful"; // Consider unstable builds as successful but with warnings
            case "FAILURE":
                return "failure";
            case "ABORTED":
                return "cancelled";
            default:
                return "failure"; // Default to failure for unknown states
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

        // Config should never be null as it's a singleton, but let's be defensive
        String apiUrl = config.getApiUrl();
        String apiKey = config.getApiKey();
        String agentName = config.getAgentId(); // This is the agent name/id in the config
        String agentWorkspaceId = config.getAgentWorkspaceId();

        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            LOGGER.error("Ctrlplane API URL or API key not configured");
            return null;
        }

        return new JobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
    }
}
