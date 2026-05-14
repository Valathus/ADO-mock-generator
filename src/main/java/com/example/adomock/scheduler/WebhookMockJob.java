package com.example.adomock.scheduler;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.adomock.service.MutationExecutor;
import com.example.adomock.state.FileStateRepository;
import com.example.adomock.state.MockState;

@DisallowConcurrentExecution
@Component
public class WebhookMockJob implements Job {

	private static final Logger log = LoggerFactory.getLogger(WebhookMockJob.class);

	private final MutationExecutor webhookService;
	private final FileStateRepository repository;

	public WebhookMockJob(MutationExecutor webhookService, FileStateRepository repository) {
		this.webhookService = webhookService;
		this.repository = repository;
	}

	@Override
	public void execute(JobExecutionContext context) {

		try {
			
			MockState state = repository.load();
			
			if(state.webhookEnabled) {
				webhookService.runCycle();
			}

		} catch (Exception ex) {
			log.error("Scheduler job failed", ex);
		}
	}

}
