package io.jenkins.plugins.ctrlplane.steps;

import com.google.common.collect.ImmutableSet;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.TaskListener;
import io.jenkins.plugins.ctrlplane.CtrlplaneGlobalConfiguration;
import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.steps.*;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/** Pipeline step to fetch job details from the Ctrlplane API. */
public class CtrlplaneGetJobStep extends Step {

    private final String jobId;

    /**
     * Constructor for the Ctrlplane Get Job step.
     *
     * @param jobId The UUID of the job to fetch from Ctrlplane
     * @throws IllegalArgumentException if jobId is null, blank, or not a valid UUID
     */
    @DataBoundConstructor
    public CtrlplaneGetJobStep(@Nonnull String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Job ID cannot be empty for ctrlplaneGetJob step.");
        }
        try {
            UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Job ID format (must be UUID): " + jobId, e);
        }
        this.jobId = jobId;
    }

    /**
     * Gets the job ID for this step.
     *
     * @return The job ID as a string
     */
    public String getJobId() {
        return jobId;
    }

    /**
     * Starts the execution of this step.
     *
     * @param context The step context
     * @return A step execution
     * @throws Exception if an error occurs during execution
     */
    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, this);
    }

    /**
     * Inner class that handles the actual execution logic for the step.
     */
    private static class Execution extends SynchronousStepExecution<Map<String, Object>> {

        private static final long serialVersionUID = 1L;

        private final transient CtrlplaneGetJobStep step;

        /**
         * Constructor for the execution.
         *
         * @param context The step context
         * @param step The step being executed
         */
        Execution(StepContext context, CtrlplaneGetJobStep step) {
            super(context);
            this.step = step;
        }

        /**
         * Executes the step and returns the job data.
         *
         * @return A map containing the job data
         * @throws Exception if an error occurs during execution
         */
        @Override
        protected Map<String, Object> run() throws Exception {
            StepContext context = getContext();
            TaskListener listener = context.get(TaskListener.class);
            UUID jobUUID;

            try {
                jobUUID = UUID.fromString(step.getJobId());
            } catch (IllegalArgumentException e) {
                throw new AbortException("Invalid Job ID format passed to step execution: " + step.getJobId());
            }

            listener.getLogger().println("Ctrlplane Step: Fetching job details for " + step.getJobId());

            CtrlplaneGlobalConfiguration config = CtrlplaneGlobalConfiguration.get();
            String apiUrl = config.getApiUrl();
            String apiKey = config.getApiKey();
            String agentName = config.getAgentId();
            String workspaceId = config.getAgentWorkspaceId();
            int pollingIntervalSeconds = config.getPollingIntervalSeconds();

            if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
                throw new AbortException("Ctrlplane API URL or API Key not configured in Jenkins global settings.");
            }

            if (agentName == null || agentName.isBlank()) {
                listener.getLogger().println("Warning: Ctrlplane Agent Name not configured globally.");
                agentName = "jenkins-pipeline-step-agent";
            }
            if (workspaceId == null || workspaceId.isBlank()) {
                listener.getLogger().println("Warning: Ctrlplane Agent Workspace ID not configured globally.");
            }

            JobAgent jobAgent = new JobAgent(apiUrl, apiKey, agentName, workspaceId, pollingIntervalSeconds);

            Map<String, Object> jobData = jobAgent.getJob(jobUUID);

            if (jobData == null) {
                throw new AbortException("Failed to fetch job details from Ctrlplane API for job " + step.getJobId());
            }

            listener.getLogger().println("Ctrlplane Step: Successfully fetched job details.");
            return jobData;
        }
    }

    /**
     * Descriptor for the Ctrlplane Get Job step.
     */
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        /**
         * Gets the function name used in pipeline scripts.
         *
         * @return The function name
         */
        @Override
        public String getFunctionName() {
            return "ctrlplaneGetJob";
        }

        /**
         * Gets the display name shown in UI snippets.
         *
         * @return The display name
         */
        @Nonnull
        @Override
        public String getDisplayName() {
            return "Get Ctrlplane Job Details";
        }

        /**
         * Gets the required context for this step.
         *
         * @return A set of required context classes
         */
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return ImmutableSet.of(TaskListener.class);
        }
    }
}
