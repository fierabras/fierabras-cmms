package com.grash.job;

import com.grash.model.PreventiveMaintenance;
import com.grash.model.Schedule;
import com.grash.repository.ScheduleRepository;
import com.grash.service.AssetStateGuard;
import com.grash.service.PreventiveMaintenanceService;
import com.grash.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkOrderCreationJob extends QuartzJobBean {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleService scheduleService;
    private final PreventiveMaintenanceService preventiveMaintenanceService;

    @Override
    @Transactional
    public void executeInternal(JobExecutionContext context) throws JobExecutionException {
        Long scheduleId = context.getMergedJobDataMap().getLong("scheduleId");

        Schedule schedule = scheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null || schedule.isDisabled()) {
            return;
        }
        if (!scheduleService.checkIfWeeklyShouldRun(schedule)) {
            return;
        }

        PreventiveMaintenance preventiveMaintenance = schedule.getPreventiveMaintenance();
        // P-14b (D-1): no work order cites an archived asset; the schedule goes on (a schedule based on the completed
        // date chains its next occurrence from now, as a completion would)
        if (!AssetStateGuard.admits(preventiveMaintenance.getAsset())) {
            log.info("P-14b: preventive maintenance {} generated no work order: asset {} is archived",
                    preventiveMaintenance.getId(), preventiveMaintenance.getAsset().getId());
            scheduleService.scheduleNextWorkOrderJobAfterCompletion(schedule.getId(), new Date());
            return;
        }
        preventiveMaintenanceService.createWorkOrderFromPreventiveMaintenance(preventiveMaintenance);
    }
}
