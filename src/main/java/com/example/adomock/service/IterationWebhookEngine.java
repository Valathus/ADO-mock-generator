package com.example.adomock.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.adomock.state.MockState;
import com.example.adomock.state.MockState.ProgramIteration;
import com.example.adomock.state.MockState.Sprint;

@Component
public class IterationWebhookEngine {

	private static final Logger log = LoggerFactory.getLogger(IterationWebhookEngine.class);

	private final IterationService iterationService; // owns PI/sprint structure
	private final WorkItemsCreationService workItemsCreationService;
	private final RepoSeederService repoSeederService;
	private final PipelineSeederService pipelineSeederService;

	public IterationWebhookEngine(IterationService iterationService, WorkItemsCreationService workItemsCreationService,
			RepoSeederService repoSeederService, PipelineSeederService pipelineSeederService) {
		this.iterationService = iterationService;
		this.workItemsCreationService = workItemsCreationService;
		this.repoSeederService = repoSeederService;
		this.pipelineSeederService = pipelineSeederService;
	}

	/**
	 * Returns true if it changed state (current sprint moved and/or new sprints
	 * seeded).
	 */
	public boolean tickAndMaybeSeed(MockState state) {

		if (state == null || state.dataLoadConfig == null || state.calendarAnchorDate == null) {
			return false;
		}

		LocalDate anchor = state.calendarAnchorDate;
		int dur = state.dataLoadConfig.sprintDurationDays;
		int windowDays = state.dataLoadConfig.windowDays;

		LocalDate today = LocalDate.now();

		int expectedCurrentSprint = sprintNumberForDate(anchor, dur, today);

		// forward-only guard
		expectedCurrentSprint = Math.max(expectedCurrentSprint, state.currentSprintNumber);

		LocalDate windowEnd = today.plusDays(Math.max(windowDays - 1, 0));
		int requiredMaxSprint = sprintNumberForDate(anchor, dur, windowEnd);

		int existingMaxSprint = findMaxExistingSprint(state);

		boolean changed = false;

		// Advance current sprint
		if (expectedCurrentSprint > state.currentSprintNumber) {
			log.info("Current sprint advancing | from={} to={}", state.currentSprintNumber, expectedCurrentSprint);
			state.currentSprintNumber = expectedCurrentSprint;
			changed = true;
		}

		// Expand sprint structure if needed
		if (existingMaxSprint < requiredMaxSprint) {

			int from = existingMaxSprint + 1;
			int to = requiredMaxSprint;

			log.info("Expanding sprint window | creating {}..{}", from, to);

			iterationService.ensureSprintsExist(state, from, to);

			for (int sprintNumber = from; sprintNumber <= to; sprintNumber++) {

				Sprint sprint = findSprint(state, sprintNumber);
				if (sprint == null)
					continue;

				String correlation = "mockRun:iterationExpand:" + UUID.randomUUID();

				workItemsCreationService.ensureWorkItemsForSprint(state, sprint, correlation);
				repoSeederService.ensureRepoActivityForSprint(state, sprint, correlation);
				pipelineSeederService.ensureBuildsForSprint(state, sprint, correlation);
			}

			changed = true;
		}

		return changed;
	}

	private int sprintNumberForDate(LocalDate anchor, int durDays, LocalDate date) {
		long days = ChronoUnit.DAYS.between(anchor, date);
		if (days < 0)
			return 1;
		return (int) (days / durDays) + 1;
	}

	private int findMaxExistingSprint(MockState state) {
		if (state.programIterations == null || state.programIterations.isEmpty())
			return 0;

		return state.programIterations.stream().filter(pi -> pi.sprints != null).flatMap(pi -> pi.sprints.stream())
				.map(s -> s.sprintNumber).max(Comparator.naturalOrder()).orElse(0);
	}

	private Sprint findSprint(MockState state, int sprintNumber) {
		if (state.programIterations == null)
			return null;
		for (ProgramIteration pi : state.programIterations) {
			if (pi.sprints == null)
				continue;
			for (Sprint s : pi.sprints) {
				if (s.sprintNumber == sprintNumber)
					return s;
			}
		}
		return null;
	}
}