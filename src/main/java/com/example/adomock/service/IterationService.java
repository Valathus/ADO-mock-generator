package com.example.adomock.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.adomock.config.AdoProperties;
import com.example.adomock.http.AdoRestClient;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.fasterxml.jackson.databind.JsonNode;

@Service
public class IterationService {

	private final AdoRestClient adoClient;
	private final AdoProperties properties;
	private final FileStateRepository repository;

	public IterationService(AdoRestClient adoClient, AdoProperties properties, FileStateRepository repository) {
		this.adoClient = adoClient;
		this.properties = properties;
		this.repository = repository;
	}

	// helper type - NOT persisted
	private static class CreateResult {
		private final String id;
		private final boolean created;

		CreateResult(String id, boolean created) {
			this.id = id;
			this.created = created;
		}

		public String getId() {
			return id;
		}

		public boolean isCreated() {
			return created;
		}
	}

	/**
	 * One-time bootstrap for iteration tree creation (idempotent). This sets the
	 * anchor date and creates iterations up to the configured window. After
	 * completion it sets iterationSeedCompleted=true.
	 */
	public void ensureSprintStructure() {

		MockState state = repository.load();

		if (state == null) {
			throw new IllegalStateException("State is null (repository.load returned null).");
		}

		if (state.iterationSeedCompleted) {
			return;
		}

		ensureStateLists(state);

		// Validate required state
		if (state.admin == null || state.admin.pat == null) {
			throw new IllegalStateException("Missing admin PAT in state.admin.pat");
		}
		if (state.collectionDetails == null || state.collectionDetails.projectName == null) {
			throw new IllegalStateException("Missing projectName in state.collectionDetails.projectName");
		}
		if (state.dataLoadConfig == null) {
			throw new IllegalStateException("Missing dataLoadConfig in state");
		}

		// Anchor Setup (once)
		if (state.calendarAnchorDate == null) {
			LocalDate nowMonday = mostRecentMonday(LocalDate.now());

			int pastDays = state.dataLoadConfig.sprintsPerPI * state.dataLoadConfig.sprintDurationDays;

			state.calendarAnchorDate = nowMonday.minusDays(pastDays);
			repository.save(state);
		}

		int sprintDuration = state.dataLoadConfig.sprintDurationDays;
		int windowDays = state.dataLoadConfig.windowDays;

		// total sprints required for the window
		int requiredSprintCount = (int) Math.ceil((double) windowDays / sprintDuration);
		int startSprint = 1;
		int endSprint = requiredSprintCount;

		// Create/ensure the range exists
		ensureSprintsExist(state, startSprint, endSprint);

		state.iterationSeedCompleted = true;
		repository.save(state);
	}

	/**
	 * Delta expander for webhook mode / window expansion.
	 *
	 * IMPORTANT: - This method mutates the provided state in-memory
	 * (programIterations / sprints). - It does NOT save state. Caller
	 * (IterationWebhookEngine/MutationExecutor) should persist after it returns
	 * true (or after its tick changes state).
	 *
	 * Returns true if it created at least one new sprint iteration in ADO.
	 */
	public boolean ensureSprintsExist(MockState state, int startSprint, int endSprint) {

		if (state == null) {
			throw new IllegalStateException("State is null.");
		}

		ensureStateLists(state);

		if (state.admin == null || state.admin.pat == null) {
			throw new IllegalStateException("Missing admin PAT in state.admin.pat");
		}
		if (state.collectionDetails == null || state.collectionDetails.projectName == null) {
			throw new IllegalStateException("Missing projectName in state.collectionDetails.projectName");
		}
		if (state.dataLoadConfig == null) {
			throw new IllegalStateException("Missing dataLoadConfig in state");
		}
		if (startSprint <= 0 || endSprint <= 0 || endSprint < startSprint) {
			throw new IllegalArgumentException("Invalid sprint range: " + startSprint + ".." + endSprint);
		}

		// Anchor must be stable; if missing, create it (same as bootstrap)
		if (state.calendarAnchorDate == null) {
			state.calendarAnchorDate = mostRecentMonday(LocalDate.now());
		}

		String pat = state.admin.pat;
		String project = state.collectionDetails.projectId;
		String apiVersion = properties.getApiVersion();

		int sprintDuration = state.dataLoadConfig.sprintDurationDays;
		int sprintsPerPI = state.dataLoadConfig.sprintsPerPI;

		// Load ADO iteration tree once
		String rootUri = "/" + project + "/_apis/wit/classificationnodes/iterations?$depth=10&api-version="
				+ apiVersion;
		JsonNode root = adoClient.get(pat, rootUri);

		saveRootPath(root, state);

		repository.save(state);

		Set<String> existingPaths = new HashSet<>();
		collectPaths(root, existingPaths);

		boolean createdAnything = false;

		for (int sprintNumber = startSprint; sprintNumber <= endSprint; sprintNumber++) {

			int piNumber = ((sprintNumber - 1) / sprintsPerPI) + 1;
			String piName = IterationNamingSupport.buildPiName(state, piNumber);

			// PI
			// Calculate PI dates before creating
			int firstSprintNumber = ((piNumber - 1) * sprintsPerPI) + 1;
			int lastSprintNumber = (piNumber * sprintsPerPI);
			LocalDate piStartDate = state.calendarAnchorDate.plusDays((long) (firstSprintNumber - 1) * sprintDuration);
			LocalDate piEndDate = state.calendarAnchorDate.plusDays((long) (lastSprintNumber - 1) * sprintDuration)
					.plusDays(sprintDuration - 1);
			
			CreateResult piResult = createIfMissing(pat, project, null, piName, piStartDate, piEndDate, apiVersion, existingPaths,
					state);
			MockState.ProgramIteration pi = findOrCreatePI(state, piName, piResult.getId(), piNumber);

			// Sprint
			LocalDate sprintStart = state.calendarAnchorDate.plusDays((long) (sprintNumber - 1) * sprintDuration);
			LocalDate sprintEnd = sprintStart.plusDays(sprintDuration - 1);

			String sprintName = IterationNamingSupport.buildSprintName(state, sprintNumber);

			CreateResult sprintResult = createIfMissing(pat, project, piName, sprintName, sprintStart, sprintEnd,
					apiVersion, existingPaths, state);

			findOrCreateSprint(pi, sprintNumber, sprintName, sprintResult.getId(), sprintStart, sprintEnd);

			if (sprintResult.isCreated()) {
				createdAnything = true;
				assignToTeams(pat, project, piName, sprintName, apiVersion, state);
			}
		}

		return createdAnything;
	}

	private CreateResult createIfMissing(String pat, String project, String parent, String name, LocalDate start,
			LocalDate end, String apiVersion, Set<String> existingPaths, MockState state) {

		// Normalize paths so comparisons match ADO's returned format (often starts with
		// '\')
		String fullPath = normalizePath(parent == null ? state.iterationRootPath + "\\" + name
				: state.iterationRootPath + "\\" + parent + "\\" + name);

		if (existingPaths.contains(fullPath)) {
			String id = fetchIdentifier(pat, project, parent, name, apiVersion, state);
			return new CreateResult(id, false);
		}

		String uri = parent == null
				? "/" + project + "/_apis/wit/classificationnodes/iterations?api-version=" + apiVersion
				: "/" + project + "/_apis/wit/classificationnodes/iterations/" + encode(parent) + "?api-version="
						+ apiVersion;

		Map<String, Object> body = (start != null) ? Map.of("name", name, "attributes",
				Map.of("startDate", start.atStartOfDay().toInstant(ZoneOffset.UTC).toString(), "finishDate",
						end.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toString()))
				: Map.of("name", name);

		JsonNode response = adoClient.post(pat, uri, body);

		existingPaths.add(fullPath);

		return new CreateResult(response.path("identifier").asText(), true);
	}

	private String fetchIdentifier(String pat, String project, String parent, String name, String apiVersion,
			MockState state) {
		String path = parent == null ? encode(name) : encode(parent) + "/" + encode(name);
		String uri = "/" + project + "/_apis/wit/classificationnodes/iterations/" + path + "?api-version=" + apiVersion;
		JsonNode node = adoClient.get(pat, uri);
		return node.path("identifier").asText();
	}

	private void collectPaths(JsonNode node, Set<String> paths) {
		if (node == null)
			return;

		if (node.has("path")) {
			paths.add(normalizePath(node.path("path").asText()));
		}

		if (node.has("children")) {
			for (JsonNode child : node.get("children")) {
				collectPaths(child, paths);
			}
		}
	}

	private void saveRootPath(JsonNode root, MockState state) {
		if (root == null || !root.has("path"))
			return;

		if (root.has("path")) {
			state.iterationRootPath = root.path("path").asText();
			state.iterationRootIdentifier = root.path("identifier").asText();
		}
	}

	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * Normalize iteration paths for consistent existence checks. Azure DevOps often
	 * returns paths with a leading "\" (e.g. "\Project\KID-PI1\KID-Sprint1"). We
	 * normalize to the same slash convention for comparisons.
	 */
	private String normalizePath(String p) {
		if (p == null)
			return null;
		String x = p.trim().replace("/", "\\");
		if (!x.startsWith("\\")) {
			x = "\\" + x;
		}
		return x;
	}

	private LocalDate mostRecentMonday(LocalDate from) {
		LocalDate d = from;
		while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
			d = d.minusDays(1);
		}
		return d;
	}

	private void ensureStateLists(MockState state) {
		// Defensive: avoid NPE if state was serialized without initializing lists
		if (state.programIterations == null) {
			state.programIterations = new ArrayList<>();
		}
		if (state.collectionDetails != null && state.collectionDetails.teams == null) {
			state.collectionDetails.teams = new ArrayList<>();
		}
	}

	private MockState.ProgramIteration findOrCreatePI(MockState state, String name, String id, int piNumber) {
		for (MockState.ProgramIteration pi : state.programIterations) {
			if (pi.piNumber == piNumber)
				return pi;
		}
		MockState.ProgramIteration pi = new MockState.ProgramIteration();
		pi.id = id;
		pi.name = name;
		pi.piNumber = piNumber;
		
		// Calculate PI dates based on first and last sprints
		int sprintsPerPI = state.dataLoadConfig.sprintsPerPI;
		int sprintDuration = state.dataLoadConfig.sprintDurationDays;
		
		// First sprint number for this PI
		int firstSprintNumber = ((piNumber - 1) * sprintsPerPI) + 1;
		// Last sprint number for this PI
		int lastSprintNumber = (piNumber * sprintsPerPI);
		
		// Calculate dates
		LocalDate firstSprintStart = state.calendarAnchorDate.plusDays((long) (firstSprintNumber - 1) * sprintDuration);
		LocalDate lastSprintEnd = state.calendarAnchorDate.plusDays((long) (lastSprintNumber - 1) * sprintDuration)
				.plusDays(sprintDuration - 1);
		
		pi.startDate = firstSprintStart;
		pi.endDate = lastSprintEnd;
		
		state.programIterations.add(pi);
		return pi;
	}

	private void findOrCreateSprint(MockState.ProgramIteration pi, int sprintNumber, String name, String id,
			LocalDate start, LocalDate end) {

		if (pi.sprints == null) {
			pi.sprints = new ArrayList<>();
		}

		for (MockState.Sprint s : pi.sprints) {
			if (s.sprintNumber == sprintNumber)
				return;
		}

		MockState.Sprint sprint = new MockState.Sprint();
		sprint.id = id;
		sprint.sprintNumber = sprintNumber;
		sprint.name = name;
		sprint.startDate = start;
		sprint.endDate = end;
		pi.sprints.add(sprint);
	}

	private void assignToTeams(String pat, String project, String parent, String sprint, String apiVersion,
			MockState state) {

		// If teams are not configured, skip gracefully
		if (state.collectionDetails == null || state.collectionDetails.teams == null
				|| state.collectionDetails.teams.isEmpty()) {
			return;
		}

		String path = encode(parent) + "/" + encode(sprint);
		String uri = "/" + project + "/_apis/wit/classificationnodes/iterations/" + path + "?api-version=" + apiVersion;

		JsonNode node = adoClient.get(pat, uri);
		String iterationId = node.path("identifier").asText();

		for (String team : state.collectionDetails.teams) {
			String teamUri = "/" + project + "/" + encode(team) + "/_apis/work/teamsettings/iterations?api-version="
					+ apiVersion;
			adoClient.post(pat, teamUri, Map.of("id", iterationId));
		}
	}
}
