package com.example.adomock.service;

import java.time.Instant;
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

@Service
public class TfvcWebhookEngine {

    private static final Logger log = LoggerFactory.getLogger(TfvcWebhookEngine.class);

    private final AdoRestClient adoClient;
    private final AdoProperties properties;
    private final UserIdentityProvider identityProvider;
    private final FileStateRepository repository;

    @Value("${mock.tfvc.enabled:false}")
    private boolean enabled;

    @Value("${mock.tfvc.rootPath:}")
    private String configuredRootPath;

    public TfvcWebhookEngine(AdoRestClient adoClient, AdoProperties properties,
            UserIdentityProvider identityProvider, FileStateRepository repository) {
        this.adoClient = adoClient;
        this.properties = properties;
        this.identityProvider = identityProvider;
        this.repository = repository;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String runCheckin(String correlationId, Random random) {
        if (!enabled) {
            log.debug("TFVC engine disabled, skipping checkin");
            return "skipped:disabled";
        }

        MockState state = repository.load();
        if (state == null || state.collectionDetails == null) {
            return "skipped:no-state";
        }

        String rootPath = resolveRootPath(state);
        if (rootPath == null || rootPath.isBlank()) {
            log.warn("TFVC root path not configured. Set mock.tfvc.rootPath in application.properties or state.tfvcRootPath in mock-state.json");
            return "skipped:no-root-path";
        }

        MockState.User user = identityProvider.next();
        if (user == null || user.pat == null) {
            return "skipped:no-user";
        }

        String project = state.collectionDetails.projectName;
        String changeType = pickChangeType(random);
        String filePath = buildFilePath(rootPath, correlationId, random);
        String comment = buildComment(correlationId, changeType, random);

        Map<String, Object> body = buildChangesetBody(comment, filePath, changeType, correlationId);

        String uri = "/" + project + "/_apis/tfvc/changesets?api-version=" + properties.getApiVersion();

        try {
            JsonNode response = adoClient.post(user.pat, uri, body);
            String changesetId = response.path("changesetId").asText(null);
            if (changesetId == null || changesetId.isBlank()) {
                changesetId = response.path("id").asText("unknown");
            }
            log.info("TFVC checkin created | changesetId={} | path={} | changeType={} | correlation={}",
                    changesetId, filePath, changeType, correlationId);
            return "ok:changeset=" + changesetId;
        } catch (Exception e) {
            log.error("TFVC checkin failed | correlation={} | err={}", correlationId, e.getMessage());
            return "error:" + e.getMessage();
        }
    }

    private Map<String, Object> buildChangesetBody(String comment, String filePath, String changeType, String correlation) {
        Map<String, Object> item = new HashMap<>();
        item.put("path", filePath);
        item.put("isFolder", false);

        Map<String, Object> newContent = new HashMap<>();
        newContent.put("content", "Mock TFVC content\nCorrelation=" + correlation + "\nUpdatedAt=" + Instant.now());
        newContent.put("contentType", "rawtext");

        Map<String, Object> change = new HashMap<>();
        change.put("changeType", changeType);
        change.put("item", item);
        if (!"delete".equalsIgnoreCase(changeType)) {
            change.put("newContent", newContent);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("comment", comment);
        body.put("changes", List.of(change));

        return body;
    }

    private String resolveRootPath(MockState state) {
        if (state.tfvcRootPath != null && !state.tfvcRootPath.isBlank()) {
            return state.tfvcRootPath;
        }
        return configuredRootPath;
    }

    private String pickChangeType(Random random) {
        int r = random.nextInt(100);
        if (r < 50) return "edit";
        if (r < 80) return "add";
        return "edit";
    }

    private String buildFilePath(String rootPath, String correlation, Random random) {
        String[] folders = { "src", "tests", "docs", "config", "build" };
        String folder = folders[random.nextInt(folders.length)];
        String stem = correlation == null ? "mock" : correlation.replaceAll("[^a-zA-Z0-9]", "").substring(0,
                Math.min(8, correlation.replaceAll("[^a-zA-Z0-9]", "").length()));
        String ext = random.nextInt(2) == 0 ? ".cs" : ".xml";
        return rootPath + "/" + folder + "/mock-" + stem + "-" + Instant.now().toEpochMilli() + ext;
    }

    private String buildComment(String correlation, String changeType, Random random) {
        String[] messages = {
            "Webhook simulation: " + changeType + " | " + correlation,
            "Mock checkin for testing | correlation=" + correlation,
            "Automated mock changeset | " + Instant.now(),
            "CI fix applied | correlation=" + correlation
        };
        return messages[random.nextInt(messages.length)];
    }
}