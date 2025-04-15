package io.jenkins.plugins.ctrlplane;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.util.FormValidation;
import java.util.UUID;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Global configuration for the Ctrlplane Agent plugin.
 */
@Extension
public class CtrlplaneGlobalConfiguration extends GlobalConfiguration {
    public static final String DEFAULT_API_URL = "https://app.ctrlplane.dev";
    public static final String DEFAULT_AGENT_ID = "jenkins-agent";
    public static final int DEFAULT_POLLING_INTERVAL_SECONDS = 60;

    /** @return the singleton instance */
    public static CtrlplaneGlobalConfiguration get() {
        return ExtensionList.lookupSingleton(CtrlplaneGlobalConfiguration.class);
    }

    private String apiUrl;
    private String apiKey;
    private String agentId;
    private String agentWorkspaceId;
    private int pollingIntervalSeconds;

    public CtrlplaneGlobalConfiguration() {
        load();
        if (StringUtils.isBlank(apiUrl)) {
            apiUrl = DEFAULT_API_URL;
        }
        if (pollingIntervalSeconds <= 0) {
            pollingIntervalSeconds = DEFAULT_POLLING_INTERVAL_SECONDS;
        }
    }

    /** @return the currently configured API URL, or default if not set */
    public String getApiUrl() {
        return StringUtils.isBlank(apiUrl) ? DEFAULT_API_URL : apiUrl;
    }

    /**
     * Sets the API URL
     * @param apiUrl the new API URL
     */
    @DataBoundSetter
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        save();
    }

    /** @return the currently configured API key */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Sets the API key
     * @param apiKey the new API key
     */
    @DataBoundSetter
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        save();
    }

    /** @return the currently configured agent ID */
    public String getAgentId() {
        return StringUtils.isBlank(agentId) ? DEFAULT_AGENT_ID : agentId;
    }

    /**
     * Sets the agent ID
     * @param agentId the new agent ID
     */
    @DataBoundSetter
    public void setAgentId(String agentId) {
        this.agentId = agentId;
        save();
    }

    /** @return the currently configured agent workspace ID */
    public String getAgentWorkspaceId() {
        return agentWorkspaceId;
    }

    /**
     * Sets the agent workspace ID
     * @param agentWorkspaceId the new agent workspace ID
     */
    @DataBoundSetter
    public void setAgentWorkspaceId(String agentWorkspaceId) {
        this.agentWorkspaceId = agentWorkspaceId;
        save();
    }

    /** @return the currently configured polling interval in seconds */
    public int getPollingIntervalSeconds() {
        return pollingIntervalSeconds > 0 ? pollingIntervalSeconds : DEFAULT_POLLING_INTERVAL_SECONDS;
    }

    /**
     * Sets the polling interval.
     * @param pollingIntervalSeconds The new interval in seconds.
     */
    @DataBoundSetter
    public void setPollingIntervalSeconds(int pollingIntervalSeconds) {
        this.pollingIntervalSeconds = Math.max(10, pollingIntervalSeconds);
        save();
    }

    /**
     * Validates the API URL field from the configuration form.
     *
     * @param value The API URL to validate
     * @return FormValidation result
     */
    @POST
    public FormValidation doCheckApiUrl(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (StringUtils.isEmpty(value)) {
            return FormValidation.error("API URL cannot be empty.");
        }
        return FormValidation.ok();
    }

    /**
     * Validates the API Key field from the configuration form.
     *
     * @param value The API key to validate
     * @return FormValidation result
     */
    @POST
    public FormValidation doCheckApiKey(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (StringUtils.isEmpty(value)) {
            return FormValidation.warning("API Key is required for the agent to poll for jobs.");
        }
        return FormValidation.ok();
    }

    /**
     * Validates the Agent ID field from the configuration form.
     *
     * @param value The agent ID to validate
     * @return FormValidation result
     */
    @POST
    public FormValidation doCheckAgentId(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (StringUtils.isEmpty(value)) {
            return FormValidation.warning("Agent ID is recommended for easier identification in Ctrlplane.");
        }
        return FormValidation.ok();
    }

    /**
     * Validates the polling interval field from the configuration form.
     *
     * @param value The polling interval to validate
     * @return FormValidation result
     */
    @POST
    public FormValidation doCheckPollingIntervalSeconds(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (StringUtils.isEmpty(value)) {
            return FormValidation.error("Polling Interval cannot be empty.");
        }
        try {
            int interval = Integer.parseInt(value);
            if (interval < 10) {
                return FormValidation.error("Polling interval must be at least 10 seconds.");
            }
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error("Polling interval must be a valid integer.");
        }
    }

    public FormValidation doCheckAgentWorkspaceId(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (StringUtils.isEmpty(value)) {
            return FormValidation.warning("Agent Workspace ID is required for the agent to identify itself.");
        }
        try {
            UUID.fromString(value);
            return FormValidation.ok();
        } catch (IllegalArgumentException e) {
            return FormValidation.error(
                    "Invalid format: Agent Workspace ID must be a valid UUID (e.g., 123e4567-e89b-12d3-a456-426614174000).");
        }
    }
}
