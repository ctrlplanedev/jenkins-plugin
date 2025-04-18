package io.jenkins.plugins.ctrlplane;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

public class CtrlplaneJobPollerTest {

    private CtrlplaneJobPoller jobPoller;

    @Before
    public void setUp() {
        jobPoller = new CtrlplaneJobPoller();
    }

    @Test
    public void testExtractJobNameFromSimpleUrl() {
        String url = "http://jenkins-server/job/simple-job/";
        String expected = "simple-job";
        assertEquals(expected, jobPoller.extractJobNameFromUrl(url));
    }

    @Test
    public void testExtractJobNameFromNestedUrl() {
        String url = "http://jenkins-server/job/folder/job/subfolder/job/nested-job/";
        String expected = "folder/subfolder/nested-job";
        assertEquals(expected, jobPoller.extractJobNameFromUrl(url));
    }

    @Test
    public void testExtractJobNameFromUrlWithContext() {
        String url = "http://jenkins-server/jenkins/job/context-job/";
        String expected = "context-job";
        assertEquals(expected, jobPoller.extractJobNameFromUrl(url));
    }

    @Test
    public void testExtractJobNameFromComplexUrl() {
        String url =
                "https://jenkins.example.com:8080/jenkins/job/team-folder/job/project/job/component/job/complex-job/";
        String expected = "team-folder/project/component/complex-job";
        assertEquals(expected, jobPoller.extractJobNameFromUrl(url));
    }

    @Test
    public void testExtractJobNameFromInvalidUrl() {
        String url = "http://not-a-jenkins-url/something-else";
        assertNull(jobPoller.extractJobNameFromUrl(url));
    }

    @Test
    public void testExtractJobNameFromNullUrl() {
        assertNull(jobPoller.extractJobNameFromUrl(null));
    }

    @Test
    public void testExtractJobNameFromBlankUrl() {
        assertNull(jobPoller.extractJobNameFromUrl("  "));
    }

    @Test
    public void testExtractJobNameFromUrlWithParameters() {
        String url = "http://jenkins-server/job/parameterized-job/?param1=value1&param2=value2";
        String expected = "parameterized-job";
        assertEquals(expected, jobPoller.extractJobNameFromUrl(url));
    }

    @Test
    public void testTriggerJenkinsJobWithParameters() {}
}
