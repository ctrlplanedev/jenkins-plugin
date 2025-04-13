package io.jenkins.plugins.ctrlplane;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.jenkins.plugins.ctrlplane.CtrlplaneJobPoller.JobInfo;
import io.jenkins.plugins.ctrlplane.api.JobAgent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CtrlplaneJobPollerMockTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Mock
    private JobAgent jobAgent;

    // Simple subclass that exposes protected methods for testing
    private static class TestableCtrlplaneJobPoller extends CtrlplaneJobPoller {
        private final JobAgent mockJobAgent;

        public TestableCtrlplaneJobPoller(JobAgent mockJobAgent) {
            this.mockJobAgent = mockJobAgent;
        }

        @Override
        protected JobAgent createJobAgent(String apiUrl, String apiKey, String agentName, String agentWorkspaceId) {
            return mockJobAgent;
        }

        // Helper method for testing external ID updates
        public void updateJobStatusWithExternalId(JobInfo jobInfo, String status, String trigger, String externalId) {
            Map<String, Object> details = new HashMap<>();
            details.put("trigger", trigger);
            details.put("externalId", externalId);
            jobAgent.updateJobStatus(jobInfo.jobUUID, status, details);
        }
    }

    private TestableCtrlplaneJobPoller jobPoller;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        jobPoller = new TestableCtrlplaneJobPoller(jobAgent);

        // Initialize the job agent field explicitly
        jobPoller.jobAgent = jobAgent;
    }

    @Test
    public void testExtractJobNameFromUrl() {
        // Test basic URL parsing
        assertEquals("simple-job", jobPoller.extractJobNameFromUrl("http://jenkins-server/job/simple-job/"));
        assertEquals(
                "folder/subfolder/job",
                jobPoller.extractJobNameFromUrl("http://jenkins-server/job/folder/job/subfolder/job/job/"));
        assertNull(jobPoller.extractJobNameFromUrl("http://not-a-jenkins-url"));
    }

    @Test
    public void testJobStatusUpdating() {
        // Given
        String jobId = "test-job-id";
        UUID jobUUID = UUID.randomUUID();
        String jobUrl = "http://jenkins-server/job/test-job/";
        JobInfo jobInfo = new JobInfo(jobId, jobUUID, jobUrl);
        String externalId = "123";

        // When
        Map<String, Object> expectedDetails = new HashMap<>();
        expectedDetails.put("trigger", "Test");
        expectedDetails.put("externalId", externalId);
        when(jobAgent.updateJobStatus(eq(jobUUID), eq("in_progress"), eq(expectedDetails)))
                .thenReturn(true);
        jobPoller.updateJobStatusWithExternalId(jobInfo, "in_progress", "Test", externalId);

        // Then
        verify(jobAgent).updateJobStatus(eq(jobUUID), eq("in_progress"), eq(expectedDetails));
    }

    @Test
    public void testExtractJobInfo() {
        // Create test job data
        Map<String, Object> jobConfig = new HashMap<>();
        jobConfig.put("jobUrl", "http://jenkins-server/job/test-job/");

        Map<String, Object> jobMap = new HashMap<>();
        String jobId = UUID.randomUUID().toString();
        jobMap.put("id", jobId);
        jobMap.put("status", "pending");
        jobMap.put("jobAgentConfig", jobConfig);

        // Extract job info
        JobInfo jobInfo = jobPoller.extractJobInfo(jobMap);

        // Verify
        assertNotNull("JobInfo should not be null", jobInfo);
        assertEquals("Job ID should match", jobId, jobInfo.jobId);
        assertEquals("Job URL should match", "http://jenkins-server/job/test-job/", jobInfo.jobUrl);
    }

    @Test
    public void testExtractJobInfo_SkipNonPendingJobs() {
        // Create test job data with non-pending status
        Map<String, Object> jobConfig = new HashMap<>();
        jobConfig.put("jobUrl", "http://jenkins-server/job/test-job/");

        Map<String, Object> jobMap = new HashMap<>();
        String jobId = UUID.randomUUID().toString();
        jobMap.put("id", jobId);
        jobMap.put("status", "in_progress"); // Non-pending status
        jobMap.put("jobAgentConfig", jobConfig);

        // Extract job info should return null for non-pending jobs
        JobInfo jobInfo = jobPoller.extractJobInfo(jobMap);

        // Verify
        assertNull("JobInfo should be null for non-pending jobs", jobInfo);
    }

    @Test
    public void testJobStatusUpdateWithExternalId() {
        // Given
        String jobId = "test-job-id";
        UUID jobUUID = UUID.randomUUID();
        String jobUrl = "http://jenkins-server/job/test-job/";
        JobInfo jobInfo = new JobInfo(jobId, jobUUID, jobUrl);
        String externalId = "123";

        // Capture the arguments passed to updateJobStatus
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);

        // When - use our test helper to update the status with external ID
        when(jobAgent.updateJobStatus(eq(jobUUID), eq("in_progress"), detailsCaptor.capture()))
                .thenReturn(true);
        jobPoller.updateJobStatusWithExternalId(jobInfo, "in_progress", "Test", externalId);

        // Then
        verify(jobAgent).updateJobStatus(eq(jobUUID), eq("in_progress"), detailsCaptor.capture());

        // Get the captured details map
        Map<String, Object> capturedDetails = detailsCaptor.getValue();

        // Verify details contain trigger and externalId
        assertNotNull("Details map should not be null", capturedDetails);
        assertTrue("Details should contain trigger", capturedDetails.containsKey("trigger"));
        assertEquals("Trigger should match", "Test", capturedDetails.get("trigger"));
        assertTrue("Details should contain externalId", capturedDetails.containsKey("externalId"));
        assertEquals("ExternalId should match", externalId, capturedDetails.get("externalId"));
    }
}
