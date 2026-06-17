package com.example.adomock.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class RepoWebhookEngine {

	private static final Logger log = LoggerFactory.getLogger(RepoWebhookEngine.class);

	private final FileStateRepository repository;
	private final UserIdentityProvider identityProvider;
	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final IdentityResolverService identityResolverService;

	private static final String DEV_BRANCH = "dev";
	private static final String ZERO = "0000000000000000000000000000000000000000";

	public RepoWebhookEngine(FileStateRepository repository, UserIdentityProvider identityProvider,
			AdoRestClient adoClient, AdoProperties properties, IdentityResolverService identityResolverService) {
		this.repository = repository;
		this.identityProvider = identityProvider;
		this.adoClient = adoClient;
		this.properties = properties;
		this.identityResolverService = identityResolverService;
	}

	private void refreshUserIdentity(String username) {
		try {
			MockState state = repository.load();
			if (state == null || state.users == null) return;
			for (MockState.User u : state.users) {
				if (u != null && username.equals(u.username)) {
					String oldId = u.id;
					u.id = null;
					String newId = identityResolverService.resolveIdentityDescriptor(u);
					if (newId != null) {
						log.info("user={} | action=refreshIdentity | newId={}", username, newId);
					} else {
						u.id = oldId;
						log.warn("user={} | action=refreshIdentity | identity not found in ADO", username);
					}
					return;
				}
			}
		} catch (Exception ex) {
			log.warn("user={} | action=refreshIdentity | error={}", username, ex.getMessage());
		}
	}

	public void runWebhookCycle(String correlationId, Random random) {

		MockState state = repository.load();
		if (!isReady(state)) {
			return;
		}

		String repoId = state.repo.repoId;
		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();

		int roll = random.nextInt(100);

		// 1% create new feature branch + PR
		if (roll < 1) {
			MockState.User actor = randomUser();
			if (actor != null) {
				createFeatureBranchAndPR(state, actor.pat, actor.username, project, apiVersion, repoId, correlationId, random);
			}
		}

		// 24% create PR from existing branch
		else if (roll < 25) {
			MockState.User actor = randomUser();
			if (actor != null) {
				createPRFromExistingBranch(state, actor.pat, actor.username, project, apiVersion, repoId, random);
			}
		}

		// 49% push to feature
		else if (roll < 50) {
			MockState.User actor = randomUser();
			if (actor != null) {
				pushToRandomFeatureBranch(state, actor.pat, actor.username, project, apiVersion, repoId, correlationId, random);
			}
		}

		// 50% PR mutation
		else {
			MockState.User actor = randomUser();
			if (actor != null) {
				mutateRandomPR(state, actor.pat, actor.username, project, apiVersion, repoId, random, correlationId);
			}
		}

		// Promotions: choose a fresh actor each time so the audit trail looks less haunted
		MockState.User p1 = randomUser();
		if (p1 != null) promoteIfEligible(state, p1.pat, p1.username, project, apiVersion, repoId, "dev", "qa", 60, random);

		MockState.User p2 = randomUser();
		if (p2 != null) promoteIfEligible(state, p2.pat, p2.username, project, apiVersion, repoId, "qa", "demo", 40, random);

		MockState.User p3 = randomUser();
		if (p3 != null) promoteIfEligible(state, p3.pat, p3.username, project, apiVersion, repoId, "demo", "prod", 25, random);
	}

	// ============================================================
	// CREATE FEATURE BRANCH + PR
	// ============================================================

	private void createFeatureBranchAndPR(MockState state, String pat, String username, String project,
			String apiVersion, String repoId, String correlation, Random random) {

		String devHead = getBranchHead(pat, project, repoId, DEV_BRANCH);
		if (devHead == null || devHead.isBlank()) {
			return;
		}

		String suffix = sanitizeCorrelation(correlation, 6);
		String featureName = "feature/webhook-" + suffix;
		String featureRef = "refs/heads/" + featureName;

		// Create branch only if it does not already exist
		if (getBranchHead(pat, project, repoId, featureName) == null) {
			Map<String, Object> pushBody = Map
					.of("refUpdates", List.of(Map.of("name", featureRef, "oldObjectId", ZERO)), "commits",
							List.of(Map.of("comment", "Webhook init on " + featureName, "parents", List.of(devHead),
									"changes",
									List.of(Map.of("changeType", "add", "item",
											Map.of("path", "/init-" + suffix + ".md"), "newContent",
											Map.of("content", "# Webhook Feature\nCorrelation=" + correlation
													+ "\nCreatedAt=" + Instant.now() + "\n", "contentType",
													"rawtext"))))));

			String pushUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pushes?api-version=" + apiVersion;

			try {
				adoClient.post(pat, pushUri, pushBody);
				log.info("user={} | action=createBranch | branch={} | repoId={}", username, featureName, repoId);
			} catch (Exception ex) {
				Throwable root = ex.getCause() != null ? ex.getCause() : ex;
				log.error("user={} | action=createBranch | branch={} | repoId={} | error={}", username, featureName, repoId, root.getMessage());
				return;
			}
		}

		boolean isDraft = random.nextInt(100) < 20;
		Integer prId = createPullRequestIfMissing(pat, username, project, apiVersion, repoId, featureRef,
				"refs/heads/" + DEV_BRANCH, "Webhook PR " + safeText(correlation, "NA"),
				"Created by webhook engine for correlation " + safeText(correlation, "NA"), isDraft);

		if (prId == null || prId <= 0) {
			return;
		}

		ensureWorkItemLinked(state, pat, username, project, apiVersion, repoId, prId, random);
		addRandomReviewers(pat, username, project, apiVersion, repoId, prId, random, 1, 2);

		if (random.nextInt(100) < 65) {
			addPullRequestComment(pat, username, project, apiVersion, repoId, prId, "Automated PR created for correlation `"
					+ safeText(correlation, "NA") + "`. Linked work items and reviewers have been assigned.");
		}
	}

	// ============================================================
	// PUSH TO EXISTING FEATURE
	// ============================================================

	private void pushToRandomFeatureBranch(MockState state, String pat, String username, String project,
			String apiVersion, String repoId, String correlation, Random random) {

		String feature = findRandomFeatureBranch(pat, project, repoId, random);
		if (feature == null) {
			return;
		}

		String head = getBranchHead(pat, project, repoId, feature);
		if (head == null || head.isBlank()) {
			return;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("refUpdates", List.of(Map.of("name", "refs/heads/" + feature, "oldObjectId", head)));

		body.put("commits", List.of(Map.of("comment", "Webhook commit " + Instant.now(), "parents", List.of(head),
				"changes",
				List.of(Map.of("changeType", "add", "item",
						Map.of("path",
								"/webhook-" + safeFileStem(correlation) + "_" + Instant.now().toEpochMilli() + ".txt"),
						"newContent", Map.of("content", "Correlation=" + correlation + "\nUpdatedAt=" + Instant.now(),
								"contentType", "rawtext"))))));

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pushes?api-version=" + apiVersion;

		try {
			adoClient.post(pat, uri, body);
			log.info("user={} | action=pushCommit | branch={} | repoId={}", username, feature, repoId);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=pushCommit | branch={} | repoId={} | error={}", username, feature, repoId, root.getMessage());
		}
	}

	private void createPRFromExistingBranch(MockState state, String pat, String username, String project,
			String apiVersion, String repoId, Random random) {

		String feature = findRandomFeatureBranch(pat, project, repoId, random);
		if (feature == null) {
			return;
		}

		String sourceRef = "refs/heads/" + feature;
		String targetRef = "refs/heads/" + DEV_BRANCH;

		Integer prId = createPullRequestIfMissing(pat, username, project, apiVersion, repoId, sourceRef, targetRef,
				"PR from " + feature, "Auto-generated PR from existing branch", random.nextInt(100) < 20);

		if (prId == null) {
			return;
		}

		ensureWorkItemLinked(state, pat, username, project, apiVersion, repoId, prId, random);
		addRandomReviewers(pat, username, project, apiVersion, repoId, prId, random, 1, 2);

		if (random.nextInt(100) < 60) {
			addPullRequestComment(pat, username, project, apiVersion, repoId, prId,
					"Automated PR opened from existing branch `" + feature + "`.");
		}
	}

	// ============================================================
	// RANDOM PR MUTATION
	// ============================================================

	private void mutateRandomPR(MockState state, String pat, String username, String project, String apiVersion,
			String repoId, Random random, String correlationId) {

		String uri = "/" + project + "/_apis/git/repositories/" + repoId
				+ "/pullrequests?searchCriteria.status=active&api-version=" + apiVersion;

		JsonNode resp;
		try {
			resp = adoClient.get(pat, uri);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			String msg = root.getMessage();
			if (msg != null && msg.contains("TF401188")) {
				refreshUserIdentity(username);
			}
			log.warn("user={} | action=mutatePR | error={}", username, msg);
			return;
		}

		if (resp == null || !resp.has("value") || resp.path("value").size() == 0) {
			return;
		}

		JsonNode pr = resp.path("value").get(random.nextInt(resp.path("value").size()));
		int prId = pr.path("pullRequestId").asInt(0);
		if (prId <= 0) {
			return;
		}

		ensureWorkItemLinked(state, pat, username, project, apiVersion, repoId, prId, random);

		int actionRoll = random.nextInt(100);

		// 25% reviewer vote update
		if (actionRoll < 25) {
			log.info("user={} | action=updateVote | prId={}", username, prId);
			updateReviewerVote(pat, username, project, apiVersion, repoId, prId, random);
			return;
		}

		// 25% add reviewer comment
		else if (actionRoll < 50) {
			log.info("user={} | action=addComment | prId={}", username, prId);
			addPullRequestComment(pat, username, project, apiVersion, repoId, prId, randomReviewComment(random));
			return;
		}

		// 15% add another reviewer if possible
		else if (actionRoll < 65) {
			addRandomReviewers(pat, username, project, apiVersion, repoId, prId, random, 1, 3);
			return;
		}

		// 15% push new commit to branch
		else if (actionRoll < 80) {
			pushToRandomFeatureBranch(state, pat, username, project, apiVersion, repoId, correlationId, random);
			return;
		}

		// 5% nothing happens
		else if (actionRoll < 90) {
			return;
		}

		// 12% complete
		else if (actionRoll < 97) {
			completePullRequest(pat, username, project, apiVersion, repoId, prId, false);
			return;
		}

		// 3% abandon or close
		else {
			abandonePR(pat, username, project, apiVersion, repoId, prId);
			return;
		}
	}

	// ============================================================
	// HELPERS
	// ============================================================

	private boolean isReady(MockState state) {
		return state != null && state.repo != null && state.repo.repoId != null && state.collectionDetails != null
				&& state.collectionDetails.projectName != null;
	}

	private MockState.User randomUser() {
		try {
			MockState.User user = identityProvider.random();
			if (user == null) {
				log.error("No user available from identity provider");
			}
			return user;
		} catch (Exception ex) {
			log.error(ex.getMessage());
			return null;
		}
	}

	private String getBranchHead(String pat, String project, String repoId, String branch) {
		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/refs?filter=heads/" + branch
				+ "&api-version=" + properties.getApiVersion();

		JsonNode resp = adoClient.get(pat, uri);
		if (resp == null || !resp.has("value") || resp.path("value").size() == 0) {
			return null;
		}

		return resp.path("value").get(0).path("objectId").asText(null);
	}

	private String findRandomFeatureBranch(String pat, String project, String repoId, Random random) {
		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/refs?filter=heads/feature&api-version="
				+ properties.getApiVersion();

		JsonNode resp = adoClient.get(pat, uri);
		if (resp == null || !resp.has("value") || resp.path("value").size() == 0) {
			return null;
		}

		JsonNode node = resp.path("value").get(random.nextInt(resp.path("value").size()));
		String ref = node.path("name").asText(null);
		if (ref == null) {
			return null;
		}

		return ref.replace("refs/heads/", "");
	}

	private Integer createPullRequestIfMissing(String pat, String username, String project, String apiVersion,
			String repoId, String sourceRef, String targetRef, String title, String description, boolean isDraft) {

		String search = "/" + project + "/_apis/git/repositories/" + repoId
				+ "/pullrequests?searchCriteria.status=active" + "&searchCriteria.sourceRefName="
				+ encodeQuery(sourceRef) + "&searchCriteria.targetRefName=" + encodeQuery(targetRef) + "&api-version="
				+ apiVersion;

		JsonNode existing;
		try {
			existing = adoClient.get(pat, search);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			String msg = root.getMessage();
			if (msg != null && msg.contains("TF401188")) {
				refreshUserIdentity(username);
			}
			log.warn("user={} | action=createPR | source={} | target={} | skipped existing-check | error={}", username, sourceRef, targetRef, msg);
			return null;
		}
		if (existing != null && existing.has("value") && existing.path("value").size() > 0) {
			int existingPrId = existing.path("value").get(0).path("pullRequestId").asInt();
			log.info("user={} | action=createPR | prId={} | status=alreadyExists | source={} | target={}", username, existingPrId, sourceRef, targetRef);
			return existingPrId;
		}

		Map<String, Object> body = new HashMap<>();
		body.put("sourceRefName", sourceRef);
		body.put("targetRefName", targetRef);
		body.put("title", title);
		body.put("description", description);
		body.put("isDraft", isDraft);

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullrequests?api-version=" + apiVersion;

		try {
			JsonNode created = adoClient.post(pat, uri, body);
			int prId = created == null ? 0 : created.path("pullRequestId").asInt(0);
			if (prId > 0) {
				log.info("user={} | action=createPR | prId={} | status=created | source={} | target={} | draft={}", username, prId, sourceRef, targetRef, isDraft);
				return prId;
			}
			return null;
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=createPR | source={} | target={} | error={}", username, sourceRef, targetRef, root.getMessage());
			return null;
		}
	}

	private void promoteIfEligible(MockState state, String pat, String username, String project, String apiVersion,
			String repoId, String source, String target, int probabilityPct, Random random) {

		if (pat == null || random.nextInt(100) >= probabilityPct) {
			return;
		}

		String sourceRef = "refs/heads/" + source;
		String targetRef = "refs/heads/" + target;

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullrequests?searchCriteria.status=active"
				+ "&searchCriteria.sourceRefName=" + encodeQuery(sourceRef) + "&searchCriteria.targetRefName="
				+ encodeQuery(targetRef) + "&api-version=" + apiVersion;

		JsonNode resp;
		try {
			resp = adoClient.get(pat, uri);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			String msg = root.getMessage();
			if (msg != null && msg.contains("TF401188")) {
				refreshUserIdentity(username);
			}
			log.warn("user={} | action=promote | source={} | target={} | error={}", username, source, target, msg);
			return;
		}

		int prId = 0;

		if (resp != null && resp.has("value") && resp.path("value").size() > 0) {
			prId = resp.path("value").get(0).path("pullRequestId").asInt(0);
		} else {
			Integer createdPrId = createPullRequestIfMissing(pat, username, project, apiVersion, repoId, sourceRef,
					targetRef, "Promote " + source + " → " + target, "Auto-promotion via webhook engine", false);
			if (createdPrId != null) {
				prId = createdPrId;
			}
		}

		if (prId <= 0) {
			return;
		}

		log.info("user={} | action=promote | prId={} | source={} | target={}", username, prId, source, target);

		ensureWorkItemLinked(state, pat, username, project, apiVersion, repoId, prId, random);
		addRandomReviewers(pat, username, project, apiVersion, repoId, prId, random, 1, 2);

		if (random.nextInt(100) < 70) {
			addPullRequestComment(pat, username, project, apiVersion, repoId, prId,
					"Promotion flow triggered from `" + source + "` to `" + target + "`.");
		}

		completePullRequest(pat, username, project, apiVersion, repoId, prId, false);
	}

	private void completePullRequest(String pat, String username, String project, String apiVersion, String repoId,
			int prId, boolean deleteSourceBranch) {

		String prUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullrequests/" + prId + "?api-version="
				+ apiVersion;

		JsonNode prDetails;
		try {
			prDetails = adoClient.get(pat, prUri);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=completePR | prId={} | stage=fetchDetails | error={}", username, prId, root.getMessage());
			return;
		}

		if (prDetails == null) {
			return;
		}

		String lastSourceCommit = prDetails.path("lastMergeSourceCommit").path("commitId").asText(null);

		if (lastSourceCommit == null || lastSourceCommit.isBlank()) {
			return;
		}

		String mergeStrategy = prDetails.path("completionOptions").path("mergeStrategy").asText("noFastForward");

		Map<String, Object> completeBody = new HashMap<>();
		completeBody.put("status", "completed");
		completeBody.put("lastMergeSourceCommit", Map.of("commitId", lastSourceCommit));
		completeBody.put("completionOptions", Map.of("deleteSourceBranch", deleteSourceBranch, "mergeStrategy", mergeStrategy));

		String patchUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullrequests/" + prId
				+ "?api-version=" + apiVersion;

		try {
			adoClient.patch(pat, patchUri, completeBody);
			log.info("user={} | action=completePR | prId={} | mergeCommit={}", username, prId, lastSourceCommit);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			String msg = root.getMessage();
			if (msg != null && msg.contains("GitPullRequestUpdateRejectedByPolicyException")) {
				Map<String, Object> bypassOptions = new HashMap<>();
				bypassOptions.put("deleteSourceBranch", deleteSourceBranch);
				bypassOptions.put("mergeStrategy", mergeStrategy);
				bypassOptions.put("bypassPolicy", true);
				bypassOptions.put("bypassReason", "Mock engine: bypassing unsatisfied policies");

				Map<String, Object> bypassBody = new HashMap<>();
				bypassBody.put("status", "completed");
				bypassBody.put("lastMergeSourceCommit", Map.of("commitId", lastSourceCommit));
				bypassBody.put("completionOptions", bypassOptions);

				try {
					adoClient.patch(pat, patchUri, bypassBody);
					log.info("user={} | action=completePR | prId={} | bypassPolicy=true | mergeStrategy={}", username, prId, mergeStrategy);
				} catch (Exception retryEx) {
					Throwable retryRoot = retryEx.getCause() != null ? retryEx.getCause() : retryEx;
					log.warn("user={} | action=completePR | prId={} | bypass failed | error={}", username, prId, retryRoot.getMessage());
				}
			} else {
				log.warn("user={} | action=completePR | prId={} | error={}", username, prId, msg);
			}
		}
	}

	// ============================================================
	// REVIEWERS
	// ============================================================

	private void addRandomReviewers(String pat, String username, String project, String apiVersion, String repoId,
			int prId, Random random, int minReviewers, int maxReviewers) {

		List<MockState.User> allUsers;
		try {
			allUsers = identityProvider.allUsers();
		} catch (Exception ex) {
			return;
		}

		if (allUsers == null || allUsers.isEmpty()) {
			return;
		}

		int safeMin = Math.max(0, minReviewers);
		int safeMax = Math.max(safeMin, maxReviewers);
		int reviewerCount = safeMin + random.nextInt((safeMax - safeMin) + 1);

		Set<String> addedReviewerIds = new HashSet<>();

		for (int i = 0; i < reviewerCount; i++) {
			MockState.User reviewer = allUsers.get(random.nextInt(allUsers.size()));
			if (reviewer == null || reviewer.id == null || reviewer.id.isBlank()) {
				continue;
			}
			if (!addedReviewerIds.add(reviewer.id)) {
				continue;
			}

			String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId + "/reviewers/"
					+ reviewer.id + "?api-version=" + apiVersion;

			Map<String, Object> body = new HashMap<>();
			body.put("id", reviewer.id);

			if (random.nextInt(100) < 35) {
				body.put("isRequired", true);
			}

			try {
				adoClient.put(pat, uri, body);
				log.info("user={} | action=addReviewer | prId={} | reviewerId={} | reviewer={}", username, prId, reviewer.id, reviewer.username);
			} catch (Exception ex) {
				Throwable root = ex.getCause() != null ? ex.getCause() : ex;
				log.warn("user={} | action=addReviewer | prId={} | reviewerId={} | error={}", username, prId, reviewer.id, root.getMessage());
			}
		}
	}

	private void abandonePR(String pat, String username, String project, String apiVersion, String repoId, int prId) {
		String patchUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullrequests/" + prId
				+ "?api-version=" + apiVersion;

		try {
			adoClient.patch(pat, patchUri, Map.of("status", "abandoned"));
			log.info("user={} | action=abandonPR | prId={}", username, prId);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=abandonPR | prId={} | error={}", username, prId, root.getMessage());
		}
	}

	private void ensureWorkItemLinked(MockState state, String pat, String username, String project, String apiVersion,
			String repoId, int prId, Random random) {

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId
				+ "/workitems?api-version=" + apiVersion;

		JsonNode resp;

		try {
			resp = adoClient.get(pat, uri);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=linkWorkItems | prId={} | stage=checkExisting | error={}", username, prId, root.getMessage());
			return;
		}

		if (resp != null && resp.has("value") && resp.path("value").size() > 0) {
			return; // already linked
		}

		List<Integer> ids = pickRandomWorkItemIds(state, random, 1, 2);

		if (ids.isEmpty()) {
			return;
		}

		log.info("user={} | action=linkWorkItems | prId={} | workItemIds={}", username, prId, ids);

		linkWorkItemsToPullRequest(state, pat, username, project, apiVersion, repoId, prId, ids);
	}

	// ============================================================
	// WORK ITEM LINKING
	// ============================================================

	private void linkWorkItemsToPullRequest(MockState state, String pat, String username, String project,
			String apiVersion, String repoId, int prId, List<Integer> workItemIds) {

		if (workItemIds == null || workItemIds.isEmpty()) {
			return;
		}

		String projectId = resolveProjectId(state, project);
		String prArtifactLink = buildPullRequestArtifactLink(projectId, repoId, prId);

		for (Integer workItemId : workItemIds) {
			if (workItemId == null || workItemId <= 0) {
				continue;
			}

			String uri = "/" + project + "/_apis/wit/workitems/" + workItemId + "?api-version=" + apiVersion;

			List<Map<String, Object>> patchBody = List.of(Map.of("op", "add", "path", "/relations/-", "value", Map
					.of("rel", "ArtifactLink", "url", prArtifactLink, "attributes", Map.of("name", "Pull Request"))));

			try {
				// IMPORTANT: this must send Content-Type: application/json-patch+json
				adoClient.patchJsonPatch(pat, uri, patchBody);
			} catch (Exception ex) {
				Throwable root = ex.getCause() != null ? ex.getCause() : ex;
				log.error("user={} | action=linkWorkItems | prId={} | workItemId={} | error={}", username, prId, workItemId, root.getMessage());
			}
		}
	}

	private List<Integer> pickRandomWorkItemIds(MockState state, Random random, int min, int max) {

		if (state == null || state.programIterations == null) {
			return List.of();
		}

		List<String> sprintWorkItems = new java.util.ArrayList<>();

		for (MockState.ProgramIteration pi : state.programIterations) {
			if (pi == null || pi.sprints == null) {
				continue;
			}

			for (MockState.Sprint sprint : pi.sprints) {

				if (sprint == null || sprint.workItemIds == null) {
					continue;
				}

				if (sprint.sprintNumber == state.currentSprintNumber) {
					sprintWorkItems.addAll(sprint.workItemIds);
				}
			}
		}

		if (sprintWorkItems.isEmpty()) {
			return List.of();
		}

		int safeMin = Math.max(0, min);
		int safeMax = Math.max(safeMin, max);
		int count = safeMin + random.nextInt((safeMax - safeMin) + 1);

		List<Integer> result = new java.util.ArrayList<>();
		Set<String> seen = new HashSet<>();

		while (result.size() < count && seen.size() < sprintWorkItems.size()) {

			String id = sprintWorkItems.get(random.nextInt(sprintWorkItems.size()));

			if (id == null || id.isBlank() || !seen.add(id)) {
				continue;
			}

			try {
				result.add(Integer.parseInt(id));
			} catch (NumberFormatException ignored) {
			}
		}

		return result;
	}

	private String buildPullRequestArtifactLink(String projectId, String repoId, int prId) {
		return "vstfs:///Git/PullRequestId/" + projectId + "%2F" + repoId + "%2F" + prId;
	}

	private String resolveProjectId(MockState state, String fallbackProjectName) {
		try {
			if (state != null && state.collectionDetails != null && state.collectionDetails.projectId != null
					&& !state.collectionDetails.projectId.isBlank()) {
				return state.collectionDetails.projectId;
			}
		} catch (Exception ignored) {
		}

		return fallbackProjectName;
	}

	// ============================================================
	// PR THREAD COMMENTS
	// ============================================================

	private void addPullRequestComment(String pat, String username, String project, String apiVersion, String repoId,
			int prId, String content) {

		if (content == null || content.isBlank()) {
			return;
		}

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId
				+ "/threads?api-version=" + apiVersion;

		Map<String, Object> body = Map.of("comments",
				List.of(Map.of("parentCommentId", 0, "content", content, "commentType", 1)), "status", 1);

		try {
			adoClient.post(pat, uri, body);
			log.info("user={} | action=addComment | prId={}", username, prId);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=addComment | prId={} | error={}", username, prId, root.getMessage());
		}
	}

	private void updateReviewerVote(String pat, String username, String project, String apiVersion, String repoId,
			int prId, Random random) {

		String uri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId
				+ "/reviewers?api-version=" + apiVersion;

		JsonNode resp;

		try {
			resp = adoClient.get(pat, uri);
		} catch (Exception ex) {
			return;
		}

		if (resp == null || !resp.has("value") || resp.path("value").size() == 0) {
			return;
		}

		JsonNode reviewer = resp.path("value").get(random.nextInt(resp.path("value").size()));
		String reviewerId = reviewer.path("id").asText(null);

		if (reviewerId == null) {
			return;
		}

		int vote = pickReviewerVote(random);

		String patchUri = "/" + project + "/_apis/git/repositories/" + repoId + "/pullRequests/" + prId + "/reviewers/"
				+ reviewerId + "?api-version=" + apiVersion;

		try {
			adoClient.put(pat, patchUri, Map.of("vote", vote));
			log.info("user={} | action=updateVote | prId={} | reviewerId={} | vote={}", username, prId, reviewerId, vote);
		} catch (Exception ex) {
			Throwable root = ex.getCause() != null ? ex.getCause() : ex;
			log.error("user={} | action=updateVote | prId={} | reviewerId={} | error={}", username, prId, reviewerId, root.getMessage());
		}
	}

	private String randomReviewComment(Random random) {
		List<String> comments = List.of("Please verify edge cases before completing this PR.",
				"Security review note: validate any external input paths.",
				"Looks reasonable at a glance, but tests should be reviewed.",
				"Please confirm linked work items are still in the correct state.",
				"Build health check recommended before merge.",
				"Corporate ritual complete: reviewer has emitted a comment.");
		return comments.get(random.nextInt(comments.size()));
	}

	// ============================================================
	// UTILS
	// ============================================================

	private String safeFileStem(String correlation) {
		if (correlation == null || correlation.isBlank()) {
			return "na";
		}
		String s = correlation.replaceAll("[^a-zA-Z0-9\\-]", "");
		return (s.length() > 24) ? s.substring(0, 24) : s;
	}

	private int pickReviewerVote(Random random) {

		int r = random.nextInt(100);

		if (r < 40)
			return 10; // approve
		if (r < 60)
			return 5; // approve with suggestions
		if (r < 80)
			return -5; // waiting for author
		return -10; // reject
	}

	private String sanitizeCorrelation(String correlation, int maxLen) {
		String suffix = (correlation == null) ? "na" : correlation;
		suffix = suffix.replaceAll("[^a-zA-Z0-9\\-]", "");
		if (suffix.isBlank()) {
			suffix = "na";
		}
		return suffix.length() > maxLen ? suffix.substring(0, maxLen) : suffix;
	}

	private String safeText(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		return value;
	}

	private String encodeQuery(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replace(" ", "%20").replace("#", "%23");
	}
}
