package io.jenkins.plugins.ctrlplane;

import static org.junit.Assert.*;

import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlNumberInput;
import org.htmlunit.html.HtmlTextInput;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsSessionRule;

public class CtrlplaneGlobalConfigurationTest {

    @Rule
    public JenkinsSessionRule sessions = new JenkinsSessionRule();

    /**
     * Tries to exercise enough code paths to catch common mistakes:
     * <ul>
     * <li>missing {@code load}
     * <li>missing {@code save}
     * <li>misnamed or absent getter/setter
     * <li>misnamed {@code textbox}
     * <li>misnamed {@code number}
     * </ul>
     */
    @Test
    public void uiAndStorage() throws Throwable {
        sessions.then(r -> {
            assertEquals(
                    "default API URL is set initially",
                    CtrlplaneGlobalConfiguration.DEFAULT_API_URL,
                    CtrlplaneGlobalConfiguration.get().getApiUrl());
            assertNull(
                    "API key not set initially",
                    CtrlplaneGlobalConfiguration.get().getApiKey());
            assertEquals(
                    "Default Agent ID is set initially",
                    CtrlplaneGlobalConfiguration.DEFAULT_AGENT_ID,
                    CtrlplaneGlobalConfiguration.get().getAgentId());
            assertNull(
                    "Agent Workspace ID not set initially",
                    CtrlplaneGlobalConfiguration.get().getAgentWorkspaceId());
            assertEquals(
                    "Default Polling Interval is set initially",
                    CtrlplaneGlobalConfiguration.DEFAULT_POLLING_INTERVAL_SECONDS,
                    CtrlplaneGlobalConfiguration.get().getPollingIntervalSeconds());

            HtmlForm config = r.createWebClient().goTo("configure").getFormByName("config");

            HtmlTextInput apiUrlBox = config.getInputByName("_.apiUrl");
            apiUrlBox.setText("https://api.example.com");

            HtmlTextInput apiKeyBox = config.getInputByName("_.apiKey");
            apiKeyBox.setText("test-api-key");

            HtmlTextInput agentIdBox = config.getInputByName("_.agentId");
            agentIdBox.setText("test-agent");

            HtmlTextInput agentWorkspaceIdBox = config.getInputByName("_.agentWorkspaceId");
            agentWorkspaceIdBox.setText("test-workspace");

            HtmlNumberInput pollingIntervalBox = config.getInputByName("_.pollingIntervalSeconds");
            pollingIntervalBox.setText("30");

            r.submit(config);

            assertEquals(
                    "API URL was updated",
                    "https://api.example.com",
                    CtrlplaneGlobalConfiguration.get().getApiUrl());
            assertEquals(
                    "API key was updated",
                    "test-api-key",
                    CtrlplaneGlobalConfiguration.get().getApiKey());
            assertEquals(
                    "Agent ID was updated",
                    "test-agent",
                    CtrlplaneGlobalConfiguration.get().getAgentId());
            assertEquals(
                    "Agent Workspace ID was updated",
                    "test-workspace",
                    CtrlplaneGlobalConfiguration.get().getAgentWorkspaceId());
            assertEquals(
                    "Polling Interval was updated",
                    30,
                    CtrlplaneGlobalConfiguration.get().getPollingIntervalSeconds());
        });

        sessions.then(r -> {
            assertEquals(
                    "API URL still there after restart",
                    "https://api.example.com",
                    CtrlplaneGlobalConfiguration.get().getApiUrl());
            assertEquals(
                    "API key still there after restart",
                    "test-api-key",
                    CtrlplaneGlobalConfiguration.get().getApiKey());
            assertEquals(
                    "Agent ID still there after restart",
                    "test-agent",
                    CtrlplaneGlobalConfiguration.get().getAgentId());
            assertEquals(
                    "Agent Workspace ID still there after restart",
                    "test-workspace",
                    CtrlplaneGlobalConfiguration.get().getAgentWorkspaceId());
            assertEquals(
                    "Polling Interval still there after restart",
                    30,
                    CtrlplaneGlobalConfiguration.get().getPollingIntervalSeconds());
        });
    }
}
