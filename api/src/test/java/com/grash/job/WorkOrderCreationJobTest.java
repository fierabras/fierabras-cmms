package com.grash.job;

import com.grash.service.PreventiveMaintenanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P-24 (E-1): the job only reads the schedule and the occurrence and delegates; the guards are in
 * {@link PreventiveMaintenanceService#generateScheduledWorkOrder} (integration tests). Before P-24 the job held
 * the guards itself and called {@code createWorkOrderFromPreventiveMaintenance}.
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderCreationJobTest {

    @Mock
    private PreventiveMaintenanceService preventiveMaintenanceService;

    @InjectMocks
    private WorkOrderCreationJob workOrderCreationJob;

    private JobExecutionContext context(long scheduleId, Date scheduledFireTime) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put("scheduleId", scheduleId);
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);
        when(context.getScheduledFireTime()).thenReturn(scheduledFireTime);
        return context;
    }

    @Test
    void theOccurrenceIsTheScheduledFireTime() {
        Date scheduledFireTime = new Date(1_000_000L);

        assertDoesNotThrow(() -> workOrderCreationJob.executeInternal(context(1L, scheduledFireTime)));

        verify(preventiveMaintenanceService).generateScheduledWorkOrder(1L, scheduledFireTime);
    }

    @Test
    void withoutScheduledFireTime_theOccurrenceIsNow() {
        long before = System.currentTimeMillis();

        assertDoesNotThrow(() -> workOrderCreationJob.executeInternal(context(1L, null)));

        ArgumentCaptor<Date> occurrence = ArgumentCaptor.forClass(Date.class);
        verify(preventiveMaintenanceService).generateScheduledWorkOrder(eq(1L), occurrence.capture());
        assertTrue(occurrence.getValue().getTime() >= before);
    }

    @Test
    void contextWithoutScheduleId_propagatesError() {
        JobDataMap jobDataMap = new JobDataMap();
        JobExecutionContext context = mock(JobExecutionContext.class);
        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);

        assertThrows(ClassCastException.class, () -> workOrderCreationJob.executeInternal(context));

        verify(preventiveMaintenanceService, never()).generateScheduledWorkOrder(anyLong(), any());
    }
}
