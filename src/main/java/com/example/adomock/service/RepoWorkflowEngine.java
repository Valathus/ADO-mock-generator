package com.example.adomock.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Corporate Git-flow-ish simulator:
 *
 * Historical (runWorkflowCycle): feature/* -> dev -> qa -> demo -> prod
 *
 * Webhook (runWebhookCycle): - pushes to an existing feature branch (or
 * discovers one) - ensures PR exists feature->dev - adds reviewers/comments -
 * randomly completes/abandons feature PR - if dev changed, ensures PR exists
 * dev->qa and randomly completes/abandons - occasionally adds noise by
 * abandoning a random active PR
 *
 * NOTE: - This class assumes RepoSeederService already created: main, dev, qa,
 * demo, prod. - This class avoids caching state at construction to prevent
 * stale values.
 */
@Service
public class RepoWorkflowEngine {

	private static final Logger log = LoggerFactory.getLogger(RepoWorkflowEngine.class);

	private static final String DEFAULT_BRANCH = "main";
	private static final String INTEGRATION_BRANCH = "dev";

	private final FileStateRepository repository;
	private final UserIdentityProvider identityProvider;
	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final IdentityResolverService identityResolverService;

	public RepoWorkflowEngine(FileStateRepository repository, UserIdentityProvider identityProvider,
			AdoRestClient adoClient, AdoProperties properties, IdentityResolverService identityResolverService) {

		this.repository = repository;
		this.identityProvider = identityProvider;
		this.adoClient = adoClient;
		this.properties = properties;
		this.identityResolverService = identityResolverService;
	}

	// ======================================================
	// SEEDER MODE — CLEAN INITIAL ACTIVITY ONLY
	// ======================================================

	public void runWorkflowCycle(LocalDate simulatedDate) {

		MockState state = repository.load();

		if (!isReady(state))
			return;

		List<MockState.User> users = enabledUsers(state);
		if (users.isEmpty())
			return;

		String repoId = state.repo.repoId;
		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();

		String devHead = getBranchHeadObjectId(state.admin.pat, project, repoId, INTEGRATION_BRANCH);
		if (devHead == null)
			return;

		MockState.User author = users.get(0);
		String workItemId = pickWorkItem(state, new Random());
		if (workItemId == null)
			return;

		String featureBranch = "feature/WI-" + workItemId + "-" + UUID.randomUUID().toString().substring(0, 6);

		String featureRef = "refs/heads/" + featureBranch;

		createBranchAndCommits(author.pat, project, apiVersion, repoId, featureRef, devHead, workItemId, 1, false,
				simulatedDate);

		int prId = createPullRequest(author.pat, project, apiVersion, repoId, featureRef,
				"refs/heads/" + INTEGRATION_BRANCH, workItemId, author.username);

		completePullRequestSafe(author.pat, project, apiVersion, repoId, prId);

		repository.save(state);
	}

	// ======================================================
	// UTILITIES
	// ======================================================

	private boolean isReady(MockState state) {

		if (state == null)
			return false;

		if (state.repo == null || state.repo.repoId == null)
			return false;

		if (state.collectionDetails == null || state.collectionDetails.projectName == null)
			return false;

		if (state.admin == null || state.admin.pat == null)
			return false;

		if (state.users == null || state.users.isEmpty())
			return false;

		if (collectAllWorkItemIds(state).isEmpty())
			return false;

		return true;
	}

	private List<String> collectAllWorkItemIds(MockState state) {

		if (state.programIterations == null)
			return List.of();

		return state.programIterations.stream().flatMap(pi -> pi.sprints.stream())
				.flatMap(sprint -> sprint.workItemIds.stream()).collect(Collectors.toList());
	}

	private List<MockState.User> enabledUsers(MockState state) {
		return state.users.stream().filter(u -> u.enabled).collect(Collectors.toList());
	}

	private String pickWorkItem(MockState state, Random random) {

		List<String> all = collectAllWorkItemIds(state);

		if (all.isEmpty())
			return null;

		return all.get(random.nextInt(all.size()));
	}

	private String getBranchHeadObjectId(String pat, String project, String repoId, String branchName) {

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/refs?filter=heads/" + branchName
				+ "&api-version=" + properties.getApiVersion();

		JsonNode resp = adoClient.get(pat, uri);

		if (resp == null || !resp.has("value") || resp.path("value").size() == 0)
			return null;

		return resp.path("value").get(0).path("objectId").asText();
	}

	// ======================================================
	// GIT OPERATIONS
	// ======================================================

	private void createBranchAndCommits(String pat, String project, String apiVersion, String repoId, String featureRef,
			String baseHead, String workItemId, int commitCount, boolean branchExists, LocalDate simulatedDate) {

		String currentHead = baseHead;
		Instant simulatedInstant = simulatedDate.atStartOfDay(ZoneOffset.UTC).toInstant();

		for (int i = 1; i <= commitCount; i++) {

			String oldObjectId = (i == 1) ? (branchExists ? baseHead : "0000000000000000000000000000000000000000")
					: currentHead;

			Map<String, Object> pushBody = new HashMap<>();
			pushBody.put("refUpdates", List.of(Map.of("name", featureRef, "oldObjectId", oldObjectId)));

			String path = "/wi-" + workItemId + "/feature.txt";

			List<Map<String, Object>> changes = List
					.of(Map.of(
							"changeType", (i == 1 ? "add" : "edit"), "item", Map.of("path", path), "newContent", Map.of(
									"content", "WorkItem=" + workItemId + "\nCommit=" + i + "\nSimulatedDate="
											+ simulatedDate + "\nTimestamp=" + simulatedInstant,
									"contentType", "rawtext")));

			pushBody.put("commits",
					List.of(Map.of("comment", "AB#" + workItemId + ": Commit " + i, "changes", changes)));

			String pushUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pushes?api-version=" + apiVersion;

			JsonNode resp = adoClient.post(pat, pushUri, pushBody);

			if (resp == null || !resp.has("commits") || resp.path("commits").isEmpty())
				return;

			currentHead = resp.path("commits").get(0).path("commitId").asText();
		}
	}

	private int createPullRequest(String pat, String project, String apiVersion, String repoId, String sourceRef,
			String targetRef, String workItemId, String createdBy) {

		Map<String, Object> body = new HashMap<>();
		body.put("sourceRefName", sourceRef);
		body.put("targetRefName", targetRef);
		body.put("title", "AB#" + workItemId + ": Merge changes");
		body.put("description", "Auto generated by " + createdBy);

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullrequests?api-version=" + apiVersion;

		JsonNode resp = adoClient.post(pat, uri, body);
		return resp.path("pullRequestId").asInt();
	}

	private boolean completePullRequestSafe(String pat, String project, String apiVersion, String repoId, int prId) {

		try {
			String getUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId
					+ "?api-version=" + apiVersion;

			JsonNode pr = adoClient.get(pat, getUri);

			String sourceRef = pr.path("sourceRefName").asText();
			String branchName = sourceRef.replace("refs/heads/", "");

			String refUri = "/" + project + "/_apis/git/repositories/" + repoId + "/refs?filter=heads/" + branchName
					+ "&api-version=" + apiVersion;

			JsonNode refResp = adoClient.get(pat, refUri);

			if (!refResp.has("value") || refResp.path("value").size() == 0)
				return false;

			String latestCommitId = refResp.path("value").get(0).path("objectId").asText();

			Map<String, Object> body = new HashMap<>();
			body.put("status", "completed");
			body.put("lastMergeSourceCommit", Map.of("commitId", latestCommitId));
			body.put("completionOptions", Map.of("deleteSourceBranch", true, "mergeCommitMessage",
					"Auto-completed by mock engine", "squashMerge", false));

			String patchUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId
					+ "?api-version=" + apiVersion;

			adoClient.patch(pat, patchUri, body);
			return true;

		} catch (Exception ex) {
			log.info("PR stale or already completed. prId={}", prId);
			return false;
		}
	}
}