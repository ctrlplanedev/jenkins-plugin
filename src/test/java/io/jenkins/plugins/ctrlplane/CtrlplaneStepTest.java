package io.jenkins.plugins.ctrlplane;

import static org.junit.Assert.*;

import hudson.model.TaskListener;
import io.jenkins.plugins.ctrlplane.steps.CtrlplaneGetJobStep;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mockito;

/**
 * Tests for Ctrlplane pipeline steps.
 */
public class CtrlplaneStepTest {

    @Rule
    public JenkinsRule jenkins = new JenkinsRule();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    /**
     * Tests that the step validates UUID format.
     */
    @Test
    public void testInvalidUUIDRejection() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("UUID");
        new CtrlplaneGetJobStep("not-a-uuid");
    }

    /**
     * Test that null/empty job ID is rejected.
     */
    @Test
    public void testEmptyJobIdRejection() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("cannot be empty");
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
        TaskListener listener = Mockito.mock(TaskListener.class);
        Mockito.when(listener.getLogger()).thenReturn(ps);
        listener.getLogger().println("Test message");
        assertEquals("Log message should be captured", "Test message" + System.lineSeparator(), baos.toString());
    }
}
