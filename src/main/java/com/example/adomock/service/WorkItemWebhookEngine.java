package com.example.adomock.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.AdminIdentityProvider;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * WorkItem webhook simulation engine: - Mutates existing work items with a
 * weighted mix. - (NEW) 20% chance to CREATE a new work item instead of
 * updating.
 *
 * Hardening / fixes applied: - Uses projectName (not projectId) for work item
 * create/update URIs. - Iteration changes target real sprint iteration paths
 * based on state.programIterations/currentSprintNumber (instead of fake
 * "\\SprintN"). - Tag updates append (read current tags) instead of
 * overwriting. - Link updates use sprint-scoped IDs (no state.workItemIds
 * global, which doesn't exist now). - State change uses correct work item type
 * endpoint under projectName. - Adds correlation into History + Tags for
 * traceability. - Adds guards for empty user list / missing iterations /
 * missing work items.
 */
@Service
public class WorkItemWebhookEngine {

	// 20% chance to CREATE a new work item instead of updating an existing one
	private static final int CREATE_NEW_WORK_ITEM_PCT = 20;

	// Update mix (applies when we are updating an existing work item)
	private static final int CHANGE_STATE_PCT = 30;
	private static final int CHANGE_ASSIGNEE_PCT = 18;
	private static final int CARRYOVER_PCT = 12;
	private static final int ADD_COMMENT_ONLY_PCT = 10;
	private static final int ADD_TAG_PCT = 10;
	private static final int ADD_LINK_PCT = 10;
	private static final int SET_DUE_DATE_PCT = 5;
	private static final int ADD_BLOCKED_BY_PCT = 5;

	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final UserIdentityProvider identityProvider;
	private final AdminIdentityProvider adminIdentityProvider;
	private final FileStateRepository repository;

	@Value("${mock.webhook.workitems.createPct:20}")
	private int createPctOverride;

	public WorkItemWebhookEngine(AdoRestClient adoClient, AdoProperties properties,
			UserIdentityProvider identityProvider, AdminIdentityProvider adminIdentityProvider,
			FileStateRepository repository) {

		this.adoClient = adoClient;
		this.properties = properties;
		this.identityProvider = identityProvider;
		this.adminIdentityProvider = adminIdentityProvider;
		this.repository = repository;
	}

	/**
	 * Called by mutation layer for a chosen workItemId. With 20% chance, creates a
	 * brand new work item (still triggers webhooks). Otherwise applies an update
	 * mutation to the provided workItemId.
	 */
	public void runWebhookUpdate(String workItemId, String correlationId, Random random) {

		MockState state = repository.load();
		if (state == null || state.collectionDetails == null || state.collectionDetails.projectName == null) {
			return;
		}

		// Actor identity for the REST call
		MockState.User actor = identityProvider.next();
		if (actor == null || actor.pat == null) {
			return;
		}
		String pat = actor.pat;

		// 20% create chance (configurable)
		int createPct = (createPctOverride >= 0 && createPctOverride <= 100) ? createPctOverride
				: CREATE_NEW_WORK_ITEM_PCT;

		if (random.nextInt(100) < createPct) {
			createWorkItemViaWebhook(state, pat, correlationId, random);
			return;
		}

		// If no workItemId provided, nothing to update
		if (workItemId == null || workItemId.isBlank()) {
			return;
		}

		int roll = random.nextInt(100);

		List<Map<String, Object>> patch = new ArrayList<>();

		int threshold = 0;
		if (roll < (threshold += CHANGE_STATE_PCT)) {
			changeState(state, workItemId, pat, patch, random);

		} else if (roll < (threshold += CHANGE_ASSIGNEE_PCT)) {
			changeAssignee(state, patch, random);

		} else if (roll < (threshold += CARRYOVER_PCT)) {
			carryOverOnSprintClose(state, patch, random);

		} else if (roll < (threshold += ADD_COMMENT_ONLY_PCT)) {
			addCommentOnly(patch, correlationId);

		} else if (roll < (threshold += ADD_TAG_PCT)) {
			addTag(state, workItemId, pat, patch, random, correlationId);

		} else if (roll < (threshold += SET_DUE_DATE_PCT)) {
			setDueDate(state, patch, random);

		} else if (roll < (threshold += ADD_BLOCKED_BY_PCT)) {
			addBlockedByLink(state, workItemId, patch, random);

		} else {
			addLink(state, workItemId, patch, random);
		}

		if (patch.isEmpty())
			return;

		// IMPORTANT: For work item APIs, use project name, not projectId.
		String uri = "/" + state.collectionDetails.projectName + "/_apis/wit/workitems/" + workItemId + "?api-version="
				+ properties.getApiVersion();

		patchWorkItemWithFallback(pat, uri, patch);
	}

	/*
	 * ============================================================ CREATE (20%
	 * path) ============================================================
	 */

	private void createWorkItemViaWebhook(MockState state, String pat, String correlationId, Random random) {

		// Choose an iteration path (prefer current sprint)
		String iterationPath = pickIterationPathForCreate(state, random);
		if (iterationPath == null) {
			iterationPath = state.collectionDetails.projectName; // fallback: project root
		}

		MockState.User assignee = identityProvider.next();
		String assignedTo = (assignee != null) ? assignee.username : null;

		String title = "Webhook-created item " + shortHash(correlationId) + " @ " + Instant.now().toString();

		List<Map<String, Object>> patch = new ArrayList<>();
		patch.add(Map.of("op", "add", "path", "/fields/System.Title", "value", title));
		patch.add(Map.of("op", "add", "path", "/fields/System.Description", "value",
				"Created by webhook simulator. correlation=" + correlationId));
		patch.add(Map.of("op", "add", "path", "/fields/System.IterationPath", "value", iterationPath));
		if (assignedTo != null && !assignedTo.isBlank()) {
			patch.add(Map.of("op", "add", "path", "/fields/System.AssignedTo", "value", assignedTo));
		}
		patch.add(
				Map.of("op", "add", "path", "/fields/System.Tags", "value", "mock; webhook; mockRun:" + correlationId));
		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				"Created via webhook simulation | " + correlationId));

		// 60% of new work items get a due date within the next 30-90 days
		if (random.nextInt(100) < 60) {
			int daysAhead = 30 + random.nextInt(61);
			LocalDate dueDate = LocalDate.now().plusDays(daysAhead);
			String dueDateStr = dueDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00.000Z";
			patch.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.Scheduling.DueDate", "value", dueDateStr));
		}

		// Use common type name used in your system ("Task" is always safe-ish). If you
		// want, switch to "$Task" literal.
		String workItemType = "$Task";

		String uri = "/" + state.collectionDetails.projectName + "/_apis/wit/workitems/" + workItemType
				+ "?api-version=" + properties.getApiVersion() + "&bypassRules=true&suppressNotifications=true";

		try {
			JsonNode created = postWorkItemWithFallback(pat, uri, patch);
			String id = created.path("id").asText(null);
			if (id != null && !id.isBlank()) {
				// Add to current sprint bucket if we can resolve sprintNumber from iteration
				// path
				addCreatedWorkItemToBestSprintBucket(state, id);
				repository.save(state);
			}
		} catch (Exception ignored) {
			// Self-healing / best-effort: creation failure just means no event this time.
		}
	}

	private JsonNode postWorkItemWithFallback(String pat, String uri, List<Map<String, Object>> patch) {
		try {
			return adoClient.postJsonPatch(pat, uri, patch);
		} catch (RuntimeException ex) {
			if (!isUnauthorized(ex)) {
				throw ex;
			}
			return adoClient.postJsonPatch(adminIdentityProvider.getAdmin().pat, uri, patch);
		}
	}

	private void patchWorkItemWithFallback(String pat, String uri, List<Map<String, Object>> patch) {
		try {
			adoClient.patchJsonPatch(pat, uri, patch);
		} catch (RuntimeException ex) {
			if (!isUnauthorized(ex)) {
				throw ex;
			}
			adoClient.patchJsonPatch(adminIdentityProvider.getAdmin().pat, uri, patch);
		}
	}

	private boolean isUnauthorized(RuntimeException ex) {
		String message = ex.getMessage();
		return message != null && message.contains("401 UNAUTHORIZED");
	}

	private void addCreatedWorkItemToBestSprintBucket(MockState state, String id) {
		MockState.Sprint s = findSprintByNumber(state, state.currentSprintNumber);
		if (s != null) {
			if (s.workItemIds == null)
				s.workItemIds = new ArrayList<>();
			s.workItemIds.add(id);
			return;
		}
		// fallback: last sprint that has any work items
		MockState.Sprint last = findMostRecentSprintWithWorkItems(state);
		if (last != null) {
			if (last.workItemIds == null)
				last.workItemIds = new ArrayList<>();
			last.workItemIds.add(id);
		}
	}

	/*
	 * ============================================================ UPDATE mutations
	 * ============================================================
	 */

	private void changeAssignee(MockState state, List<Map<String, Object>> patch, Random random) {

		if (state.users == null || state.users.isEmpty())
			return;

		int enabledCount = (int) state.users.stream().filter(u -> u != null && u.enabled).count();
		if (enabledCount == 0)
			return;

		// Pick an enabled user deterministically-ish with random
		MockState.User user = state.users.stream().filter(u -> u != null && u.enabled)
				.skip(random.nextInt(enabledCount)).findFirst().orElse(null);

		if (user == null || user.username == null)
			return;

		patch.add(Map.of("op", "add", "path", "/fields/System.AssignedTo", "value", user.username));
		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				"Assignee changed via webhook simulation"));
	}

	private void carryOverOnSprintClose(MockState state, List<Map<String, Object>> patch, Random random) {

		// Only fire on sprint boundary
		if (!isSprintCloseWindow(state)) {
			return;
		}

		// 10% probability
		if (random.nextInt(100) >= 10) {
			return;
		}

		int nextSprint = state.currentSprintNumber + 1;

		MockState.Sprint target = findSprintByNumber(state, nextSprint);
		if (target == null) {
			return; // nothing to carry forward to
		}

		String iterationPath = buildIterationPath(state, target.sprintNumber);
		if (iterationPath == null) {
			return;
		}

		patch.add(Map.of("op", "add", "path", "/fields/System.IterationPath", "value", iterationPath));

		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				"Carry-over to Sprint " + nextSprint + " during sprint close"));
	}

	private boolean isSprintCloseWindow(MockState state) {
		// Example: treat the last sprint in each PI as “close window”
		int sprintsPerPI = state.dataLoadConfig != null ? state.dataLoadConfig.sprintsPerPI : 4;

		return (state.currentSprintNumber % sprintsPerPI) == 0;
	}

	private void addCommentOnly(List<Map<String, Object>> patch, String correlationId) {
		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value", "Webhook comment | " + correlationId));
	}

	private void addTag(MockState state, String workItemId, String pat, List<Map<String, Object>> patch, Random random,
			String correlationId) {

		// Choose a tag
		String tag = switch (random.nextInt(3)) {
		case 0 -> "backend";
		case 1 -> "frontend";
		default -> "hotfix";
		};

		// Read current tags so we append instead of overwrite
		String getUri = "/" + state.collectionDetails.projectName + "/_apis/wit/workitems/" + workItemId
				+ "?api-version=" + properties.getApiVersion();

		String existingTags = "";
		try {
			JsonNode wi = adoClient.get(pat, getUri);
			existingTags = wi.path("fields").path("System.Tags").asText("");
		} catch (Exception ignored) {
			// If GET fails, fall back to setting just the tag
		}

		String merged = mergeTags(existingTags, List.of(tag, "mockRun:" + correlationId, "webhook"));
		patch.add(Map.of("op", "add", "path", "/fields/System.Tags", "value", merged));
		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value", "Tag changed via webhook simulation"));
	}

	private void setDueDate(MockState state, List<Map<String, Object>> patch, Random random) {
		int daysAhead = 3 + random.nextInt(58);
		LocalDate dueDate = LocalDate.now().plusDays(daysAhead);
		String formatted = dueDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00.000Z";

		patch.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.Scheduling.DueDate", "value", formatted));
		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				"Due date set to " + dueDate + " via webhook simulation"));
	}

	private void addBlockedByLink(MockState state, String workItemId, List<Map<String, Object>> patch, Random random) {
		List<String> candidates = getCandidateWorkItemIds(state);
		if (candidates.isEmpty()) return;

		String other = candidates.get(random.nextInt(candidates.size()));
		if (other.equals(workItemId)) return;

		// 50% "Affects" (this item affects the other), 50% "Affected By" (this item is affected by the other)
		boolean isAffects = random.nextBoolean();
		String rel = isAffects ? "Microsoft.VSTS.Common.Affects-Forward" : "Microsoft.VSTS.Common.Affects-Reverse";
		String label = isAffects ? "Affects" : "Affected By";

		patch.add(Map.of("op", "add", "path", "/relations/-", "value",
				Map.of("rel", rel,
						"url", state.collectionDetails.url + "/"
								+ state.collectionDetails.projectName + "/_apis/wit/workItems/" + other,
						"attributes", Map.of("comment", label + " link via webhook simulation"))));

		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				label + " link added to work item " + other + " via webhook simulation"));
	}

	private void addLink(MockState state, String workItemId, List<Map<String, Object>> patch, Random random) {

		// Choose a target from current sprint if possible; else any sprint that has
		// items.
		List<String> candidates = getCandidateWorkItemIds(state);

		if (candidates.isEmpty())
			return;

		String other = candidates.get(random.nextInt(candidates.size()));

		if (other.equals(workItemId))
			return;

		patch.add(Map.of("op", "add", "path", "/relations/-", "value",
				Map.of("rel", "System.LinkTypes.Related", "url", state.collectionDetails.url + "/"
						+ state.collectionDetails.projectName + "/_apis/wit/workItems/" + other)));

		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				"Related link added via webhook simulation"));
	}

	private void changeState(MockState state, String workItemId, String pat, List<Map<String, Object>> patch,
			Random random) {

		String project = state.collectionDetails.projectName;

		String getUri = "/" + project + "/_apis/wit/workitems/" + workItemId + "?api-version="
				+ properties.getApiVersion();

		JsonNode workItem;
		try {
			workItem = adoClient.get(pat, getUri);
		} catch (Exception ex) {
			return;
		}

		JsonNode fields = workItem.path("fields");

		String type = fields.path("System.WorkItemType").asText();
		String currentState = fields.path("System.State").asText();

		if (type == null || type.isBlank())
			return;

		// Fetch workflow definition for this type
		String typeUri = "/" + project + "/_apis/wit/workitemtypes/" + type + "?api-version="
				+ properties.getApiVersion();

		JsonNode typeDef;
		try {
			typeDef = adoClient.get(pat, typeUri);
		} catch (Exception ex) {
			return;
		}

		JsonNode statesNode = typeDef.path("states");

		List<String> allowedStates = new ArrayList<>();
		for (JsonNode s : statesNode) {
			String name = s.path("name").asText();
			if (name != null && !name.equalsIgnoreCase(currentState)) {
				allowedStates.add(name);
			}
		}

		if (allowedStates.isEmpty())
			return;

		// Weighted realism — common corporate patterns
		String newState;

		if ("New".equalsIgnoreCase(currentState)) {
			newState = weightedPick(random, orderedWeights("Active", 70, "Closed", 30), allowedStates);

		} else if ("Active".equalsIgnoreCase(currentState)) {
			newState = weightedPick(random, orderedWeights("Closed", 60, "New", 20, "Resolved", 20), allowedStates);

		} else if ("Closed".equalsIgnoreCase(currentState)) {
			newState = weightedPick(random, orderedWeights("Active", 50, "Closed", 50), allowedStates);

		} else {
			newState = allowedStates.get(random.nextInt(allowedStates.size()));
		}

		patch.add(Map.of("op", "add", "path", "/fields/System.State", "value", newState));
		patch.add(Map.of("op", "add", "path", "/fields/System.History", "value",
				"State changed via webhook simulation | " + Instant.now().toString()));
	}

	/*
	 * ============================================================ Iteration
	 * selection helpers
	 * ============================================================
	 */

	private String pickIterationPathForUpdate(MockState state, Random random) {

		// Prefer moving within: current sprint or nearby sprints.
		MockState.Sprint current = findSprintByNumber(state, state.currentSprintNumber);
		if (current != null && current.name != null) {
			// 70% stay within current PI-ish region: choose among current +/- 2
			if (random.nextInt(100) < 70) {
				int delta = random.nextInt(5) - 2; // -2..+2
				int targetNumber = Math.max(1, state.currentSprintNumber + delta);
				MockState.Sprint target = findSprintByNumber(state, targetNumber);
				if (target != null) {
					return buildIterationPath(state, target.sprintNumber);
				}
			}
			// else pick any existing sprint
		}

		MockState.Sprint any = pickAnySprint(state, random);
		if (any == null)
			return null;

		return buildIterationPath(state, any.sprintNumber);
	}

	private String pickIterationPathForCreate(MockState state, Random random) {
		// Create should usually land in current sprint to trigger "current activity"
		if (random.nextInt(100) < 80) {
			return buildIterationPath(state, state.currentSprintNumber);
		}
		MockState.Sprint any = pickAnySprint(state, random);
		return (any == null) ? null : buildIterationPath(state, any.sprintNumber);
	}

	/**
	 * Build iteration path using the same convention as seeding:
	 * Project\{ABC-PI1}\{ABC-Sprint1}
	 */
	private String buildIterationPath(MockState state, int sprintNumber) {
		String piName = IterationNamingSupport.resolvePiName(state, sprintNumber);
		String sprintName = IterationNamingSupport.resolveSprintName(state, sprintNumber);
		return normalizePath(state.iterationRootPath + "\\" + piName + "\\" + sprintName);
	}

	private MockState.Sprint pickAnySprint(MockState state, Random random) {
		if (state.programIterations == null || state.programIterations.isEmpty())
			return null;

		List<MockState.Sprint> all = new ArrayList<>();
		for (MockState.ProgramIteration pi : state.programIterations) {
			if (pi == null || pi.sprints == null)
				continue;
			for (MockState.Sprint s : pi.sprints) {
				if (s != null)
					all.add(s);
			}
		}
		if (all.isEmpty())
			return null;

		return all.get(random.nextInt(all.size()));
	}

	private MockState.Sprint findSprintByNumber(MockState state, int sprintNumber) {
		if (state.programIterations == null)
			return null;
		for (MockState.ProgramIteration pi : state.programIterations) {
			if (pi == null || pi.sprints == null)
				continue;
			for (MockState.Sprint s : pi.sprints) {
				if (s != null && s.sprintNumber == sprintNumber)
					return s;
			}
		}
		return null;
	}

	private MockState.Sprint findMostRecentSprintWithWorkItems(MockState state) {
		MockState.Sprint best = null;
		if (state.programIterations == null)
			return null;

		for (MockState.ProgramIteration pi : state.programIterations) {
			if (pi == null || pi.sprints == null)
				continue;
			for (MockState.Sprint s : pi.sprints) {
				if (s == null || s.workItemIds == null)
					continue;
				if (!s.workItemIds.isEmpty()) {
					if (best == null || s.sprintNumber > best.sprintNumber) {
						best = s;
					}
				}
			}
		}
		return best;
	}

	/*
	 * ============================================================ Candidate
	 * selection (for links)
	 * ============================================================
	 */

	private List<String> getCandidateWorkItemIds(MockState state) {
		List<String> ids = new ArrayList<>();

		MockState.Sprint current = findSprintByNumber(state, state.currentSprintNumber);
		if (current != null && current.workItemIds != null) {
			ids.addAll(current.workItemIds);
		}

		// fallback: all work items from any sprint
		if (ids.isEmpty() && state.programIterations != null) {
			for (MockState.ProgramIteration pi : state.programIterations) {
				if (pi == null || pi.sprints == null)
					continue;
				for (MockState.Sprint s : pi.sprints) {
					if (s != null && s.workItemIds != null) {
						ids.addAll(s.workItemIds);
					}
				}
			}
		}

		return ids;
	}

	/*
	 * ============================================================ Utilities
	 * ============================================================
	 */

	private String mergeTags(String existingTags, List<String> add) {
		// ADO uses semicolon-separated tags
		LinkedHashMap<String, Boolean> set = new LinkedHashMap<>();
		if (existingTags != null && !existingTags.isBlank()) {
			for (String t : existingTags.split(";")) {
				String x = t.trim();
				if (!x.isEmpty())
					set.put(x, true);
			}
		}
		for (String t : add) {
			if (t == null)
				continue;
			String x = t.trim();
			if (!x.isEmpty())
				set.put(x, true);
		}
		return String.join("; ", set.keySet());
	}

	private Map<String, Integer> orderedWeights(String k1, int w1, String k2, int w2) {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put(k1, w1);
		m.put(k2, w2);
		return m;
	}

	private Map<String, Integer> orderedWeights(String k1, int w1, String k2, int w2, String k3, int w3) {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put(k1, w1);
		m.put(k2, w2);
		m.put(k3, w3);
		return m;
	}

	private String weightedPick(Random random, Map<String, Integer> weights, List<String> allowed) {

		int total = 0;

		for (String key : weights.keySet()) {
			if (allowed.contains(key)) {
				total += weights.get(key);
			}
		}

		if (total == 0) {
			return allowed.get(random.nextInt(allowed.size()));
		}

		int r = random.nextInt(total);
		int cumulative = 0;

		for (Map.Entry<String, Integer> e : weights.entrySet()) {
			if (!allowed.contains(e.getKey()))
				continue;

			cumulative += e.getValue();
			if (r < cumulative) {
				return e.getKey();
			}
		}

		return allowed.get(0);
	}

	private String normalizePath(String p) {
		if (p == null)
			return null;
		String x = p.trim().replace("/", "\\");
		if (x.startsWith("\\"))
			x = x.substring(1);
		return x;
	}

	private String shortHash(String s) {
		if (s == null)
			return "null";
		CRC32 crc = new CRC32();
		crc.update(s.getBytes(StandardCharsets.UTF_8));
		return Long.toHexString(crc.getValue());
	}
}
