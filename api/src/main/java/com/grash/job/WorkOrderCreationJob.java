package com.grash.job;

import com.grash.service.PreventiveMaintenanceService;
import lombok.RequiredArgsConstructor;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
@RequiredArgsConstructor
public class WorkOrderCreationJob extends QuartzJobBean {

    private final PreventiveMaintenanceService preventiveMaintenanceService;

    /**
     * P-24 (E-1): generates the work order of one occurrence, identified by its scheduled fire time, in the
     * transaction of {@link PreventiveMaintenanceService#generateScheduledWorkOrder}. A run outside Quartz, without
     * scheduled fire time, is an occurrence of now.
     */
    @Override
    public void executeInternal(JobExecutionContext context) throws JobExecutionException {
        Long scheduleId = context.getMergedJobDataMap().getLong("scheduleId");
        Date occurrence = context.getScheduledFireTime() == null ? new Date() : context.getScheduledFireTime();
        preventiveMaintenanceService.generateScheduledWorkOrder(scheduleId, occurrence);
    }
}
