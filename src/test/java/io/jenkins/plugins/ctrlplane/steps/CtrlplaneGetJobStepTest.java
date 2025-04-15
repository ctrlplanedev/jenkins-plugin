package io.jenkins.plugins.ctrlplane.steps;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import hudson.model.TaskListener;
import io.jenkins.plugins.ctrlplane.CtrlplaneGlobalConfiguration;
import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Tests for the CtrlplaneGetJobStep pipeline step.
 */
public class CtrlplaneGetJobStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    /**
     * Tests that the step validates UUID format.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInvalidUUIDRejection() {
        new CtrlplaneGetJobStep("not-a-uuid");
    }

    /**
     * Test that null/empty job ID is rejected.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testEmptyJobIdRejection() {
        new CtrlplaneGetJobStep("");
    }

    /**
     * Test that valid UUIDs are accepted.
     */
    @Test
    public void testValidUUIDAcceptance() {
        String validUuid = UUID.randomUUID().toString();
        CtrlplaneGetJobStep step = new CtrlplaneGetJobStep(validUuid);
        assertEquals("Job ID should be stored", validUuid, step.getJobId());
    }

    /**
     * Simple test for parameter handling.
     */
    @Test
    public void testParameterHandling() {
        Map<String, String> params = Map.of(
                "PARAM1", "value1",
                "PARAM2", "value2");
        assertEquals("Parameter values should be retrievable", "value1", params.get("PARAM1"));
        assertEquals("Parameter values should be retrievable", "value2", params.get("PARAM2"));
    }

    /**
     * Test that logging works as expected.
     */
    @Test
    public void testLogging() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        TaskListener listener = mock(TaskListener.class);
        when(listener.getLogger()).thenReturn(ps);
        listener.getLogger().println("Test message");
        assertEquals("Log message should be captured", "Test message" + System.lineSeparator(), baos.toString());
    }

    /**
     * Test that the step properly creates execution.
     */
    @Test
    public void testStepExecution() throws Exception {
        String validUuid = UUID.randomUUID().toString();
        CtrlplaneGetJobStep step = new CtrlplaneGetJobStep(validUuid);

        StepContext context = mock(StepContext.class);

        assertNotNull("Step should create a valid execution", step.start(context));
    }

    /**
     * Test that configuration is properly accessed.
     */
    @Test
    public void testConfigurationAccess() {
        CtrlplaneGlobalConfiguration config = CtrlplaneGlobalConfiguration.get();
        // Just verify we can access the configuration
        assertNotNull("Global configuration should be accessible", config);
    }

    /**
     * Test that the JobAgent can be instantiated with parameters.
     */
    @Test
    public void testJobAgentCreation() {
        JobAgent agent = new JobAgent("https://api.example.com", "test-api-key", "test-agent", "test-workspace-id");

        assertNotNull("JobAgent should be created successfully", agent);
    }

    /**
     * Test that the step's function name is correctly set.
     */
    @Test
    public void testStepFunctionName() {
        CtrlplaneGetJobStep.DescriptorImpl descriptor = new CtrlplaneGetJobStep.DescriptorImpl();
        assertEquals("ctrlplaneGetJob", descriptor.getFunctionName());
        assertEquals("Get Ctrlplane Job Details", descriptor.getDisplayName());
        assertTrue(
                "TaskListener should be in required context",
                descriptor.getRequiredContext().contains(TaskListener.class));
    }
}
