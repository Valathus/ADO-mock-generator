package com.example.adomock.scheduler;

import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

	// -----------------------------
	// DATA LOAD (Run once)
	// -----------------------------
	@Bean
	public JobDetail dataLoadMockJobDetail() {
		return JobBuilder.newJob(DataLoadMockJob.class).withIdentity("dataLoadMockJob").storeDurably().build();
	}

	@Bean
	public Trigger dataLoadTrigger(@Qualifier("dataLoadMockJobDetail")
	JobDetail jobDetail) {

		return TriggerBuilder.newTrigger().forJob(jobDetail).withIdentity("dataLoadMockTrigger").startNow()
				.withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0)
						.withMisfireHandlingInstructionFireNow())
				.build();
	}

	// -----------------------------
	// WEBHOOK (Every 15 minutes)
	// -----------------------------
	@Bean
	public JobDetail webhookMockJobDetail() {
		return JobBuilder.newJob(WebhookMockJob.class).withIdentity("webhookMockJob").storeDurably().build();
	}

	@Bean
	public Trigger webhookTrigger(@Qualifier("webhookMockJobDetail")
	JobDetail jobDetail) {

		return TriggerBuilder.newTrigger().forJob(jobDetail).withIdentity("webhookMockTrigger").withSchedule(
				CronScheduleBuilder.cronSchedule("0 0/15 5-23 ? * MON-SAT").withMisfireHandlingInstructionDoNothing())
				.build();
	}
}
