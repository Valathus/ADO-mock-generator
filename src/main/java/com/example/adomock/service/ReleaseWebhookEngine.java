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
 * Classic Release Pipeline simulation (available on ADO Server 2017+).
 * Creates a release and optionally triggers environment deployments,
 * firing release.created and deployment lifecycle events.
 */
@Service
public class ReleaseWebhookEngine {

    private static final Logger log = LoggerFactory.getLogger(ReleaseWebhookEngine.class);

    private final AdoRestClient adoClient;
    private final AdoProperties properties;
    private final UserIdentityProvider identityProvider;
    private final FileStateRepository repository;

    @Value("${mock.release.enabled:false}")
    private boolean enabled;

    @Value("${mock.release.definitionId:0}")
    private int configuredDefinitionId;

    public ReleaseWebhookEngine(AdoRestClient adoClient, AdoProperties properties,
            UserIdentityProvider identityProvider, FileStateRepository repository) {
        this.adoClient = adoClient;
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.repository = repository;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String runReleaseWebhook(String correlationId, Random random) {
        if (!enabled) {
            log.debug("Release engine disabled, skipping");
            return "skipped:disabled";
        }

        MockState state = repository.load();
        if (state == null || state.collectionDetails == null) {
            return "skipped:no-state";
        }

        int definitionId = resolveDefinitionId(state);
        if (definitionId <= 0) {
            log.warn("Release definition ID not configured. Set mock.release.definitionId in application.properties or state.releaseDefinitionId in mock-state.json");
            return "skipped:no-definition-id";
        }

        MockState.User user = identityProvider.next();
        if (user == null || user.pat == null) {
            return "skipped:no-user";
        }

        String project = state.collectionDetails.projectName;
        String apiVersion = properties.getApiVersion();

        // Step 1: Create release (fires ms.vss-release.release-created-event)
        Integer releaseId = createRelease(user.pat, project, apiVersion, definitionId, correlationId);
        if (releaseId == null) {
            return "error:release-create-failed";
        }
        log.info("Release created | releaseId={} | correlation={}", releaseId, correlationId);

        // Step 2: 70% of the time trigger deployment to first environment
        if (random.nextInt(100) < 70) {
            triggerDeployment(user.pat, project, apiVersion, releaseId, random, correlationId);
        }

        return "ok:releaseId=" + releaseId;
    }

    private Integer createRelease(String pat, String project, String apiVersion, int definitionId, String correlationId) {
        Map<String, Object> definition = new HashMap<>();
        definition.put("id", definitionId);

        Map<String, Object> body = new HashMap<>();
        body.put("definitionId", definitionId);
        body.put("description", "Mock release | correlation=" + correlationId);
        body.put("artifacts", List.of());
        body.put("isDraft", false);
        body.put("manualEnvironments", List.of());
        body.put("variables", Map.of());

        String uri = "/" + project + "/_apis/release/releases?api-version=" + apiVersion;

        try {
            JsonNode response = adoClient.post(pat, uri, body);
            int id = response.path("id").asInt(0);
            return id > 0 ? id : null;
        } catch (Exception e) {
            log.error("Release create failed | err={}", e.getMessage());
            return null;
        }
    }

    private void triggerDeployment(String pat, String project, String apiVersion, int releaseId, Random random, String correlationId) {
        // Fetch environments from the release
        String releaseUri = "/" + project + "/_apis/release/releases/" + releaseId + "?api-version=" + apiVersion;

        JsonNode releaseDetails;
        try {
            releaseDetails = adoClient.get(pat, releaseUri);
        } catch (Exception e) {
            log.error("Failed to fetch release details for deployment | releaseId={} err={}", releaseId, e.getMessage());
            return;
        }

        JsonNode environments = releaseDetails.path("environments");
        if (environments.isEmpty()) {
            log.info("No environments found in release {}", releaseId);
            return;
        }

        // Pick first (or random) environment
        int envIndex = environments.size() > 1 ? random.nextInt(environments.size()) : 0;
        JsonNode env = environments.get(envIndex);
        int environmentId = env.path("id").asInt(0);
        String envName = env.path("name").asText("env-" + environmentId);

        if (environmentId <= 0) {
            return;
        }

        // Start deployment (fires deployment.started event)
        String envUri = "/" + project + "/_apis/release/releases/" + releaseId
                + "/environments/" + environmentId + "?api-version=" + apiVersion;

        try {
            Map<String, Object> startBody = new HashMap<>();
            startBody.put("status", "inProgress");
            startBody.put("comment", "Mock deployment started | correlation=" + correlationId);
            adoClient.patch(pat, envUri, startBody);
            log.info("Deployment started | releaseId={} | envId={} | envName={}", releaseId, environmentId, envName);
        } catch (Exception e) {
            log.warn("Deployment start failed (env may auto-deploy) | err={}", e.getMessage());
        }

        // 80% chance complete the deployment
        if (random.nextInt(100) < 80) {
            completeDeployment(pat, project, apiVersion, releaseId, environmentId, envName, random, correlationId);
        }
    }

    private void completeDeployment(String pat, String project, String apiVersion, int releaseId,
            int environmentId, String envName, Random random, String correlationId) {

        // 85% succeed, 15% fail
        String finalStatus = random.nextInt(100) < 85 ? "succeeded" : "rejected";

        String envUri = "/" + project + "/_apis/release/releases/" + releaseId
                + "/environments/" + environmentId + "?api-version=" + apiVersion;

        try {
            Map<String, Object> completeBody = new HashMap<>();
            completeBody.put("status", finalStatus);
            completeBody.put("comment", "Mock deployment " + finalStatus + " | correlation=" + correlationId);
            adoClient.patch(pat, envUri, completeBody);
            log.info("Deployment {} | releaseId={} | envId={} | envName={}", finalStatus, releaseId, environmentId, envName);
        } catch (Exception e) {
            log.error("Deployment complete failed | err={}", e.getMessage());
        }
    }

    private int resolveDefinitionId(MockState state) {
        if (state.releaseDefinitionId != null && state.releaseDefinitionId > 0) {
            return state.releaseDefinitionId;
        }
        return configuredDefinitionId;
    }
}