package com.example.adomock.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;
import com.example.adomock.state.MockState.ProgramIteration;
import com.example.adomock.state.MockState.Sprint;

@Component
public class MutationExecutor {

	private static final Logger log = LoggerFactory.getLogger(MutationExecutor.class);

	private final FileStateRepository repository;
	private final RepoWebhookEngine repoWorkflowEngine;
	private final PipelineWebhookEngine pipelineWebhookEngine;
	private final IdentityResolverService identityResolver;
	private final WorkItemWebhookEngine workItemWebhookEngine;
	private final IterationWebhookEngine iterationWebhookEngine;
	private final TfvcWebhookEngine tfvcWebhookEngine;
	private final ReleaseWebhookEngine releaseWebhookEngine;
	private final TestRunWebhookEngine testRunWebhookEngine;

	@Value("${mutations.perHour:10}")
	private int mutationsPerHour;

	@Value("${mutations.mix.workItemUpdatePct:50}")
	private int workItemUpdatePct;

	@Value("${mutations.mix.repoWorkflowPct:30}")
	private int repoWorkflowPct;

	@Value("${mutations.mix.pipelinePct:20}")
	private int pipelinePct;

	@Value("${mock.mutation.enabled:true}")
	private boolean mutationEnabled;

	@Value("${mock.random.seed:12345}")
	private long randomSeed;

	public MutationExecutor(FileStateRepository repository, RepoWebhookEngine repoWorkflowEngine,
			PipelineWebhookEngine pipelineWebhookEngine, IdentityResolverService identityResolver,
			WorkItemWebhookEngine workItemWebhookEngine, IterationWebhookEngine iterationWebhookEngine,
			TfvcWebhookEngine tfvcWebhookEngine, ReleaseWebhookEngine releaseWebhookEngine,
			TestRunWebhookEngine testRunWebhookEngine) {
		this.repository = repository;
		this.repoWorkflowEngine = repoWorkflowEngine;
		this.pipelineWebhookEngine = pipelineWebhookEngine;
		this.identityResolver = identityResolver;
		this.workItemWebhookEngine = workItemWebhookEngine;
		this.iterationWebhookEngine = iterationWebhookEngine;
		this.tfvcWebhookEngine = tfvcWebhookEngine;
		this.releaseWebhookEngine = releaseWebhookEngine;
		this.testRunWebhookEngine = testRunWebhookEngine;
	}

	public void runCycle() {

		int totalPct = workItemUpdatePct + repoWorkflowPct + pipelinePct;
		if (totalPct != 100) {
			log.warn("Mutation mix percentages do not sum to 100. Current total={}", totalPct);
		}

		MockState state = repository.load();

		if (!state.webhookEnabled) {
			log.info("Webhook simulation disabled (state.webhookEnabled=false). Skipping cycle.");
			return;
		}

		if (state.workItemBackfill == null || !state.workItemBackfill.completed) {
			log.info("Work item backfill not completed yet. Skipping mutation cycle.");
			return;
		}

		// 1) Keep iterations/sprints fresh + extend window if needed
		boolean changed = iterationWebhookEngine.tickAndMaybeSeed(state);
		if (changed) {
			repository.save(state);
		}

		// Validate iteration structure
		if (state.programIterations == null || state.programIterations.isEmpty()) {
			log.warn("No programIterations available. Skipping mutation cycle.");
			return;
		}

		if (!hasAnyWorkItems(state)) {
			log.warn("No work items available in any sprint. Skipping mutation cycle.");
			return;
		}

		// Repo/pipeline mutations require repo presence
		boolean repoReady = (state.repo != null && state.repo.repoId != null);

		// Correlation for downstream webhook correlation
		String batchId = UUID.randomUUID().toString();
		Random random = new Random(randomSeed ^ batchId.hashCode());

		log.info("Starting mutation cycle | batchId={} | mutationsPerHour={} | currentSprint={}", batchId,
				mutationsPerHour, state.currentSprintNumber);

		int success = 0;
		int failed = 0;

		addUserIds(state);

		if (state.webhookMutationState == null) {
			state.webhookMutationState = new MockState.WebhookMutationState();
		}

		state.webhookMutationState.lastCycleStartedAt = java.time.Instant.now();

		for (int i = 1; i <= mutationsPerHour; i++) {
			String correlation = "mockRun:" + batchId + ":" + i;

			try {
				int roll = random.nextInt(100);

				if (roll < workItemUpdatePct) {
					String workItemId = pickRandomWorkItemIdFromCurrentOrRecentSprint(state, random);
					if (workItemId == null) {
						log.warn("No work item found to mutate. correlation={}", correlation);
					} else {
						log.info("mutating started for boards {}", workItemId);
						workItemWebhookEngine.runWebhookUpdate(workItemId, correlation, random);
						log.info("mutating completed for boards {}", workItemId);
					}

				} else if (roll < workItemUpdatePct + repoWorkflowPct) {
					if (!repoReady) {
						log.warn("Repo not available; skipping repo workflow mutation. correlation={}", correlation);
					} else {
						// 15% of repo-slot goes to TFVC checkin if enabled, rest to Git workflow
						if (tfvcWebhookEngine.isEnabled() && random.nextInt(100) < 15) {
							log.info("mutating started for tfvc");
							tfvcWebhookEngine.runCheckin(correlation, random);
							log.info("mutating completed for tfvc");
						} else {
							log.info("mutating started for repos");
							repoWorkflowEngine.runWebhookCycle(correlation, random);
							log.info("mutating completed for repos");
						}
					}

				} else {
					if (!repoReady) {
						log.warn("Repo not available; skipping pipeline mutation. correlation={}", correlation);
					} else {
						// Pipeline slot: 60% build, 20% release (if enabled), 20% testrun (if enabled)
						int pipelineRoll = random.nextInt(100);
						if (pipelineRoll < 60 || (!releaseWebhookEngine.isEnabled() && !testRunWebhookEngine.isEnabled())) {
							String workItemId = pickRandomWorkItemIdFromCurrentOrRecentSprint(state, random);
							if (workItemId == null) {
								log.warn("No work item found for pipeline link. correlation={}", correlation);
							} else {
								log.info("mutating started for pipeline");
								pipelineWebhookEngine.runPipelineWebhook(workItemId, correlation);
								log.info("mutating completed for pipeline");
							}
						} else if (pipelineRoll < 80 && releaseWebhookEngine.isEnabled()) {
							log.info("mutating started for release");
							releaseWebhookEngine.runReleaseWebhook(correlation, random);
							log.info("mutating completed for release");
						} else if (testRunWebhookEngine.isEnabled()) {
							log.info("mutating started for testrun");
							testRunWebhookEngine.runTestRunWebhook(correlation, random);
							log.info("mutating completed for testrun");
						} else {
							String workItemId = pickRandomWorkItemIdFromCurrentOrRecentSprint(state, random);
							if (workItemId != null) {
								pipelineWebhookEngine.runPipelineWebhook(workItemId, correlation);
							}
						}
					}
				}

				success++;
				state.webhookMutationState.totalActions++;

			} catch (Exception ex) {
				failed++;
				log.error("Mutation action failed | correlation={} | err={}", correlation, ex.getMessage(), ex);
			}
		}
		state.webhookMutationState.totalCycles++;
		state.webhookMutationState.lastCycleCompletedAt = java.time.Instant.now();
		repository.save(state);

		log.info("Mutation cycle completed | batchId={} | success={} failed={}", batchId, success, failed);
	}

	/**
	 * Prefer current sprint work items. If none exist, fall back to most recent
	 * sprint with items.
	 */
	private String pickRandomWorkItemIdFromCurrentOrRecentSprint(MockState state, Random random) {

		Sprint current = findSprintByNumber(state, state.currentSprintNumber).orElse(null);

		if (current != null && current.workItemIds != null && !current.workItemIds.isEmpty()) {
			return current.workItemIds.get(random.nextInt(current.workItemIds.size()));
		}

		// fallback: pick from latest sprint that has work items
		List<Sprint> allSprints = flattenSprints(state);
		allSprints.sort(Comparator.comparingInt(s -> s.sprintNumber));

		for (int idx = allSprints.size() - 1; idx >= 0; idx--) {
			Sprint s = allSprints.get(idx);
			if (s.workItemIds != null && !s.workItemIds.isEmpty()) {
				return s.workItemIds.get(random.nextInt(s.workItemIds.size()));
			}
		}

		return null;
	}

	private Optional<Sprint> findSprintByNumber(MockState state, int sprintNumber) {
		if (state.programIterations == null)
			return Optional.empty();
		for (ProgramIteration pi : state.programIterations) {
			if (pi.sprints == null)
				continue;
			for (Sprint s : pi.sprints) {
				if (s.sprintNumber == sprintNumber)
					return Optional.of(s);
			}
		}
		return Optional.empty();
	}

	private List<Sprint> flattenSprints(MockState state) {
		List<Sprint> out = new ArrayList<>();
		if (state.programIterations == null)
			return out;
		for (ProgramIteration pi : state.programIterations) {
			if (pi.sprints != null)
				out.addAll(pi.sprints);
		}
		return out;
	}

	private void addUserIds(MockState state) {
		if (state.users == null)
			return;
		for (MockState.User user : state.users) {
			if (user != null && user.id == null) {
				identityResolver.resolveIdentityDescriptor(user);
			}
		}
	}

	private boolean hasAnyWorkItems(MockState state) {
		if (state.programIterations == null)
			return false;
		for (ProgramIteration pi : state.programIterations) {
			if (pi.sprints == null)
				continue;
			for (Sprint s : pi.sprints) {
				if (s.workItemIds != null && !s.workItemIds.isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}
}