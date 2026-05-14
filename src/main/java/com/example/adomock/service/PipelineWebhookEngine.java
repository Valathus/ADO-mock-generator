package com.example.adomock.service;

import java.util.HashMap;
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

		Map<String, Object> parameters = Map.of("mockScenario", scenario);

		Map<String, Object> body = new HashMap<>();
		body.put("definition", definition);
		body.put("sourceBranch", "refs/heads/" + branch);

		try {
			body.put("templateParameters", Map.of("mockScenario", scenario));
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize parameters", e);
		}

		MockState.User user = identityProvider.next();
		String pat = user.pat;

		String uri = "/" + state.collectionDetails.projectName + "/_apis/build/builds?api-version="
				+ properties.getApiVersion();

		JsonNode response = adoClient.post(pat, uri, body);

		String buildId = response.path("id").asText(null);

		if (buildId == null || buildId.isBlank()) {
			throw new IllegalStateException("Build queue failed");
		}
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
				log.info("No running builds available to cancel");
				return;
			}

			int index = random.nextInt(builds.size());
			String buildId = builds.get(index).path("id").asText();

			log.info("Cancelling running build {}", buildId);

			Map<String, Object> cancelBody = Map.of("status", "cancelling");

			String cancelUri = "/" + state.collectionDetails.projectName + "/_apis/build/builds/" + buildId
					+ "?api-version=" + properties.getApiVersion();

			adoClient.patch(pat, cancelUri, cancelBody);

		} catch (Exception e) {
			log.error("Failed to cancel running build", e);
		}
	}
}