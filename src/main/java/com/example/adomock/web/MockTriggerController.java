package com.example.adomock.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.adomock.service.PipelineWebhookEngine;
import com.example.adomock.service.ReleaseWebhookEngine;
import com.example.adomock.service.RepoWebhookEngine;
import com.example.adomock.service.TestRunWebhookEngine;
import com.example.adomock.service.TfvcWebhookEngine;
import com.example.adomock.service.WorkItemWebhookEngine;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;

/**
 * Manual event trigger endpoints for testing.
 * Call POST /api/mock/trigger/{event} to immediately fire that event type
 * without waiting for the Quartz scheduler.
 *
 * Endpoints:
 *   POST /api/mock/trigger/build       — queue a build (ADO fires build.queued → started → complete)
 *   POST /api/mock/trigger/tfvc        — create a TFVC changeset (fires tfvc.checkin)
 *   POST /api/mock/trigger/release     — create a release + deploy (fires release.created, deployment events)
 *   POST /api/mock/trigger/testrun     — create + complete a test run (fires testrun.created, testrun.completed)
 *   POST /api/mock/trigger/workitem    — mutate a work item (due date + blocked-by included in random mix)
 *   POST /api/mock/trigger/repo        — run a repo mutation cycle (push, PR, merge)
 *   POST /api/mock/trigger/all         — fire one of each above
 */
@RestController
@RequestMapping("/api/mock/trigger")
public class MockTriggerController {

    private static final Logger log = LoggerFactory.getLogger(MockTriggerController.class);

    private final PipelineWebhookEngine pipelineEngine;
    private final TfvcWebhookEngine tfvcEngine;
    private final ReleaseWebhookEngine releaseEngine;
    private final TestRunWebhookEngine testRunEngine;
    private final WorkItemWebhookEngine workItemEngine;
    private final RepoWebhookEngine repoEngine;
    private final FileStateRepository repository;

    public MockTriggerController(PipelineWebhookEngine pipelineEngine,
            TfvcWebhookEngine tfvcEngine,
            ReleaseWebhookEngine releaseEngine,
            TestRunWebhookEngine testRunEngine,
            WorkItemWebhookEngine workItemEngine,
            RepoWebhookEngine repoEngine,
            FileStateRepository repository) {
        this.pipelineEngine = pipelineEngine;
        this.tfvcEngine = tfvcEngine;
        this.releaseEngine = releaseEngine;
        this.testRunEngine = testRunEngine;
        this.workItemEngine = workItemEngine;
        this.repoEngine = repoEngine;
        this.repository = repository;
    }

    @PostMapping("/build")
    public Map<String, Object> triggerBuild() {
        String correlation = "manual:" + UUID.randomUUID();
        log.info("Manual trigger: build | correlation={}", correlation);
        try {
            MockState state = repository.load();
            String workItemId = pickAnyWorkItemId(state);
            String scenario = pipelineEngine.runPipelineWebhook(workItemId, correlation);
            return result("ok", "Build queued. ADO will fire build.queued → build.complete. scenario=" + scenario);
        } catch (Exception e) {
            log.error("Manual build trigger failed", e);
            return result("error", e.getMessage());
        }
    }

    @PostMapping("/tfvc")
    public Map<String, Object> triggerTfvc() {
        String correlation = "manual:" + UUID.randomUUID();
        log.info("Manual trigger: tfvc | correlation={}", correlation);
        try {
            String outcome = tfvcEngine.runCheckin(correlation, new Random());
            return result(outcome.startsWith("ok") ? "ok" : outcome.startsWith("skipped") ? "skipped" : "error", outcome);
        } catch (Exception e) {
            log.error("Manual TFVC trigger failed", e);
            return result("error", e.getMessage());
        }
    }

    @PostMapping("/release")
    public Map<String, Object> triggerRelease() {
        String correlation = "manual:" + UUID.randomUUID();
        log.info("Manual trigger: release | correlation={}", correlation);
        try {
            String outcome = releaseEngine.runReleaseWebhook(correlation, new Random());
            return result(outcome.startsWith("ok") ? "ok" : outcome.startsWith("skipped") ? "skipped" : "error", outcome);
        } catch (Exception e) {
            log.error("Manual release trigger failed", e);
            return result("error", e.getMessage());
        }
    }

    @PostMapping("/testrun")
    public Map<String, Object> triggerTestRun() {
        String correlation = "manual:" + UUID.randomUUID();
        log.info("Manual trigger: testrun | correlation={}", correlation);
        try {
            String outcome = testRunEngine.runTestRunWebhook(correlation, new Random());
            return result(outcome.startsWith("ok") ? "ok" : outcome.startsWith("skipped") ? "skipped" : "error", outcome);
        } catch (Exception e) {
            log.error("Manual test run trigger failed", e);
            return result("error", e.getMessage());
        }
    }

    @PostMapping("/workitem")
    public Map<String, Object> triggerWorkItem() {
        String correlation = "manual:" + UUID.randomUUID();
        log.info("Manual trigger: workitem | correlation={}", correlation);
        try {
            MockState state = repository.load();
            String workItemId = pickAnyWorkItemId(state);
            if (workItemId == null) {
                return result("skipped", "No work items found in state");
            }
            workItemEngine.runWebhookUpdate(workItemId, correlation, new Random());
            return result("ok", "Work item mutation applied to workItemId=" + workItemId);
        } catch (Exception e) {
            log.error("Manual work item trigger failed", e);
            return result("error", e.getMessage());
        }
    }

    @PostMapping("/repo")
    public Map<String, Object> triggerRepo() {
        String correlation = "manual:" + UUID.randomUUID();
        log.info("Manual trigger: repo | correlation={}", correlation);
        try {
            repoEngine.runWebhookCycle(correlation, new Random());
            return result("ok", "Repo mutation cycle executed (push/PR/merge)");
        } catch (Exception e) {
            log.error("Manual repo trigger failed", e);
            return result("error", e.getMessage());
        }
    }

    @PostMapping("/all")
    public Map<String, Object> triggerAll() {
        log.info("Manual trigger: ALL events");
        Map<String, Object> results = new LinkedHashMap<>();
        Random random = new Random();
        MockState state = repository.load();
        String workItemId = pickAnyWorkItemId(state);

        // Build
        try {
            String scenario = pipelineEngine.runPipelineWebhook(workItemId, "manual-all:" + UUID.randomUUID());
            results.put("build", result("ok", "Build queued. scenario=" + scenario));
        } catch (Exception e) {
            results.put("build", result("error", e.getMessage()));
        }

        // TFVC
        try {
            String outcome = tfvcEngine.runCheckin("manual-all:" + UUID.randomUUID(), random);
            results.put("tfvc", result(outcome.startsWith("ok") ? "ok" : "skipped", outcome));
        } catch (Exception e) {
            results.put("tfvc", result("error", e.getMessage()));
        }

        // Release
        try {
            String outcome = releaseEngine.runReleaseWebhook("manual-all:" + UUID.randomUUID(), random);
            results.put("release", result(outcome.startsWith("ok") ? "ok" : "skipped", outcome));
        } catch (Exception e) {
            results.put("release", result("error", e.getMessage()));
        }

        // Test run
        try {
            String outcome = testRunEngine.runTestRunWebhook("manual-all:" + UUID.randomUUID(), random);
            results.put("testrun", result(outcome.startsWith("ok") ? "ok" : "skipped", outcome));
        } catch (Exception e) {
            results.put("testrun", result("error", e.getMessage()));
        }

        // Work item
        try {
            if (workItemId != null) {
                workItemEngine.runWebhookUpdate(workItemId, "manual-all:" + UUID.randomUUID(), random);
                results.put("workitem", result("ok", "Work item mutated. id=" + workItemId));
            } else {
                results.put("workitem", result("skipped", "No work items in state"));
            }
        } catch (Exception e) {
            results.put("workitem", result("error", e.getMessage()));
        }

        // Repo
        try {
            repoEngine.runWebhookCycle("manual-all:" + UUID.randomUUID(), random);
            results.put("repo", result("ok", "Repo cycle executed"));
        } catch (Exception e) {
            results.put("repo", result("error", e.getMessage()));
        }

        return results;
    }

    private String pickAnyWorkItemId(MockState state) {
        if (state == null || state.programIterations == null) return null;
        for (MockState.ProgramIteration pi : state.programIterations) {
            if (pi == null || pi.sprints == null) continue;
            for (MockState.Sprint sprint : pi.sprints) {
                if (sprint != null && sprint.workItemIds != null && !sprint.workItemIds.isEmpty()) {
                    return sprint.workItemIds.get(0);
                }
            }
        }
        return null;
    }

    private Map<String, Object> result(String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        m.put("timestamp", java.time.Instant.now().toString());
        return m;
    }
}