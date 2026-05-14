package com.example.adomock.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.AdminIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Repository bootstrap seeder.
 *
 * Design intent (per consolidated architecture notes): - Ensures repository
 * exists (creates if missing). - Ensures default branch exists (with initial
 * commit). - Ensures environment branches exist (dev/qa/demo/prod). - No
 * historical backdating of Git events. - Webhook layer will handle ongoing
 * activity (not this seeder). :contentReference[oaicite:0]{index=0}
 *
 * Hardening applied: - Idempotent: safe to run multiple times. - Correctly
 * bases env branches off default branch head (not the zero object). - Avoids
 * creating empty commits that don't update ref (ADO may reject/ignore). - Saves
 * state only when changes are meaningful.
 */
@Service
public class RepoSeederService {

	private final FileStateRepository repository;
	private final AdminIdentityProvider adminIdentityProvider;
	private final AdoRestClient adoClient;
	private final AdoProperties properties;

	private static final List<String> ENV_BRANCHES = List.of("dev", "qa", "demo", "prod");
	private static final String ZERO_OBJECT_ID = "0000000000000000000000000000000000000000";

	@Value("${mock.seed.repo.enabled:true}")
	private boolean repoSeedingEnabled;

	public RepoSeederService(FileStateRepository repository, AdoRestClient adoClient, AdoProperties properties,
			AdminIdentityProvider adminIdentityProvider) {

		this.repository = repository;
		this.adoClient = adoClient;
		this.properties = properties;
		this.adminIdentityProvider = adminIdentityProvider;
	}

	/**
	 * Seed repo + branches if required.
	 *
	 * Returns true only when it performed actions that should be considered
	 * "seeding work" (repo created / default branch created / env branches created
	 * / state updated).
	 */
	public boolean seedIfRequired() {

		if (!repoSeedingEnabled) {
			return false;
		}

		MockState state = repository.load();
		if (state == null || state.collectionDetails == null) {
			return false;
		}

		if (state.repo == null || state.repo.repoName == null || state.repo.repoName.isBlank()) {
			return false;
		}

		String pat = adminIdentityProvider.getAdmin().pat;
		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();
		String repoName = state.repo.repoName;

		// Default branch fallback
		String defaultBranch = (state.repo.defaultBranch == null || state.repo.defaultBranch.isBlank()) ? "main"
				: state.repo.defaultBranch.trim();

		boolean didWork = false;

		JsonNode repoResponse = resolveRepo(pat, project, apiVersion, repoName);
		String repoId = repoResponse.path("id").asText(null);
		if (repoId == null || repoId.isBlank()) {
			// If repo resolution failed, don't mutate state.
			return false;
		}

		// Ensure default branch exists (idempotent)
		boolean defaultCreated = ensureDefaultBranchExists(pat, project, apiVersion, repoId, defaultBranch);
		didWork = didWork || defaultCreated;

		// Use the real head of default branch as the base for env branches
		String defaultHead = getBranchHeadObjectId(pat, project, repoId, defaultBranch);

		// If default branch still has no head, we cannot create branches.
		if (defaultHead != null) {
			for (String branch : ENV_BRANCHES) {
				boolean created = ensureBranchExistsFromBase(pat, project, apiVersion, repoId, defaultHead, branch);
				didWork = didWork || created;
			}
		}

		// Update state (only if changes or missing info)
		boolean stateChanged = false;

		if (state.repo.repoId == null || !repoId.equals(state.repo.repoId)) {
			state.repo.repoId = repoId;
			stateChanged = true;
		}

		if (defaultHead != null
				&& (state.repo.defaultBranchHead == null || !defaultHead.equals(state.repo.defaultBranchHead))) {
			state.repo.defaultBranchHead = defaultHead;
			stateChanged = true;
		}

		if (state.repo.createdAt == null) {
			state.repo.createdAt = Instant.now();
			stateChanged = true;
		}

		if (stateChanged) {
			repository.save(state);
		}

		return didWork || stateChanged;
	}

	/**
	 * Resolve repository by name, creating if missing. Kept intentionally simple;
	 * if GET fails for reasons other than 404, create may still fail.
	 */
	private JsonNode resolveRepo(String pat, String project, String apiVersion, String repoName) {

		String getUri = "/" + project + "/_apis/git/repositories/" + repoName + "?api-version=" + apiVersion;

		try {
			return adoClient.get(pat, getUri);
		} catch (Exception ex) {
			Map<String, Object> body = Map.of("name", repoName);
			String createUri = "/" + project + "/_apis/git/repositories?api-version=" + apiVersion;
			return adoClient.post(pat, createUri, body);
		}
	}

	/**
	 * Ensures the default branch exists by making an initial push/commit if needed.
	 *
	 * Returns true if it performed the initial push (created the branch), false if
	 * branch already existed or create was not needed.
	 */
	private boolean ensureDefaultBranchExists(String pat, String project, String apiVersion, String repoId,
			String defaultBranch) {

		String head = getBranchHeadObjectId(pat, project, repoId, defaultBranch);
		if (head != null) {
			return false; // already exists
		}

		String branchRef = "refs/heads/" + defaultBranch;

		Map<String, Object> initialPush = new HashMap<>();
		initialPush.put("refUpdates", List.of(Map.of("name", branchRef, "oldObjectId", ZERO_OBJECT_ID)));

		// Create at least one file so the commit is real.
		List<Map<String, Object>> changes = List.of(Map.of("changeType", "add", "item", Map.of("path", "/README.md"),
				"newContent",
				Map.of("content", "# Mock Repository\nGenerated by ADO Mock Engine\n", "contentType", "rawtext")));

		initialPush.put("commits", List.of(Map.of("comment", "Initial commit", "changes", changes)));

		String pushUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pushes?api-version=" + apiVersion;
		adoClient.post(pat, pushUri, initialPush);

		return true;
	}

	/**
	 * Ensures env branch exists by creating a ref pointing at the provided base
	 * commit.
	 *
	 * Key fix vs old code: - Old code pushed an "empty commit" with
	 * oldObjectId=ZERO but did not specify newObjectId/base, which can be
	 * rejected/ignored by ADO and does not guarantee the ref points at default
	 * branch.
	 *
	 * This method uses the refs API to create the branch ref directly.
	 *
	 * Returns true if created, false if already exists or could not create due to
	 * missing base.
	 */
	private boolean ensureBranchExistsFromBase(String pat, String project, String apiVersion, String repoId,
			String baseObjectId, String branchName) {

		String existingHead = getBranchHeadObjectId(pat, project, repoId, branchName);
		if (existingHead != null) {
			return false; // already exists
		}

		if (baseObjectId == null || baseObjectId.isBlank()) {
			return false;
		}

		List<Map<String, Object>> body = List.of(
				Map.of("name", "refs/heads/" + branchName, "oldObjectId", ZERO_OBJECT_ID, "newObjectId", baseObjectId));

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/refs?api-version=" + apiVersion;
		adoClient.post(pat, uri, body);

		return true;
	}

	/**
	 * Fetch branch head objectId (commit id) for a given branch. Returns null if
	 * ref not present.
	 */
	private String getBranchHeadObjectId(String pat, String project, String repoId, String branchName) {

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/refs?filter=heads/" + branchName
				+ "&api-version=" + properties.getApiVersion();

		JsonNode resp = adoClient.get(pat, uri);
		JsonNode value = resp.path("value");

		if (!value.isArray() || value.size() == 0) {
			return null;
		}

		return value.get(0).path("objectId").asText(null);
	}

	public void ensureRepoActivityForSprint(MockState state, MockState.Sprint sprint, String correlation) {

		if (!repoSeedingEnabled)
			return;

		if (state == null || sprint == null)
			return;

		if (state.repo == null || state.repo.repoId == null)
			return;

		String pat = adminIdentityProvider.getAdmin().pat;
		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();
		String repoId = state.repo.repoId;

		String baseBranch = "dev";
		String baseHead = getBranchHeadObjectId(pat, project, repoId, baseBranch);

		// If dev branch does not exist, we cannot branch from it
		if (baseHead == null || baseHead.isBlank())
			return;

		String featureBranch = "feature/sprint-" + sprint.sprintNumber + "-1";

		// Idempotency: skip if branch already exists
		if (getBranchHeadObjectId(pat, project, repoId, featureBranch) != null) {
			return;
		}

		String pushUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pushes?api-version=" + apiVersion;

		Map<String, Object> pushBody = Map.of("refUpdates",
				List.of(Map.of("name", "refs/heads/" + featureBranch, "oldObjectId", ZERO_OBJECT_ID)), "commits",
				List.of(Map.of("comment", "Sprint " + sprint.sprintNumber + " mock activity", "parents",
						List.of(baseHead), "changes",
						List.of(Map.of("changeType", "add", "item",
								Map.of("path", "/sprint-" + sprint.sprintNumber + ".txt"), "newContent",
								Map.of("content", "Sprint activity. Correlation: " + correlation, "contentType",
										"rawtext"))))));

		adoClient.post(pat, pushUri, pushBody);
	}
}