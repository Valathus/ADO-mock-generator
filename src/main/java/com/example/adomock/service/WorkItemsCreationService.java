package com.example.adomock.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.identity.AdminIdentityProvider;
import com.example.adomock.identity.UserIdentityProvider;
import com.example.adomock.scheduler.DataLoadMockJob;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Work item seeding service.
 *
 * Supports: - One-time backfill seeding across the configured window
 * (isWorkItemsDataLoaded()). - Per-sprint idempotent delta seeding for webhook
 * iteration expansion (ensureWorkItemsForSprint(...)) as required by
 * IterationWebhookEngine design. :contentReference[oaicite:0]{index=0}
 *
 * Notes: - Delta seeding must be idempotent + self-healing across retries.
 * :contentReference[oaicite:1]{index=1} - Determinism: avoid ThreadLocalRandom
 * for structural decisions (subtasks set). - Persist state in batches, not per
 * item.
 */
@Service
public class WorkItemsCreationService {
	
	private static final Logger log = LoggerFactory.getLogger(WorkItemsCreationService.class);

	private final FileStateRepository repository;
	private final UserIdentityProvider identityProvider;
	private final AdminIdentityProvider adminIdentityProvider;
	private final AdoRestClient adoClient;
	private final AdoProperties properties;

	enum WorkItemCategory {
		RequirementCategory, BugCategory, TaskCategory
	}

	private final Map<WorkItemCategory, String> categoryTypeMap = new HashMap<>();
	private boolean workItemTypesLoaded = false;

	@Value("${mock.seed.workItems.enabled:true}")
	private boolean workItemSeedingEnabled;

	@Value("${mock.seed.iterations.enabled:true}")
	private boolean iterationSeedingEnabled;

	/**
	 * Optional global seed for deterministic behavior across restarts. If not
	 * configured, a stable seed will still be derived per sprint.
	 */
	@Value("${mock.random.seed:0}")
	private long baseRandomSeed;

	/**
	 * Batch size for saving state during backfill. Lower means safer, higher means
	 * faster. For webhook delta seeding, we still prefer "save once at end", but
	 * this provides a safety valve.
	 */
	@Value("${mock.state.save.batchSize:25}")
	private int stateSaveBatchSize;

	public WorkItemsCreationService(FileStateRepository repository, UserIdentityProvider identityProvider,
			AdoRestClient adoClient, AdoProperties properties, AdminIdentityProvider adminIdentityProvider) {

		this.repository = repository;
		this.identityProvider = identityProvider;
		this.adminIdentityProvider = adminIdentityProvider;
		this.adoClient = adoClient;
		this.properties = properties;
	}

	/**
	 * One-time historical-looking backfill across the configured window.
	 *
	 * Returns true only when it actually performed seeding work.
	 */
	public boolean isWorkItemsDataLoaded() {

		if (!workItemSeedingEnabled)
			return false;

		MockState state = repository.load();

		// If already completed, nothing to do.
		if (state.workItemBackfill != null && state.workItemBackfill.completed)
			return false;

		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();
		String pat = adminIdentityProvider.getAdmin().pat;
		loadWorkItemTypes(pat, project, apiVersion);

		int windowDays = state.dataLoadConfig.windowDays;
		int sprintDuration = state.dataLoadConfig.sprintDurationDays;
		int sprintsPerPI = state.dataLoadConfig.sprintsPerPI;
		int workItemsPerDay = state.dataLoadConfig.workItemsPerDay;

		if (state.workItemBackfill.startedAt == null) {
			state.workItemBackfill.startedAt = Instant.now();
			repository.save(state);
		}

		int requiredSprintCount = (int) Math.ceil((double) windowDays / sprintDuration);

		int resumeSprint = state.workItemBackfill.lastProcessedSprintNumber;
		int sprintNumber = Math.max(1, resumeSprint == 0 ? 1 : resumeSprint);

		// If we already processed sprint N fully, start from N+1. If mid-sprint, resume
		// same sprint.
		if (state.workItemBackfill.createdInCurrentSprint == 0
				&& state.workItemBackfill.lastProcessedSprintNumber > 0) {
			sprintNumber = state.workItemBackfill.lastProcessedSprintNumber + 1;
		}

		for (; sprintNumber <= requiredSprintCount; sprintNumber++) {

			String piName = IterationNamingSupport.resolvePiName(state, sprintNumber);
			String sprintName = IterationNamingSupport.resolveSprintName(state, sprintNumber);

			String iterationPath = normalizePath("\\" + project + "\\" + piName + "\\" + sprintName);

			LocalDate sprintStart = state.calendarAnchorDate.plusDays((long) (sprintNumber - 1) * sprintDuration);
			int totalItemsForSprint = workItemsPerDay * sprintDuration;

			// resume inside sprint
			int startIndex = (sprintNumber == state.workItemBackfill.lastProcessedSprintNumber)
					? state.workItemBackfill.createdInCurrentSprint
					: 0;

			// Deterministic selection for "PBIs with subtasks" (restart-safe)
			Set<Integer> pbiIndexesForSubtasks = computeDeterministicPbiIndexesForSubtasks(state, sprintNumber,
					totalItemsForSprint);

			int createdSinceLastSave = 0;

			for (int i = startIndex; i < totalItemsForSprint; i++) {

				String createdId = createSingleWorkItem(state, pat, project, apiVersion, iterationPath, sprintNumber,
						sprintName, sprintStart, sprintDuration, i, /* correlation */ null, /* allowSubtasks */ true,
						pbiIndexesForSubtasks);

				if (createdId != null) {
					// update progress ONLY after successful create (avoids phantom progress)
					state.workItemBackfill.lastProcessedSprintNumber = sprintNumber;
					state.workItemBackfill.createdInCurrentSprint = i + 1;
				}

				createdSinceLastSave++;
				if (stateSaveBatchSize > 0 && createdSinceLastSave >= stateSaveBatchSize) {
					repository.save(state);
					createdSinceLastSave = 0;
				}
			}

			// finished sprint
			state.workItemBackfill.lastProcessedSprintNumber = sprintNumber;
			state.workItemBackfill.createdInCurrentSprint = 0;
			repository.save(state);
		}

		state.workItemBackfill.completed = true;
		state.workItemBackfill.completedAt = Instant.now();
		repository.save(state);

		return true;
	}

	/**
	 * Webhook/delta expansion contract: Ensure a specific sprint has the expected
	 * work item count.
	 *
	 * Idempotent + self-healing: - If the sprint already has >= expected items,
	 * does nothing. - If the sprint has fewer, creates ONLY the missing delta. -
	 * Deterministic subtask selection across restarts.
	 *
	 * This is meant to be called from IterationWebhookEngine during
	 * tickAndMaybeSeed(...) when new future sprints are created via
	 * ensureSprintsExist(...). :contentReference[oaicite:2]{index=2}
	 */
	public void ensureWorkItemsForSprint(MockState state, MockState.Sprint sprint, String correlation) {

		if (!workItemSeedingEnabled)
			return;

		if (state == null || sprint == null)
			return;

		String project = state.collectionDetails.projectName;
		String apiVersion = properties.getApiVersion();
		String pat = adminIdentityProvider.getAdmin().pat;

		loadWorkItemTypes(pat, project, apiVersion);

		int sprintDuration = state.dataLoadConfig.sprintDurationDays;
		int workItemsPerDay = state.dataLoadConfig.workItemsPerDay;

		int expected = Math.max(0, workItemsPerDay * sprintDuration);

		// Guard: if no expected items, nothing to do.
		if (expected == 0)
			return;

		// Ensure list exists
		if (sprint.workItemIds == null)
			sprint.workItemIds = new ArrayList<>();

		int existing = sprint.workItemIds.size();
		if (existing >= expected)
			return; // idempotent

		int sprintNumber = sprint.sprintNumber;

		// Compute iteration path from config convention (and normalize)
		String piName = IterationNamingSupport.resolvePiName(state, sprintNumber);
		String sprintName = IterationNamingSupport.resolveSprintName(state, sprintNumber);
		String iterationPath = normalizePath(state.iterationRootPath + "\\" + piName + "\\" + sprintName);

		LocalDate sprintStart = state.calendarAnchorDate.plusDays((long) (sprintNumber - 1) * sprintDuration);

		// Deterministic subtask selection for the sprint
		Set<Integer> pbiIndexesForSubtasks = computeDeterministicPbiIndexesForSubtasks(state, sprintNumber, expected);

		// Create only the missing delta: indices [existing, expected)
		for (int i = existing; i < expected; i++) {
			createSingleWorkItem(state, pat, project, apiVersion, iterationPath, sprintNumber, sprintName, sprintStart,
					sprintDuration, i, correlation, /* allowSubtasks */ true, pbiIndexesForSubtasks);
		}

		// Persist once at end (preferred for webhook tick)
		repository.save(state);
	}

	/**
	 * Create one work item and (optionally) its subtasks. Returns created work item
	 * id (or null if create failed).
	 */
	private String createSingleWorkItem(MockState state, String pat, String project, String apiVersion,
			String iterationPath, int sprintNumber, String sprintName, LocalDate sprintStart, int sprintDuration, int i,
			String correlation, boolean allowSubtasks, Set<Integer> pbiIndexesForSubtasks) {

		// Stable-ish time distribution inside sprint; clamp to now to avoid future
		// timestamps
		int dayOffset = i % sprintDuration;
		LocalDate itemDate = sprintStart.plusDays(dayOffset);

		int hour = (i * 3) % 24;
		int minute = (i * 7) % 60;

		Instant candidateInstant = itemDate.atTime(hour, minute).toInstant(ZoneOffset.UTC);
		Instant now = Instant.now();
		Instant safeInstant = candidateInstant.isAfter(now)
				? now.minusSeconds(ThreadLocalRandom.current().nextInt(0, 3600))
				: candidateInstant;

		String createdDate = safeInstant.toString();

		MockState.User user = identityProvider.next();
		String workItemType = pickWorkItemType(i);
		String title = "Mock Item - " + sprintName + " - #" + i;

		List<Map<String, Object>> patch = new ArrayList<>();
		patch.add(Map.of("op", "add", "path", "/fields/System.Title", "value", title));
		patch.add(Map.of("op", "add", "path", "/fields/System.Description", "value",
				"Generated mock data for " + sprintName));
		patch.add(Map.of("op", "add", "path", "/fields/System.IterationPath", "value", iterationPath));
		patch.add(Map.of("op", "add", "path", "/fields/System.AssignedTo", "value", user.username));

		// NOTE: Setting CreatedDate may fail on some ADO configs even with bypassRules.
		// If it fails in your environment, remove this line.
		patch.add(Map.of("op", "add", "path", "/fields/System.CreatedDate", "value", createdDate));

		// Correlation + base tags for tracing webhook payloads
		// ADO tags are semicolon-separated. Avoid commas.
		String tags = "mock";
		if (correlation != null && !correlation.isBlank()) {
			tags = tags + "; mockRun:" + correlation;
		}
		patch.add(Map.of("op", "add", "path", "/fields/System.Tags", "value", tags));
		patch.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.Scheduling.DueDate", "value",
				pickDueDate(sprintStart, sprintDuration, i)));

		if (workItemType.equals(categoryTypeMap.get(WorkItemCategory.RequirementCategory))
				|| workItemType.equals(categoryTypeMap.get(WorkItemCategory.BugCategory))) {
			patch.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.Scheduling.StoryPoints", "value",
					pickStoryPoints(sprintNumber, i)));
		} else if (workItemType.equals(categoryTypeMap.get(WorkItemCategory.TaskCategory))) {
			patch.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.Scheduling.OriginalEstimate", "value",
					pickTaskHours(sprintNumber, i)));
		}

		String uri = "/" + project + "/_apis/wit/workitems/$" + workItemType + "?api-version=" + apiVersion
				+ "&bypassRules=true&suppressNotifications=true";

		JsonNode created;
		try {
			created = adoClient.postJsonPatch(pat, uri, patch);
		} catch (Exception e) {
			log.error(e.getMessage());
			return null;
		}

		String id = created.path("id").asText(null);
		if (id == null || id.isBlank())
			return null;

		addWorkItemToSprintInState(state, sprintNumber, id);

		// Create subtasks deterministically for selected "PBI-like" items
		if (allowSubtasks && workItemType.equals(categoryTypeMap.get(WorkItemCategory.RequirementCategory))
				&& pbiIndexesForSubtasks != null && pbiIndexesForSubtasks.contains(i)) {

			int subTaskCount = 2 + (i % 2);

			for (int t = 0; t < subTaskCount; t++) {
				MockState.User subUser = identityProvider.next();

				List<Map<String, Object>> subPatch = new ArrayList<>();
				subPatch.add(Map.of("op", "add", "path", "/fields/System.Title", "value",
						"Subtask " + t + " for PBI " + id));
				subPatch.add(Map.of("op", "add", "path", "/fields/System.IterationPath", "value", iterationPath));
				subPatch.add(Map.of("op", "add", "path", "/fields/System.AssignedTo", "value", subUser.username));
				subPatch.add(Map.of("op", "add", "path", "/fields/System.CreatedDate", "value", createdDate));
				subPatch.add(Map.of("op", "add", "path", "/fields/System.Tags", "value", tags));
				subPatch.add(Map.of("op", "add", "path", "/fields/Microsoft.VSTS.Scheduling.OriginalEstimate", "value",
						pickTaskHours(sprintNumber, i + t)));

				// Parent link
				subPatch.add(Map.of("op", "add", "path", "/relations/-", "value",
						Map.of("rel", "System.LinkTypes.Hierarchy-Reverse", "url",
								state.collectionDetails.url + "/" + project + "/_apis/wit/workItems/" + id)));

				String subUri = "/" + project + "/_apis/wit/workitems/$Task?api-version=" + apiVersion
						+ "&bypassRules=true&suppressNotifications=true";
				
				try {
					JsonNode subCreated = adoClient.postJsonPatch(pat, subUri, subPatch);
					String subId = subCreated.path("id").asText(null);
					if (subId != null && !subId.isBlank()) {
						addWorkItemToSprintInState(state, sprintNumber, subId);
					}
				} catch (Exception ignored) {
					log.error(ignored.getMessage());
				}
			}
		}

		return id;
	}

	private void addWorkItemToSprintInState(MockState state, int sprintNumber, String workItemId) {

		if (state == null || state.programIterations == null)
			return;

		for (MockState.ProgramIteration pi : state.programIterations) {
			if (pi == null || pi.sprints == null)
				continue;

			for (MockState.Sprint s : pi.sprints) {
				if (s != null && s.sprintNumber == sprintNumber) {
					if (s.workItemIds == null)
						s.workItemIds = new ArrayList<>();
					s.workItemIds.add(workItemId);
					return;
				}
			}
		}
		// If not found, ignore. Normally it should exist after iteration seeding /
		// ensureSprintsExist.
	}

	private void loadWorkItemTypes(String pat, String project, String apiVersion) {

		if (workItemTypesLoaded)
			return;

		String uri = "/" + project + "/_apis/wit/workitemtypes?api-version=" + apiVersion;
		JsonNode response = adoClient.get(pat, uri);

		for (JsonNode type : response.path("value")) {

			String name = type.path("name").asText();
			String category = type.path("category").asText();

			try {
				WorkItemCategory enumCategory = WorkItemCategory.valueOf(category);
				categoryTypeMap.put(enumCategory, name);
			} catch (IllegalArgumentException ignored) {
				// Ignore categories we don't care about
			}
		}

		// Fallback safety
		categoryTypeMap.putIfAbsent(WorkItemCategory.RequirementCategory, "User Story");
		categoryTypeMap.putIfAbsent(WorkItemCategory.BugCategory, "Bug");
		categoryTypeMap.putIfAbsent(WorkItemCategory.TaskCategory, "Task");

		workItemTypesLoaded = true;
	}

	private String pickWorkItemType(int i) {

		int m = i % 10;

		if (m == 0)
			return categoryTypeMap.get(WorkItemCategory.RequirementCategory);

		if (m == 1)
			return categoryTypeMap.get(WorkItemCategory.BugCategory);

		return categoryTypeMap.get(WorkItemCategory.TaskCategory);
	}

	/**
	 * Normalize ADO iteration paths to avoid duplicates caused by leading
	 * slashes/backslashes or forward-slash variants.
	 * :contentReference[oaicite:3]{index=3}
	 */
	private String normalizePath(String p) {
		if (p == null)
			return null;
		String x = p.trim().replace("/", "\\");
		if (x.startsWith("\\"))
			x = x.substring(1);
		return x;
	}

	/**
	 * Deterministically pick a stable set of PBI indexes that will get subtasks, so
	 * reruns/crashes don’t change which items are “special”.
	 *
	 * Strategy: - Use a stable per-sprint seed derived from (anchorDate,
	 * sprintNumber, baseRandomSeed). - Pick up to N unique indices within [0,
	 * totalItems).
	 */
	private Set<Integer> computeDeterministicPbiIndexesForSubtasks(MockState state, int sprintNumber,
			int totalItemsForSprint) {

		Set<Integer> out = new HashSet<>();
		if (totalItemsForSprint <= 0)
			return out;

		int max = Math.min(5, Math.max(1, 2 + (sprintNumber % 3)));

		long seed = stableSprintSeed(state, sprintNumber);

		// Simple LCG-ish deterministic generator (no java.util.Random to avoid
		// accidental shared state)
		long x = seed;

		while (out.size() < max) {
			x = (x * 6364136223846793005L + 1442695040888963407L);
			int idx = (int) Math.floorMod(x, totalItemsForSprint);
			out.add(idx);
		}

		return out;
	}

	private static final int[] STORY_POINTS = { 1, 2, 3, 5, 8, 13 };
	private static final int[] TASK_HOURS = { 2, 4, 4, 8, 8, 16 };

	private int pickStoryPoints(int sprintNumber, int itemIndex) {
		return STORY_POINTS[(sprintNumber * 31 + itemIndex) % STORY_POINTS.length];
	}

	private int pickTaskHours(int sprintNumber, int itemIndex) {
		return TASK_HOURS[(sprintNumber * 31 + itemIndex) % TASK_HOURS.length];
	}

	private String pickDueDate(LocalDate sprintStart, int sprintDuration, int itemIndex) {
		int daysIntoSprint = Math.min(sprintDuration - 1, (itemIndex % sprintDuration) + 2);
		LocalDate dueDate = sprintStart.plusDays(Math.max(0, daysIntoSprint));
		return dueDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + "T00:00:00.000Z";
	}

	/**
	 * Create a stable seed for a sprint. Uses calendarAnchorDate + sprintNumber +
	 * optional baseRandomSeed.
	 */
	private long stableSprintSeed(MockState state, int sprintNumber) {
		String anchor = (state != null && state.calendarAnchorDate != null) ? state.calendarAnchorDate.toString()
				: "null";
		String material = "anchor=" + anchor + "|sprint=" + sprintNumber + "|base=" + baseRandomSeed;

		CRC32 crc = new CRC32();
		crc.update(material.getBytes(StandardCharsets.UTF_8));
		long c = crc.getValue();

		// Spread into 64-bit space a bit
		return (c << 32) ^ (c * 0x9E3779B97F4A7C15L) ^ (long) sprintNumber;
	}
}
