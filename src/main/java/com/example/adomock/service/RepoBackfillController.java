package com.example.adomock.service;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;

@Service
public class RepoBackfillController {

	private final FileStateRepository repository;
	private final RepoSeederService repoSeederService;
	private final RepoWorkflowEngine repoWorkflowEngine;

	@Value("${backfill.enabled:true}")
	private boolean enabled;

	@Value("${backfill.batchWorkflows:10}")
	private int workflowsPerBatch;

	public RepoBackfillController(FileStateRepository repository, RepoSeederService repoSeederService,
			RepoWorkflowEngine repoWorkflowEngine) {
		this.repository = repository;
		this.repoSeederService = repoSeederService;
		this.repoWorkflowEngine = repoWorkflowEngine;
	}

	public boolean runBatchIfRequired() {

		if (!enabled) {
			return false;
		}

		MockState state = repository.load();

		if (state.repoBackfill == null) {
			state.repoBackfill = new MockState.RepoBackfill();
		}

		if (state.repoBackfill.completed) {
			return false;
		}

		if (state.repoBackfill.startedAt == null) {
			state.repoBackfill.startedAt = Instant.now();
		}

		repoSeederService.seedIfRequired();

		int activities = state.dataLoadConfig.repoActivitiesPerSprint;

		LocalDate today = LocalDate.now();

		for (int i = 0; i < activities; i++) {
			repoWorkflowEngine.runWorkflowCycle(today);
		}

		state.repoBackfill.completed = true;
		state.repoBackfill.completedAt = Instant.now();

		repository.save(state);

		return true;
	}
}
