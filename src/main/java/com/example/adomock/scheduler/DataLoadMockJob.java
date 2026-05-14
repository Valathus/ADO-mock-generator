package com.example.adomock.scheduler;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.adomock.service.IterationService;
import com.example.adomock.service.MockStateInitializer;
import com.example.adomock.service.PipelineSeederService;
import com.example.adomock.service.RepoBackfillController;
import com.example.adomock.service.WorkItemsCreationService;

@DisallowConcurrentExecution
@Component
public class DataLoadMockJob implements Job {

	private static final Logger log = LoggerFactory.getLogger(DataLoadMockJob.class);

	private final IterationService iterationService;
	private final WorkItemsCreationService workItemsCreationService;
	private final PipelineSeederService buildSeederService;
	private final RepoBackfillController backfillController;
	private final MockStateInitializer mockStateInitializer;

	public DataLoadMockJob(WorkItemsCreationService seederService, PipelineSeederService buildSeederService,
			RepoBackfillController backfill, IterationService iterationService,
			MockStateInitializer mockStateInitializer) {
		this.workItemsCreationService = seederService;
		this.buildSeederService = buildSeederService;
		this.backfillController = backfill;
		this.iterationService = iterationService;
		this.mockStateInitializer = mockStateInitializer;
	}

	@Override
	public void execute(JobExecutionContext context) {

		try {

			if (!mockStateInitializer.initializeAndValidate()) {
				log.warn("MockState invalid. Seeder execution skipped.");
				return;
			}

			log.info("Iteration seeder started");
			iterationService.ensureSprintStructure();
			log.info("Iteration seeder completed");

			log.info("work item creation  seeder started");
			workItemsCreationService.isWorkItemsDataLoaded();
			log.info("work item creation seeder completed");
			
			log.info("Repo seeder started");
			backfillController.runBatchIfRequired();
			log.info("Repo seeder completed");
			
			log.info("pipeline seeder started");
			buildSeederService.seedBuildHistoryIfRequired();
			log.info("pipeline seeder completed");

		} catch (Exception ex) {
			log.error("Scheduler job failed", ex);
		}
	}

}
