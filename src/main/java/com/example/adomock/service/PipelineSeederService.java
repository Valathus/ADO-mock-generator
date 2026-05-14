package com.example.adomock.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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
 * Pipeline seeding + per-sprint deterministic build generation.
 *
 * Architecture intent: - Seeder queues limited builds for current sprint only.
 * - Webhook engine generates ongoing realistic behavior. - Idempotent + restart
 * safe. :contentReference[oaicite:1]{index=1}
 */
@Service
public class PipelineSeederService {

	private final FileStateRepository repository;
	private final UserIdentityProvider userIdentityProvider;
	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final ObjectMapper mapper = new ObjectMapper();

	@Value("${mock.seed.builds.enabled:true}")
	private boolean buildSeedingEnabled;

	public PipelineSeederService(FileStateRepository repository, UserIdentityProvider identityProvider,
			AdoRestClient adoClient, AdoProperties properties) {

		this.repository = repository;
		this.userIdentityProvider = identityProvider;
		this.adoClient = adoClient;
		this.properties = properties;
	}

	/*
	 * ============================================================ ONE-TIME
	 * BACKFILL (INITIAL SEED)
	 * ============================================================
	 */

	public boolean seedBuildHistoryIfRequired() {

		if (!buildSeedingEnabled)
			return false;

		MockState state = repository.load();
		if (state.repo == null)
			return false;

		if (state.buildBackfill == null) {
			state.buildBackfill = new MockState.BuildBackfill();
		}

		if (state.buildBackfill.completed)
			return false;

		if (state.buildBackfill.startedAt == null) {
			state.buildBackfill.startedAt = Instant.now();
		}

		Integer definitionId = resolveDefinitionIfRequired(state);

		int buildsToQueue = state.dataLoadConfig.buildsPerSprint;

		for (int i = 0; i < buildsToQueue; i++) {
			queueBuild(state, definitionId, state.repo.defaultBranch, "SUCCESS", "initial-seed-" + i);
		}

		state.buildBackfill.completed = true;
		state.buildBackfill.completedAt = Instant.now();
		repository.save(state);

		return true;
	}

	/*
	 * ============================================================ PER-SPRINT
	 * ENSURE (WEBHOOK MODE)
	 * ============================================================
	 */

	public void ensureBuildsForSprint(MockState state, MockState.Sprint sprint, String correlation) {

		if (!buildSeedingEnabled)
			return;
		if (state == null || sprint == null)
			return;
		if (state.repo == null)
			return;

		// Ensure build definition resolved once
		if (state.repo.buildDefinitionId == null) {
			resolveDefinitionIfRequired(state);
		}

		Integer definitionId = state.repo.buildDefinitionId;
		if (definitionId == null)
			return;

		int expected = state.dataLoadConfig.buildsPerSprint;
		if (expected <= 0)
			return;

		if (sprint.buildIds == null) {
			sprint.buildIds = new ArrayList<>();
		}

		int existing = sprint.buildIds.size();

		// Idempotency guard
		if (existing >= expected) {
			return;
		}

		for (int i = existing; i < expected; i++) {

			String scenario = deterministicScenario(i);

			String buildId = queueBuild(state, definitionId, state.repo.defaultBranch, scenario,
					correlation + "-s" + sprint.sprintNumber + "-b" + i);

			// Only mutate state AFTER successful queue
			if (buildId != null && !buildId.isBlank()) {
				sprint.buildIds.add(buildId);
			}
		}

		repository.save(state);
	}

	/*
	 * ============================================================ INTERNAL HELPERS
	 * ============================================================
	 */

	private String queueBuild(MockState state, Integer definitionId, String branch, String scenario,
			String correlation) {

		String pat = userIdentityProvider.next().pat;

		Map<String, Object> definition = Map.of("id", definitionId);

		Map<String, Object> parameters = new HashMap<>();
		parameters.put("mockScenario", scenario);
		parameters.put("mockRun", correlation);

		Map<String, Object> body = new HashMap<>();
		body.put("definition", definition);
		body.put("sourceBranch", "refs/heads/" + branch);
		body.put("parameters", serialize(parameters));

		String uri = "/" + state.collectionDetails.projectName + "/_apis/build/builds?api-version="
				+ properties.getApiVersion();

		try {
			JsonNode response = adoClient.post(pat, uri, body);
			return response.path("id").asText(null);
		} catch (Exception ex) {
			// Self-healing: next tick will attempt missing delta again
			return null;
		}
	}

	private String deterministicScenario(int i) {
		switch (i % 5) {
		case 0:
			return "FAIL";
		case 1:
			return "SUCCESS";
		case 2:
			return "RETRY";
		case 3:
			return "CANCEL";
		default:
			return "SUCCESS";
		}
	}

	private Integer resolveDefinitionIfRequired(MockState state) {

		if (state.repo.buildDefinitionId != null)
			return state.repo.buildDefinitionId;

		String uri = "/" + state.collectionDetails.projectName + "/_apis/build/definitions?api-version="
				+ properties.getApiVersion();

		JsonNode response = adoClient.get(state.admin.pat, uri);

		if (response == null || !response.has("value"))
			throw new IllegalStateException("Failed to resolve build definitions");

		for (JsonNode def : response.path("value")) {
			if (state.repo.pipeLineName.equals(def.path("name").asText())) {
				Integer id = def.path("id").asInt();
				state.repo.buildDefinitionId = id;
				repository.save(state);
				return id;
			}
		}

		throw new IllegalStateException("No matching build definition found for pipeline " + state.repo.pipeLineName);
	}

	private String serialize(Map<String, Object> map) {
		try {
			return mapper.writeValueAsString(map);
		} catch (Exception e) {
			throw new RuntimeException("Failed to serialize build parameters", e);
		}
	}
}