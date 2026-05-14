package com.example.adomock.state;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class MockState {

	// ------------------------------------------------
	// USERS
	// ------------------------------------------------
	public List<User> users = new ArrayList<>();
	
	public boolean iterationSeedCompleted;

	// ------------------------------------------------
	// CALENDAR
	// ------------------------------------------------
	public LocalDate calendarAnchorDate; // fixed once
	public int currentSprintNumber = 1; // derived each startup

	// ------------------------------------------------
	// ITERATION STRUCTURE (persisted for resume)
	// ------------------------------------------------
	public String iterationRootPath;
	public String iterationRootIdentifier;
	public List<ProgramIteration> programIterations = new ArrayList<>();

	// ------------------------------------------------
	// BACKFILL PROGRESS
	// ------------------------------------------------
	public WorkItemBackfill workItemBackfill = new WorkItemBackfill();
	public RepoBackfill repoBackfill = new RepoBackfill();
	public BuildBackfill buildBackfill = new BuildBackfill();

	// ------------------------------------------------
	public RepoState repo;
	public boolean webhookEnabled = false;
	public Admin admin;
	public CollectionDetails collectionDetails;
	public DataLoadConfig dataLoadConfig;
	public WebhookMutationState webhookMutationState = new WebhookMutationState();

	// ------------------------------------------------
	// TFVC (optional — populate if using TFVC repos)
	// ------------------------------------------------
	public String tfvcRootPath; // e.g. "$/MyProject"

	// ------------------------------------------------
	// RELEASE PIPELINE (optional — classic release)
	// ------------------------------------------------
	public Integer releaseDefinitionId;

	// ------------------------------------------------
	// TEST RUNS (optional)
	// ------------------------------------------------
	public Integer testPlanId;

	// =========================================================
	// ITERATION MODEL
	// =========================================================

	public static class ProgramIteration {
		public String id;
		public int piNumber; // sequential
		public String name; // PI-1, PI-2...
		public LocalDate startDate;
		public LocalDate endDate;
		public List<Sprint> sprints = new ArrayList<>();
	}

	public static class Sprint {
	    public String id;
	    public int sprintNumber;
	    public String name;
	    public LocalDate startDate;
	    public LocalDate endDate;
	    public List<String> workItemIds = new ArrayList<>();

	    // NEW: tracked artifacts for idempotent per-sprint seeding
	    public List<String> buildIds = new ArrayList<>();
	    public List<String> repoActivityIds = new ArrayList<>(); // optional, but mirrors architecture
	}

	// =========================================================
	// CONFIG
	// =========================================================

	public static class DataLoadConfig {
		public int windowDays; // controls future expansion
		public int sprintDurationDays; // usually 14
		public int sprintsPerPI; // usually 4 (hard cap)
		public int workItemsPerDay;
		public int repoActivitiesPerSprint;
		public int buildsPerSprint;
		public int currentSprintFillPercentage;
	}

	// =========================================================
	// BACKFILL TRACKING
	// =========================================================

	public static class WorkItemBackfill {
		public boolean completed = false;
		public int lastProcessedSprintNumber = 0;
		public int createdInCurrentSprint = 0;
		public Instant startedAt;
		public Instant completedAt;
	}

	public static class RepoBackfill {
		public boolean completed = false;
		public int lastProcessedSprintNumber = 0;
		public int createdInCurrentSprint = 0;
		public Instant startedAt;
		public Instant completedAt;
	}

	public static class BuildBackfill {
		public boolean completed = false;
		public int lastProcessedSprintNumber = 0;
		public int createdInCurrentSprint = 0;
		public Instant startedAt;
		public Instant completedAt;
	}

	// =========================================================
	// SUPPORT CLASSES
	// =========================================================

	public static class Admin {
		public String username;
		public String pat;
	}

	public static class User {
		public String username;
		public String pat;
		public boolean enabled = true;
		public String descriptor;
		public String id;
	}

	public static class RepoState {
		public String repoId;
		public String repoName;
		public String pipeLineName;
		public String defaultBranch;
		public String defaultBranchHead;
		public Instant createdAt;
		public Integer buildDefinitionId;
	}

	public static class CollectionDetails {
		public String url;
		public String projectName;
		public String projectId;
		public String id;
		public List<String> teams = new ArrayList<>();
		public String webhookURL;
	}
	
	public static class WebhookMutationState {
	    public long totalCycles;
	    public long totalActions;
	    public java.time.Instant lastCycleStartedAt;
	    public java.time.Instant lastCycleCompletedAt;
	}
}