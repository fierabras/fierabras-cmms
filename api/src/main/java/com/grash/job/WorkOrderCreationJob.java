package com.grash.job;

import com.grash.service.PreventiveMaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkOrderCreationJob extends QuartzJobBean {

    private final PreventiveMaintenanceService preventiveMaintenanceService;

    /**
     * P-24 (E-1): generates the work order of one occurrence, identified by its scheduled fire time, in the
     * transaction of {@link PreventiveMaintenanceService#generateScheduledWorkOrder}. A run outside Quartz, without
     * scheduled fire time, is an occurrence of now. If generating fails, that transaction is rolled back whole and
     * the failure is recorded apart (E-4, CA-P24-07), without retrying it (D-P24-6).
     */
    @Override
    public void executeInternal(JobExecutionContext context) throws JobExecutionException {
        Long scheduleId = context.getMergedJobDataMap().getLong("scheduleId");
        Date occurrence = context.getScheduledFireTime() == null ? new Date() : context.getScheduledFireTime();
        try {
            preventiveMaintenanceService.generateScheduledWorkOrder(scheduleId, occurrence);
        } catch (RuntimeException e) {
            log.error("P-24: the occurrence {} of schedule {} generated no work order", occurrence, scheduleId, e);
            preventiveMaintenanceService.recordGenerationFailure(scheduleId);
        }
    }
}
