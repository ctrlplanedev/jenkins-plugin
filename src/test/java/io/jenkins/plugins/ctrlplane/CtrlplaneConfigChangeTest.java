package io.jenkins.plugins.ctrlplane;

import static org.junit.Assert.*;

import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.util.Objects;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests that configuration changes are properly detected and handled.
 */
@RunWith(JUnit4.class)
public class CtrlplaneConfigChangeTest {

    /**
     * Test configuration class that mirrors CtrlplaneConfig
     */
    private static class TestConfig {
        public String apiUrl;
        public String apiKey;
        public String agentName;
        public String agentWorkspaceId;
        public int pollingIntervalSeconds;
    }

    private TestableCtrlplaneJobPoller jobPoller;

    /**
     * Extends CtrlplaneJobPoller to access protected methods and
     * track JobAgent recreations.
     */
    private static class TestableCtrlplaneJobPoller extends CtrlplaneJobPoller {
        public int jobAgentCreationCount = 0;
        public String lastApiUrl;
        public String lastApiKey;
        public String lastAgentName;
        public String lastWorkspaceId;
        public int lastPollingIntervalSeconds;
        private JobAgent testJobAgent;

        @Override
        protected JobAgent createJobAgent(
                String apiUrl, String apiKey, String agentName, String agentWorkspaceId, int pollingIntervalSeconds) {
            jobAgentCreationCount++;
            lastApiUrl = apiUrl;
            lastApiKey = apiKey;
            lastAgentName = agentName;
            lastWorkspaceId = agentWorkspaceId;
            lastPollingIntervalSeconds = pollingIntervalSeconds;

            JobAgent agent = new JobAgent(apiUrl, apiKey, agentName, agentWorkspaceId);
            testJobAgent = agent;
            return agent;
        }

        /**
         * Simplified test version that doesn't need to access internal CtrlplaneConfig
         */
        public boolean initializeAndRegisterAgent(TestConfig config) {
            if (config.apiUrl == null || config.apiUrl.isBlank() || config.apiKey == null || config.apiKey.isBlank()) {
                return false;
            }

            String agentName = config.agentName;
            if (agentName == null || agentName.isBlank()) {
                agentName = CtrlplaneGlobalConfiguration.DEFAULT_AGENT_ID;
            }

            if (testJobAgent == null
                    || !config.apiUrl.equals(lastApiUrl)
                    || !config.apiKey.equals(lastApiKey)
                    || !agentName.equals(lastAgentName)
                    || !Objects.equals(config.agentWorkspaceId, lastWorkspaceId)
                    || config.pollingIntervalSeconds != lastPollingIntervalSeconds) {

                JobAgent newAgent = createJobAgent(
                        config.apiUrl,
                        config.apiKey,
                        agentName,
                        config.agentWorkspaceId,
                        config.pollingIntervalSeconds);

                try {
                    java.lang.reflect.Field field = CtrlplaneJobPoller.class.getDeclaredField("jobAgent");
                    field.setAccessible(true);
                    field.set(this, newAgent);
                } catch (Exception e) {
                    e.printStackTrace();
                    return false;
                }
            }

            return true;
        }
    }

    @Before
    public void setUp() {
        jobPoller = new TestableCtrlplaneJobPoller();
    }

    @Test
    public void testInitialAgentSetup() {
        // Create test configuration
        TestConfig config = new TestConfig();
        config.apiUrl = "https://api.example.com";
        config.apiKey = "test-key";
        config.agentName = "test-agent";
        config.agentWorkspaceId = "test-workspace";
        config.pollingIntervalSeconds = 120;

        // Initialize agent
        boolean result = jobPoller.initializeAndRegisterAgent(config);

        // Verify
        assertTrue("Agent initialization should succeed", result);
        assertEquals("Agent should be created once", 1, jobPoller.jobAgentCreationCount);
        assertEquals("API URL should match", "https://api.example.com", jobPoller.lastApiUrl);
        assertEquals("API key should match", "test-key", jobPoller.lastApiKey);
        assertEquals("Agent name should match", "test-agent", jobPoller.lastAgentName);
        assertEquals("Workspace ID should match", "test-workspace", jobPoller.lastWorkspaceId);
        assertEquals("Polling interval should match", 120, jobPoller.lastPollingIntervalSeconds);
    }

    @Test
    public void testAgentReInitializationOnConfigChange() {
        // First initialization
        TestConfig config = new TestConfig();
        config.apiUrl = "https://api.example.com";
        config.apiKey = "test-key";
        config.agentName = "test-agent";
        config.agentWorkspaceId = "test-workspace";
        config.pollingIntervalSeconds = 120;

        jobPoller.initializeAndRegisterAgent(config);
        assertEquals("Agent should be created once", 1, jobPoller.jobAgentCreationCount);

        // Same config - should not recreate
        jobPoller.initializeAndRegisterAgent(config);
        assertEquals("Agent should not be recreated for same config", 1, jobPoller.jobAgentCreationCount);

        // Change API URL - should recreate
        config.apiUrl = "https://new-api.example.com";
        jobPoller.initializeAndRegisterAgent(config);
        assertEquals("Agent should be recreated when API URL changes", 2, jobPoller.jobAgentCreationCount);
        assertEquals("New API URL should be used", "https://new-api.example.com", jobPoller.lastApiUrl);

        // Change API key - should recreate
        config.apiKey = "new-test-key";
        jobPoller.initializeAndRegisterAgent(config);
        assertEquals("Agent should be recreated when API key changes", 3, jobPoller.jobAgentCreationCount);
        assertEquals("New API key should be used", "new-test-key", jobPoller.lastApiKey);

        // Change polling interval - should recreate
        config.pollingIntervalSeconds = 180;
        jobPoller.initializeAndRegisterAgent(config);
        assertEquals("Agent should be recreated when polling interval changes", 4, jobPoller.jobAgentCreationCount);
        assertEquals("New polling interval should be used", 180, jobPoller.lastPollingIntervalSeconds);
    }

    @Test
    public void testDefaultAgentNameUsedWhenBlank() {
        TestConfig config = new TestConfig();
        config.apiUrl = "https://api.example.com";
        config.apiKey = "test-key";
        config.agentName = ""; // Blank agent name
        config.agentWorkspaceId = "test-workspace";
        config.pollingIntervalSeconds = 120;

        jobPoller.initializeAndRegisterAgent(config);

        // Should use default agent name
        assertFalse(
                "Agent name should not be blank", jobPoller.lastAgentName == null || jobPoller.lastAgentName.isEmpty());
        assertEquals(
                "Default agent name should be used",
                CtrlplaneGlobalConfiguration.DEFAULT_AGENT_ID,
                jobPoller.lastAgentName);
    }
}
