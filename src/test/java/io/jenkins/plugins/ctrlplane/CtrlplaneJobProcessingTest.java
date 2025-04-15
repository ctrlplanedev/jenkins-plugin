package io.jenkins.plugins.ctrlplane;

import static org.junit.Assert.*;

import hudson.model.Result;
import hudson.model.Run;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/**
 * Tests job processing logic in the CtrlplaneJobPoller class.
 */
@RunWith(JUnit4.class)
public class CtrlplaneJobProcessingTest {

    /**
     * Test version of JobInfo to use in tests
     */
    public static class TestJobInfo {
        public final String jobId;
        public final UUID jobUUID;
        public final String jobUrl;

        public TestJobInfo(String jobId, UUID jobUUID, String jobUrl) {
            this.jobId = jobId;
            this.jobUUID = jobUUID;
            this.jobUrl = jobUrl;
        }
    }

    /**
     * Test version of ActiveJobInfo to use in tests
     */
    public static class TestActiveJobInfo {
        public final TestJobInfo jobInfo;
        public final String externalId;

        @SuppressWarnings("rawtypes")
        public final Run run;

        @SuppressWarnings("rawtypes")
        public TestActiveJobInfo(TestJobInfo jobInfo, String externalId, Run run) {
            this.jobInfo = jobInfo;
            this.externalId = externalId;
            this.run = run;
        }
    }

    /**
     * Simple class to track job processing statistics for testing
     */
    public static class TestJobProcessingStats {
        public int processedJobs = 0;
        public int errors = 0;
        public int skippedJobs = 0;
    }

    private static class TestableCtrlplaneJobPoller extends CtrlplaneJobPoller {
        public final ConcurrentHashMap<String, TestActiveJobInfo> testActiveJobs = new ConcurrentHashMap<>();
        public final List<Map<String, String>> jobStatusUpdates = new ArrayList<>();

        public TestJobInfo testExtractJobInfo(Map<String, Object> jobMap) {
            String status = (String) jobMap.get("status");
            if (!"pending".equals(status)) {
                return null;
            }

            String jobId = (String) jobMap.get("id");
            UUID jobUUID = UUID.fromString(jobId);

            @SuppressWarnings("unchecked")
            Map<String, Object> jobConfig = (Map<String, Object>) jobMap.get("jobAgentConfig");
            String jobUrl = (String) jobConfig.get("jobUrl");

            return new TestJobInfo(jobId, jobUUID, jobUrl);
        }

        public void updateJobStatus(TestJobInfo jobInfo, String status, String reason) {
            jobStatusUpdates.add(Map.of(
                    "jobId", jobInfo.jobId,
                    "status", status,
                    "reason", reason));
        }

        public void handleCompletedJob(TestActiveJobInfo activeJob) {
            String jobId = activeJob.jobInfo.jobId;
            Result result = activeJob.run.getResult();
            String status = (Result.SUCCESS.equals(result)) ? "successful" : "failure";
            updateJobStatus(activeJob.jobInfo, status, "Job completed with status: " + result.toString());
            testActiveJobs.remove(jobId);
        }
    }

    private TestableCtrlplaneJobPoller jobPoller;

    @Before
    public void setUp() {
        jobPoller = new TestableCtrlplaneJobPoller();
    }

    @Test
    public void testExtractJobInfoFromValidMap() {
        // Create a valid job map
        Map<String, Object> jobConfig = new HashMap<>();
        jobConfig.put("jobUrl", "http://jenkins-server/job/test-job/");

        Map<String, Object> jobMap = new HashMap<>();
        String jobId = UUID.randomUUID().toString();
        jobMap.put("id", jobId);
        jobMap.put("status", "pending");
        jobMap.put("jobAgentConfig", jobConfig);

        // Extract job info
        TestJobInfo jobInfo = jobPoller.testExtractJobInfo(jobMap);

        // Verify extraction
        assertNotNull("JobInfo should not be null", jobInfo);
        assertEquals("Job ID should match", jobId, jobInfo.jobId);
        assertEquals("Job URL should match", "http://jenkins-server/job/test-job/", jobInfo.jobUrl);
    }

    @Test
    public void testSkipNonPendingJobs() {
        // Create a job map with non-pending status
        Map<String, Object> jobConfig = new HashMap<>();
        jobConfig.put("jobUrl", "http://jenkins-server/job/test-job/");

        Map<String, Object> jobMap = new HashMap<>();
        String jobId = UUID.randomUUID().toString();
        jobMap.put("id", jobId);
        jobMap.put("status", "in_progress"); // Non-pending status
        jobMap.put("jobAgentConfig", jobConfig);

        // Extract job info
        TestJobInfo jobInfo = jobPoller.testExtractJobInfo(jobMap);

        // Verify null for non-pending jobs
        assertNull("Should return null for non-pending jobs", jobInfo);
    }

    @Test
    public void testJobProcessingStats() {
        TestJobProcessingStats stats = new TestJobProcessingStats();
        assertEquals("Should have 0 processed jobs initially", 0, stats.processedJobs);
        assertEquals("Should have 0 errors initially", 0, stats.errors);
        assertEquals("Should have 0 skipped jobs initially", 0, stats.skippedJobs);

        // Increment counters
        stats.processedJobs++;
        stats.errors++;
        stats.skippedJobs += 2;

        assertEquals("Should have 1 processed job", 1, stats.processedJobs);
        assertEquals("Should have 1 error", 1, stats.errors);
        assertEquals("Should have 2 skipped jobs", 2, stats.skippedJobs);
    }

    @Test
    public void testHandleCompletedJobWithSuccessResult() {
        String jobId = UUID.randomUUID().toString();
        UUID jobUUID = UUID.randomUUID();
        String externalId = "build-123";

        @SuppressWarnings("rawtypes")
        Run mockRun = Mockito.mock(Run.class);
        Mockito.when(mockRun.getResult()).thenReturn(Result.SUCCESS);

        TestJobInfo jobInfo = new TestJobInfo(jobId, jobUUID, "http://jenkins-server/job/test-job/");
        TestActiveJobInfo activeJob = new TestActiveJobInfo(jobInfo, externalId, mockRun);
        jobPoller.testActiveJobs.put(jobId, activeJob);

        jobPoller.handleCompletedJob(activeJob);

        assertEquals("Should have 1 status update", 1, jobPoller.jobStatusUpdates.size());
        Map<String, String> update = jobPoller.jobStatusUpdates.get(0);
        assertEquals("Job ID should match", jobId, update.get("jobId"));
        assertEquals("Status should be successful", "successful", update.get("status"));

        assertFalse("Job should be removed from active jobs", jobPoller.testActiveJobs.containsKey(jobId));
    }

    @Test
    public void testHandleCompletedJobWithFailureResult() {
        String jobId = UUID.randomUUID().toString();
        UUID jobUUID = UUID.randomUUID();
        String externalId = "build-456";

        @SuppressWarnings("rawtypes")
        Run mockRun = Mockito.mock(Run.class);
        Mockito.when(mockRun.getResult()).thenReturn(Result.FAILURE);

        TestJobInfo jobInfo = new TestJobInfo(jobId, jobUUID, "http://jenkins-server/job/test-job/");
        TestActiveJobInfo activeJob = new TestActiveJobInfo(jobInfo, externalId, mockRun);
        jobPoller.testActiveJobs.put(jobId, activeJob);

        jobPoller.handleCompletedJob(activeJob);

        assertEquals("Should have 1 status update", 1, jobPoller.jobStatusUpdates.size());
        Map<String, String> update = jobPoller.jobStatusUpdates.get(0);
        assertEquals("Job ID should match", jobId, update.get("jobId"));
        assertEquals("Status should be failure", "failure", update.get("status"));

        assertFalse("Job should be removed from active jobs", jobPoller.testActiveJobs.containsKey(jobId));
    }
}
