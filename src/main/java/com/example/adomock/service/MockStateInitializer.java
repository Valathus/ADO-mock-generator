package com.example.adomock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;

@Component
public class MockStateInitializer {

	private static final Logger log = LoggerFactory.getLogger(MockStateInitializer.class);

	private final FileStateRepository repository;

	public MockStateInitializer(FileStateRepository repository) {
		this.repository = repository;
	}

	public boolean initializeAndValidate() {

		MockState state = repository.load();
		if (state == null) {
			log.error("MockState file missing or unreadable.");
			return false;
		}

		boolean changed = false;

		// ------------------------------------------------
		// REQUIRED ROOT SECTIONS
		// ------------------------------------------------

		if (state.users == null) {
			log.error("MockState.users missing.");
			return false;
		}

		if (state.admin == null || state.admin.pat == null) {
			log.error("MockState.admin missing or invalid.");
			return false;
		}

		if (state.collectionDetails == null || state.collectionDetails.projectName == null
				|| state.collectionDetails.projectId == null) {

			log.error("MockState.collectionDetails incomplete.");
			return false;
		}

		if (state.dataLoadConfig == null) {
			log.error("MockState.dataLoadConfig missing.");
			return false;
		}

		// ------------------------------------------------
		// NORMALIZE DATA LOAD CONFIG
		// ------------------------------------------------

		MockState.DataLoadConfig cfg = state.dataLoadConfig;

		if (cfg.windowDays <= 0) {
			cfg.windowDays = 365;
			changed = true;
		}

		if (cfg.sprintDurationDays <= 0) {
			cfg.sprintDurationDays = 14;
			changed = true;
		}

		if (cfg.sprintsPerPI <= 0) {
			cfg.sprintsPerPI = 4;
			changed = true;
		}

		if (cfg.workItemsPerDay < 0) {
			cfg.workItemsPerDay = 5;
			changed = true;
		}

		if (cfg.repoActivitiesPerSprint < 0) {
			cfg.repoActivitiesPerSprint = 3;
			changed = true;
		}

		if (cfg.buildsPerSprint < 0) {
			cfg.buildsPerSprint = 1;
			changed = true;
		}

		if (cfg.currentSprintFillPercentage <= 0 || cfg.currentSprintFillPercentage > 100) {
			cfg.currentSprintFillPercentage = 70;
			changed = true;
		}

		// ------------------------------------------------
		// ENSURE BACKFILL OBJECTS EXIST
		// ------------------------------------------------

		if (state.workItemBackfill == null) {
			state.workItemBackfill = new MockState.WorkItemBackfill();
			changed = true;
		}

		if (state.repoBackfill == null) {
			state.repoBackfill = new MockState.RepoBackfill();
			changed = true;
		}

		if (state.buildBackfill == null) {
			state.buildBackfill = new MockState.BuildBackfill();
			changed = true;
		}

		if (state.webhookMutationState == null) {
			state.webhookMutationState = new MockState.WebhookMutationState();
			changed = true;
		}

		// ------------------------------------------------
		// ENSURE REPO BLOCK EXISTS IF CONFIGURED
		// ------------------------------------------------

		if (state.repo != null) {

			if (state.repo.repoName == null) {
				log.error("Repo name missing in MockState.repo.");
				return false;
			}

			if (state.repo.defaultBranch == null) {
				state.repo.defaultBranch = "main";
				changed = true;
			}

		}

		// ------------------------------------------------
		// VALIDATE USERS
		// ------------------------------------------------

		if (state.users.isEmpty()) {
			log.error("No users configured in MockState.");
			return false;
		}

		// Remove null users
		state.users.removeIf(u -> u == null || u.username == null || u.pat == null);

		if (state.users.isEmpty()) {
			log.error("All users invalid in MockState.");
			return false;
		}

		// ------------------------------------------------
		// SAVE IF NORMALIZED
		// ------------------------------------------------

		if (changed) {
			repository.save(state);
			log.info("MockState normalized and updated.");
		}

		return true;
	}
}