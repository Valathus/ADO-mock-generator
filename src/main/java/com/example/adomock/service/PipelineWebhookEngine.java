package com.example.adomock.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PipelineWebhookEngine {

	private static final Logger log = LoggerFactory.getLogger(PipelineWebhookEngine.class);

	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final UserIdentityProvider identityProvider;
	private final FileStateRepository repository;
	private final ObjectMapper mapper = new ObjectMapper();

	private int successPct = 50;

	private int failPct = 30;

	private int cancelPct = 20;

	public PipelineWebhookEngine(AdoRestClient adoClient, AdoProperties properties,
			UserIdentityProvider identityProvider, FileStateRepository repository) {

		this.adoClient = adoClient;
		this.properties = properties;
		this.identityProvider = identityProvider;
		this.repository = repository;
	}

	public String runPipelineWebhook(String workItemId, String correlationId) {

		ThreadLocalRandom random = ThreadLocalRandom.current();

		String scenario = resolveScenario(random);

		if ("CANCEL".equalsIgnoreCase(scenario)) {
		    cancelRandomRunningBuild(random);
		} else {
		    queueBuild(workItemId, scenario, correlationId, random);
		}

		return scenario;
	}

	private String resolveScenario(ThreadLocalRandom random) {

		int total = successPct + failPct + cancelPct;

		if (total <= 0) {
			throw new IllegalStateException("Pipeline probability config invalid");
		}

		int r = random.nextInt(total);

		if (r < successPct) {
			return "SUCCESS";
		}

		if (r < successPct + failPct) {
			return "FAIL";
		}

		return "CANCEL";
	}

	private String resolveBranch(ThreadLocalRandom random) {

		int r = random.nextInt(100);

		if (r < 40) {
			return "dev";
		}

		if (r < 70) {
			return "qa";
		}

		if (r < 90) {
			return "demo";
		}

		return "prod";
	}

	private void queueBuild(String workItemId, String scenario, String correlationId, ThreadLocalRandom random) {

		MockState state = repository.load();

		String branch = resolveBranch(random);

		log.info("Scenario chosen: {} and branch choosen: {}", scenario, branch);

		if (state == null || state.collectionDetails == null) {
			throw new IllegalStateException("Project not initialized");
		}

		Integer definitionId = state.repo.buildDefinitionId;

		if (definitionId == null) {
			throw new IllegalStateException("Build definition not configured");
		}

		Map<String, Object> definition = Map.of("id", definitionId);

		Map<String, Object> body = new HashMap<>();
		body.put("definition", definition);
		body.put("sourceBranch", "refs/heads/" + branch);

		try {
			body.put("templateParameters", Map.of("mockScenario", scenario));
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize parameters", e);
		}

		List<String> tags = pickBuildTags(random);
		String tag = tags.isEmpty() ? "" : tags.get(0);
		log.info("action=pickBuildTags | tag={}", tag.isEmpty() ? "<none>" : tag);

		Map<String, Object> templateParams = new HashMap<>();
		templateParams.put("mockScenario", scenario);
		if (!tag.isEmpty()) {
			templateParams.put("mockTag", tag);
		}
		body.put("templateParameters", templateParams);

		MockState.User user = identityProvider.next();
		String pat = user.pat;

		String uri = "/" + state.collectionDetails.projectName + "/_apis/build/builds?api-version="
				+ properties.getApiVersion();

		JsonNode response;
		try {
			response = adoClient.post(pat, uri, body);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.warn("user={} | action=queueBuild | scenario={} | branch={} | error={}", user.username, scenario, branch, root.getMessage());
			return;
		}

		String buildId = response.path("id").asText(null);

		if (buildId == null || buildId.isBlank()) {
			log.warn("user={} | action=queueBuild | scenario={} | branch={} | error=empty buildId in response", user.username, scenario, branch);
			return;
		}

		log.info("user={} | action=queueBuild | buildId={} | scenario={} | branch={} | tag={}", user.username, buildId, scenario, branch, tag.isEmpty() ? "<none>" : tag);
	}

	private void cancelRandomRunningBuild(ThreadLocalRandom random) {

		MockState state = repository.load();

		if (state == null || state.collectionDetails == null) {
			throw new IllegalStateException("Project not initialized");
		}

		Integer definitionId = state.repo.buildDefinitionId;

		MockState.User user = identityProvider.next();
		String pat = user.pat;

		try {

			String uri = "/" + state.collectionDetails.projectName + "/_apis/build/builds?statusFilter=inProgress"
					+ "&definitions=" + definitionId + "&api-version=" + properties.getApiVersion();

			JsonNode response = adoClient.get(pat, uri);

			JsonNode builds = response.path("value");

			if (builds.isEmpty()) {
				log.info("user={} | action=cancelBuild | status=noRunningBuilds", user.username);
				return;
			}

			int index = random.nextInt(builds.size());
			String buildId = builds.get(index).path("id").asText();

			Map<String, Object> cancelBody = Map.of("status", "cancelling");

			String cancelUri = "/" + state.collectionDetails.projectName + "/_apis/build/builds/" + buildId
					+ "?api-version=" + properties.getApiVersion();

			adoClient.patch(pat, cancelUri, cancelBody);

			log.info("user={} | action=cancelBuild | buildId={}", user.username, buildId);

		} catch (Exception e) {
			log.error("user={} | action=cancelBuild | error={}", user.username, e.getMessage(), e);
		}
	}

	private static final String[] BUILD_TAGS = {
		"automated", "regression", "ci-cd", "nightly", "smoke", "integration"
	};

	private List<String> pickBuildTags(ThreadLocalRandom random) {
		int r = random.nextInt(100);
		if (r < 10) return List.of();
		if (r < 40) return List.of("manual");
		return List.of(BUILD_TAGS[random.nextInt(BUILD_TAGS.length)]);
	}
}