package com.example.adomock.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Test Run simulation. Creates an automated test run and completes it,
 * firing testrun.created and testrun.completed service hook events.
 * Available on ADO Server 2017+.
 */
@Service
public class TestRunWebhookEngine {

    private static final Logger log = LoggerFactory.getLogger(TestRunWebhookEngine.class);

    private final AdoRestClient adoClient;
    private final AdoProperties properties;
    private final UserIdentityProvider identityProvider;
    private final FileStateRepository repository;

    @Value("${mock.testrun.enabled:false}")
    private boolean enabled;

    public TestRunWebhookEngine(AdoRestClient adoClient, AdoProperties properties,
            UserIdentityProvider identityProvider, FileStateRepository repository) {
        this.adoClient = adoClient;
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.repository = repository;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String runTestRunWebhook(String correlationId, Random random) {
        if (!enabled) {
            log.debug("Test run engine disabled, skipping");
            return "skipped:disabled";
        }

        MockState state = repository.load();
        if (state == null || state.collectionDetails == null) {
            return "skipped:no-state";
        }

        MockState.User user = identityProvider.next();
        if (user == null || user.pat == null) {
            return "skipped:no-user";
        }

        String project = state.collectionDetails.projectName;
        String apiVersion = properties.getApiVersion();

        // Step 1: Create test run (fires testrun.created)
        Integer runId = createTestRun(user.pat, project, apiVersion, state, correlationId, random);
        if (runId == null) {
            return "error:testrun-create-failed";
        }
        log.info("Test run created | runId={} | correlation={}", runId, correlationId);

        // Step 2: Add some test results
        addTestResults(user.pat, project, apiVersion, runId, correlationId, random);

        // Step 3: Complete the test run (fires testrun.completed)
        completeTestRun(user.pat, project, apiVersion, runId, correlationId, random);

        return "ok:runId=" + runId;
    }

    private Integer createTestRun(String pat, String project, String apiVersion, MockState state,
            String correlationId, Random random) {

        String runName = "Mock Test Run | " + correlationId;

        Map<String, Object> body = new HashMap<>();
        body.put("name", runName);
        body.put("isAutomated", true);

        // Attach to build if available in state
        if (state.repo != null && state.repo.buildDefinitionId != null) {
            body.put("build", Map.of("id", state.repo.buildDefinitionId));
        }

        // Attach to test plan if configured
        if (state.testPlanId != null && state.testPlanId > 0) {
            body.put("plan", Map.of("id", state.testPlanId));
        }

        body.put("startedDate", java.time.Instant.now().toString());
        body.put("comment", "Created by mock generator | correlation=" + correlationId);

        String uri = "/" + project + "/_apis/test/runs?api-version=" + apiVersion;

        try {
            JsonNode response = adoClient.post(pat, uri, body);
            int id = response.path("id").asInt(0);
            return id > 0 ? id : null;
        } catch (Exception e) {
            log.error("Test run create failed | err={}", e.getMessage());
            return null;
        }
    }

    private void addTestResults(String pat, String project, String apiVersion, int runId, String correlationId, Random random) {
        int totalTests = 3 + random.nextInt(8); // 3-10 tests
        List<Map<String, Object>> results = new java.util.ArrayList<>();

        String[] outcomes = { "Passed", "Failed", "Passed", "Passed", "Passed" }; // weighted pass
        String[] testNames = {
            "ShouldReturnCorrectData", "ValidateInputHandling", "ShouldHandleNullInput",
            "VerifyBusinessLogic", "CheckBoundaryConditions", "TestErrorScenarios",
            "ValidateResponseFormat", "EnsureDataIntegrity", "CheckPermissions", "VerifyLogging"
        };

        for (int i = 0; i < totalTests; i++) {
            String outcome = outcomes[random.nextInt(outcomes.length)];
            String testName = testNames[i % testNames.length];

            Map<String, Object> result = new HashMap<>();
            result.put("testCaseTitle", testName);
            result.put("outcome", outcome);
            result.put("state", "Completed");
            result.put("durationInMs", 100 + random.nextInt(5000));
            result.put("comment", "Mock test result | correlation=" + correlationId);
            results.add(result);
        }

        String uri = "/" + project + "/_apis/test/runs/" + runId + "/results?api-version=" + apiVersion;

        try {
            adoClient.post(pat, uri, results);
            log.info("Added {} test results to run {}", totalTests, runId);
        } catch (Exception e) {
            log.warn("Test results add failed (run still created) | err={}", e.getMessage());
        }
    }

    private void completeTestRun(String pat, String project, String apiVersion, int runId, String correlationId, Random random) {
        Map<String, Object> body = new HashMap<>();
        body.put("state", "Completed");
        body.put("completedDate", java.time.Instant.now().toString());
        body.put("comment", "Completed by mock generator | correlation=" + correlationId);

        String uri = "/" + project + "/_apis/test/runs/" + runId + "?api-version=" + apiVersion;

        try {
            adoClient.patch(pat, uri, body);
            log.info("Test run completed | runId={}", runId);
        } catch (Exception e) {
            log.error("Test run complete failed | runId={} | err={}", runId, e.getMessage());
        }
    }
}