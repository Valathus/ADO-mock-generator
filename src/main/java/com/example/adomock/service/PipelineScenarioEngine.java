package com.example.adomock.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pipeline webhook simulator.
 *
 * Fixes / improvements: - Deterministic webhook randomness: scenario picked
 * from provided Random (or seeded) - Uses sourceBranch (refs/heads/<branch>) so
 * builds align with env promotion flow - Safe/self-healing: queue/cancel/link
 * failures won't crash the whole mutation cycle - Idempotency-friendly:
 * correlationId included in parameters - Links build to work item via
 * ArtifactLink (kept) - Resolves build definition id once and caches to state
 *
 * Call paths: - Seeder uses PipelineSeederService.ensureBuildsForSprint(...) -
 * Mutator uses this engine for noisy webhooks (MutationExecutor ->
 * runWebhookScenario)
 */
@Service
public class PipelineScenarioEngine {

	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final UserIdentityProvider identityProvider;
	private final ObjectMapper mapper;
	private final FileStateRepository repository;

	@Value("${mock.pipeline.defaultBranch:dev}")
	private String defaultBranchForWebhook; // align with gitflow envs

	@Value("${mock.pipeline.scenario.successPct:60}")
	private int successPct;

	@Value("${mock.pipeline.scenario.failPct:20}")
	private int failPct;

	@Value("${mock.pipeline.scenario.retryPct:10}")
	private int retryPct;

	@Value("${mock.pipeline.scenario.cancelPct:5}")
	private int cancelPct;

	@Value("${mock.pipeline.scenario.flakyPct:5}")
	private int flakyPct;

	public PipelineScenarioEngine(AdoRestClient adoClient, AdoProperties properties,
			UserIdentityProvider identityProvider, FileStateRepository repository) {

		this.adoClient = adoClient;
		this.properties = properties;
		this.identityProvider = identityProvider;
		this.mapper = new ObjectMapper();
		this.repository = repository;
	}

	// ======================================================
	// SCENARIO ENTRY
	// ======================================================

	/**
	 * Non-webhook/manual entry. Still deterministic-ish per call.
	 */
	public String runScenario(String workItemId) {

		Random random = new Random();
		String correlation = "manual-" + Instant.now().toEpochMilli();

		String scenario = resolveScenario(random);
		executeScenario(workItemId, scenario, correlation, defaultBranchForWebhook, random);

		return scenario;
	}

	// ======================================================
	// SCENARIO RESOLUTION
	// ======================================================

	private String resolveScenario(Random random) {

		int total = safePct(successPct) + safePct(failPct) + safePct(retryPct) + safePct(cancelPct) + safePct(flakyPct);
		if (total <= 0) {
			// fallback
			return "SUCCESS";
		}

		int r = random.nextInt(total);
		int c = 0;

		c += safePct(successPct);
		if (r < c)
			return "SUCCESS";

		c += safePct(failPct);
		if (r < c)
			return "FAIL";

		c += safePct(retryPct);
		if (r < c)
			return "RETRY";

		c += safePct(cancelPct);
		if (r < c)
			return "CANCEL";

		return "FLAKY";
	}

	private int safePct(int p) {
		return Math.max(0, p);
	}

	// ======================================================
	// EXECUTION
	// ======================================================

	private void executeScenario(String workItemId, String scenario, String correlationId, String branch,
			Random random) {

		// Make sure branch is not null/blank
		String useBranch = (branch == null || branch.isBlank()) ? defaultBranchForWebhook : branch;

		if ("RETRY".equals(scenario)) {

			queueBuildSafe(workItemId, "FAIL", correlationId + ":retry1", useBranch);
			queueBuildSafe(workItemId, "SUCCESS", correlationId + ":retry2", useBranch);

		} else if ("CANCEL".equals(scenario)) {

			String buildId = queueBuildSafe(workItemId, "SUCCESS", correlationId + ":cancel", useBranch);
			if (buildId != null) {
				cancelBuildSafe(buildId);
			}

		} else if ("FLAKY".equals(scenario)) {

			// Flaky: fail once, then success (but not always)
			queueBuildSafe(workItemId, "FAIL", correlationId + ":flaky1", useBranch);
			if (random.nextInt(100) < 70) {
				queueBuildSafe(workItemId, "SUCCESS", correlationId + ":flaky2", useBranch);
			}

		} else {

			queueBuildSafe(workItemId, scenario, correlationId, useBranch);
		}
	}

	// ======================================================
	// BUILD QUEUE
	// ======================================================

	private String queueBuildSafe(String workItemId, String mockScenario, String correlationId, String branch) {
		try {
			return queueBuild(workItemId, mockScenario, correlationId, branch);
		} catch (Exception ignored) {
			// Mutation cycle should not die because one queue failed.
			return null;
		}
	}

	private String queueBuild(String workItemId, String mockScenario, String correlationId, String branch) {

		MockState state = repository.load();
		if (state == null || state.collectionDetails == null || state.collectionDetails.projectName == null) {
			throw new IllegalStateException("Project not initialized");
		}
		if (state.repo == null) {
			throw new IllegalStateException("Repo not initialized");
		}

		Integer definitionId = state.repo.buildDefinitionId;
		if (definitionId == null) {
			definitionId = resolveBuildDefinitionId(state);
			state.repo.buildDefinitionId = definitionId;
			repository.save(state);
		}

		MockState.User user = identityProvider.next();
		String pat = user.pat;

		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();

		Map<String, Object> definition = Map.of("id", definitionId);

		Map<String, Object> parameters = Map.of("mockScenario", mockScenario, "linkedWorkItem", workItemId, "mockRun",
				correlationId, "mockBranch", branch);

		Map<String, Object> body = new HashMap<>();
		body.put("definition", definition);

		// Align with env promotion flow: builds should come from a branch
		body.put("sourceBranch", "refs/heads/" + branch);

		try {
			body.put("parameters", mapper.writeValueAsString(parameters));
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize build parameters", e);
		}

		List<String> tags = pickBuildTags(new Random());
		if (!tags.isEmpty()) {
			body.put("tags", tags);
		}

		String uri = "/" + project + "/_apis/build/builds?api-version=" + apiVersion;

		JsonNode response = adoClient.post(pat, uri, body);

		String buildId = response.path("id").asText(null);

		if (buildId == null || buildId.isBlank())
			throw new IllegalStateException("Build queue failed");

		// Link build to work item (best-effort)
		if (workItemId != null && !workItemId.isBlank()) {
			linkBuildToWorkItemSafe(pat, project, buildId, workItemId);
		}

		return buildId;
	}

	private void cancelBuildSafe(String buildId) {
		try {
			cancelBuild(buildId);
		} catch (Exception ignored) {
		}
	}

	private void cancelBuild(String buildId) {

		MockState state = repository.load();
		if (state == null || state.collectionDetails == null)
			return;

		MockState.User user = identityProvider.next();
		String pat = user.pat;

		String uri = "/" + state.collectionDetails.projectName + "/_apis/build/builds/" + buildId + "?api-version="
				+ properties.getApiVersion();

		Map<String, Object> body = Map.of("status", "cancelling");

		adoClient.patch(pat, uri, body);
	}

	private void linkBuildToWorkItemSafe(String pat, String project, String buildId, String workItemId) {
		try {
			linkBuildToWorkItem(pat, project, buildId, workItemId);
		} catch (Exception ignored) {
		}
	}

	private void linkBuildToWorkItem(String pat, String project, String buildId, String workItemId) {

		String buildUrl = String.format("vstfs:///Build/Build/%s", buildId);

		List<Map<String, Object>> patch = List.of(Map.of("op", "add", "path", "/relations/-", "value",
				Map.of("rel", "ArtifactLink", "url", buildUrl, "attributes", Map.of("name", "Build"))));

		String uri = "/" + project + "/_apis/wit/workitems/" + workItemId + "?api-version=" + properties.getApiVersion()
				+ "&bypassRules=true&suppressNotifications=true";

		adoClient.patchJsonPatch(pat, uri, patch);
	}

	private static final String[] BUILD_TAGS = {
		"automated", "regression", "ci-cd", "nightly", "smoke", "integration"
	};

	private List<String> pickBuildTags(Random random) {
		int r = random.nextInt(100);
		if (r < 10) return List.of();
		if (r < 40) return List.of("manual");
		return List.of(BUILD_TAGS[random.nextInt(BUILD_TAGS.length)]);
	}

	private Integer resolveBuildDefinitionId(MockState state) {

		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();

		String uri = "/" + project + "/_apis/build/definitions?api-version=" + apiVersion;

		JsonNode response = adoClient.get(state.admin.pat, uri);

		if (response == null || !response.has("value"))
			throw new IllegalStateException("Failed to resolve build definitions");

		for (JsonNode def : response.path("value")) {
			if (state.repo.pipeLineName != null && state.repo.pipeLineName.equals(def.path("name").asText())) {
				return def.path("id").asInt();
			}
		}

		throw new IllegalStateException("No matching build definition found for pipeline " + state.repo.pipeLineName);
	}
}