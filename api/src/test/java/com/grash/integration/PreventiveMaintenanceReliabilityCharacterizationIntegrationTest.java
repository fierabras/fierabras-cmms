package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.Concurrently;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.job.WorkOrderCreationJob;
import com.grash.model.Asset;
import com.grash.model.User;
import com.grash.service.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.quartz.CalendarIntervalScheduleBuilder;
import org.quartz.CalendarIntervalTrigger;
import org.quartz.CronTrigger;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.spi.OperableTrigger;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-24 characterization: fixes, before any change, how the preventive maintenance jobs behave: the job run twice
 * for one occurrence, the jobs left after deleting, rescheduling, pausing and resuming a preventive maintenance based
 * on the completed date, an occurrence missed while the scheduler is stopped (by recurrence type and for the one-shot
 * jobs), the archived asset, a failure while the job creates the work order, a failure of the scheduler, the disabled
 * technician and the manual trigger.
 * <p>
 * The job is run the way Quartz runs it in production: a new instance from {@code createBean} (with its proxies) and
 * {@code execute}. The tests use Quartz in memory ({@code RAMJobStore}); the missed occurrence is reproduced on the
 * trigger itself, with the call the job store makes when it finds a misfire ({@code updateAfterMisfire}).
 */
@AutoConfigureMockMvc
class PreventiveMaintenanceReliabilityCharacterizationIntegrationTest extends AbstractIntegrationTest {

    private static final String FUTURE = "2099-01-01T00:00:00.000+00:00";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final Scheduler scheduler;
    private final ScheduleService scheduleService;
    private final ApplicationContext context;
    private final List<CommonData> created = new ArrayList<>();
    private final List<Long> schedules = new ArrayList<>();

    @Autowired
    PreventiveMaintenanceReliabilityCharacterizationIntegrationTest(TestOrganizations organizations,
                                                                    TestRecords records, MockMvc mockMvc,
                                                                    ObjectMapper objectMapper, JdbcTemplate jdbc,
                                                                    Scheduler scheduler,
                                                                    ScheduleService scheduleService,
                                                                    ApplicationContext context) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.faults = new FaultInjector(jdbc);
        this.scheduler = scheduler;
        this.scheduleService = scheduleService;
        this.context = context;
    }

    @AfterEach
    void cleanUp() throws SchedulerException {
        faults.removeAll();
        for (Long schedule : schedules)
            for (JobKey key : jobsOf(schedule)) scheduler.deleteJob(key);
        schedules.clear();
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData data() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    // --- helpers ---

    private long count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private List<String> audit(CommonData data) {
        return jdbc.queryForList("SELECT action || ' ' || entity_type || ' ' || source FROM audit_log "
                + "WHERE company_id = ? ORDER BY id", String.class, data.o1().company().getId());
    }

    private Response send(MockHttpServletRequestBuilder request, String body, User user) {
        return api.call(request.contentType(MediaType.APPLICATION_JSON).content(body), api.login(user));
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private static String ref(Long id) {
        return "{\"id\":" + id + "}";
    }

    private static String iso(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    /**
     * A preventive maintenance created through the API, which schedules its jobs.
     */
    private long preventiveMaintenance(User user, String recurrenceType, String recurrenceBasedOn, String startsOn,
                                       String extra) {
        String name = TestRecords.unique("P-24 PM");
        Response response = send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\"" + name
                + "\",\"frequency\":1,\"recurrenceType\":\"" + recurrenceType + "\",\"recurrenceBasedOn\":\""
                + recurrenceBasedOn + "\",\"startsOn\":\"" + startsOn + "\"" + extra + "}", user);
        assertEquals(200, response.status(), response.toString());
        long pm = json(response).get("id").asLong();
        schedules.add(scheduleOf(pm));
        return pm;
    }

    private long preventiveMaintenance(User user, String extra) {
        return preventiveMaintenance(user, "DAILY", "SCHEDULED_DATE", FUTURE, extra);
    }

    private long scheduleOf(long preventiveMaintenance) {
        return jdbc.queryForObject("SELECT schedule_id FROM preventive_maintenance WHERE id = ?", Long.class,
                preventiveMaintenance);
    }

    private void tasks(long preventiveMaintenance, User user, String... labels) {
        List<String> tasks = new ArrayList<>();
        for (String label : labels) tasks.add("{\"label\":\"" + label + "\",\"taskType\":\"SUBTASK\",\"options\":[]}");
        Response response = send(patch("/tasks/preventive-maintenance/" + preventiveMaintenance),
                "[" + String.join(",", tasks) + "]", user);
        assertEquals(200, response.status(), response.toString());
    }

    private long workOrdersOf(long preventiveMaintenance) {
        return count("SELECT count(*) FROM work_order WHERE parent_preventive_maintenance_id = ?",
                preventiveMaintenance);
    }

    private long tasksOfWorkOrdersOf(long preventiveMaintenance) {
        return count("SELECT count(*) FROM task t JOIN work_order w ON w.id = t.work_order_id "
                + "WHERE w.parent_preventive_maintenance_id = ?", preventiveMaintenance);
    }

    private boolean disabled(long schedule) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT disabled FROM schedule WHERE id = ?", Boolean.class,
                schedule));
    }

    /**
     * Every job of the schedule, in both groups: {@code wo-job-<id>}, {@code wo-job-chained-<id>-<time>} and
     * {@code notif-job-<id>}.
     */
    private List<JobKey> jobsOf(long schedule) throws SchedulerException {
        List<JobKey> keys = new ArrayList<>();
        for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals("wo-group")))
            if (key.getName().equals("wo-job-" + schedule) || key.getName().startsWith("wo-job-chained-" + schedule
                    + "-")) keys.add(key);
        JobKey notification = new JobKey("notif-job-" + schedule, "notif-group");
        if (scheduler.checkExists(notification)) keys.add(notification);
        return keys;
    }

    private List<String> jobNamesOf(long schedule) throws SchedulerException {
        return jobsOf(schedule).stream().map(key -> key.getName().replaceAll("-\\d{10,}$", "-<time>")).sorted()
                .toList();
    }

    private List<JobKey> chainedJobsOf(long schedule) throws SchedulerException {
        return jobsOf(schedule).stream().filter(key -> key.getName().startsWith("wo-job-chained-")).toList();
    }

    /**
     * The job run as Quartz runs it in production: a new instance from the bean factory and {@code execute}.
     */
    private void fire(long schedule, Date occurrence) throws Exception {
        WorkOrderCreationJob job = context.getAutowireCapableBeanFactory().createBean(WorkOrderCreationJob.class);
        job.execute(jobContext(schedule, occurrence));
    }

    private JobExecutionContext jobContext(long schedule, Date occurrence) throws SchedulerException {
        JobDataMap jobData = new JobDataMap();
        jobData.put("scheduleId", schedule);
        JobExecutionContext jobContext = mock(JobExecutionContext.class);
        when(jobContext.getMergedJobDataMap()).thenReturn(jobData);
        when(jobContext.getScheduledFireTime()).thenReturn(occurrence);
        when(jobContext.getFireTime()).thenReturn(new Date());
        when(jobContext.getScheduler()).thenReturn(scheduler);
        return jobContext;
    }

    /**
     * What the job store does with the trigger when it finds that its fire time passed while the scheduler was
     * stopped: the next fire time after applying the misfire instruction of the trigger.
     */
    private static Date nextFireTimeAfterOutage(Trigger stored, Date missed) {
        OperableTrigger trigger = (OperableTrigger) ((OperableTrigger) stored).clone();
        trigger.setNextFireTime(missed);
        trigger.updateAfterMisfire(null);
        return trigger.getNextFireTime();
    }

    private static boolean firesNow(Date nextFireTime) {
        return nextFireTime != null && Math.abs(nextFireTime.getTime() - System.currentTimeMillis()) < 10_000;
    }

    // =====================================================================================================
    // One occurrence, run twice (CA-P24-01, CA-P24-02)
    // =====================================================================================================

    @Nested
    class Occurrences {

        @Test
        void ca01_theJobOfOneOccurrenceRunTwice_twoWorkOrders_eachWithItsTasks() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            tasks(pm, data.o1().admin(), "P-24 first", "P-24 second");
            long schedule = scheduleOf(pm);
            Date occurrence = new Date();

            fire(schedule, occurrence);
            fire(schedule, occurrence);

            // before P-24: the job does not check whether the occurrence already generated its work order
            assertAll(
                    () -> assertEquals(2, workOrdersOf(pm)),
                    () -> assertEquals(4, tasksOfWorkOrdersOf(pm)));
        }

        @Test
        void ca02_twoSimultaneousRunsOfOneOccurrence_twoWorkOrders() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            long schedule = scheduleOf(pm);
            Date occurrence = new Date();

            List<Concurrently.Outcome<Boolean>> outcomes = Concurrently.run(2, i -> () -> {
                fire(schedule, occurrence);
                return true;
            });

            assertAll(
                    () -> assertTrue(outcomes.stream().allMatch(Concurrently.Outcome::succeeded), outcomes.toString()),
                    () -> assertEquals(2, workOrdersOf(pm)));
        }

        @Test
        void ca09_aManualTriggerAfterTheOccurrence_createsItsWorkOrder() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            fire(scheduleOf(pm), new Date());

            Response manual = api.call(post("/preventive-maintenances/" + pm + "/trigger-work-order"),
                    api.login(data.o1().admin()));

            assertAll(
                    () -> assertEquals(200, manual.status(), manual.toString()),
                    () -> assertEquals(2, workOrdersOf(pm)),
                    // the job's work order has no creator; the manual one, the user who triggered it
                    () -> assertEquals(List.of("null", String.valueOf(data.o1().admin().getId())),
                            jdbc.queryForList("SELECT coalesce(created_by::text, 'null') FROM work_order "
                                    + "WHERE parent_preventive_maintenance_id = ? ORDER BY id", String.class, pm)));
        }

        @Test
        void ca09_theAssignedTechnicianDisabled_theOccurrenceGeneratesTheWorkOrderWithTheSameTechnician()
                throws Exception {
            CommonData data = data();
            User technician = data.o1().technician();
            long pm = preventiveMaintenance(data.o1().admin(), ",\"primaryUser\":" + ref(technician.getId())
                    + ",\"assignedTo\":[" + ref(technician.getId()) + "]");
            jdbc.update("UPDATE own_user SET enabled = false WHERE id = ?", technician.getId());

            fire(scheduleOf(pm), new Date());

            // EX-11 already holds: the job route does not reject a disabled user
            assertAll(
                    () -> assertEquals(1, workOrdersOf(pm)),
                    () -> assertEquals(technician.getId(), jdbc.queryForObject("SELECT primary_user_id FROM "
                            + "work_order WHERE parent_preventive_maintenance_id = ?", Long.class, pm)),
                    () -> assertEquals(1, count("SELECT count(*) FROM work_order_assigned_to a JOIN work_order w "
                            + "ON w.id = a.work_order_id WHERE w.parent_preventive_maintenance_id = ? "
                            + "AND a.assigned_to_id = ?", pm, technician.getId())));
        }
    }

    // =====================================================================================================
    // Jobs left behind (CA-P24-03, TD-P15-01)
    // =====================================================================================================

    @Nested
    class JobsLeft {

        private long completionBased(CommonData data, Date completed) throws SchedulerException {
            long pm = preventiveMaintenance(data.o1().admin(), "DAILY", "COMPLETED_DATE", FUTURE, "");
            long schedule = scheduleOf(pm);
            // what closing a work order of the preventive maintenance does (WorkOrderService)
            scheduleService.scheduleNextWorkOrderJobAfterCompletion(schedule, completed);
            // P-24 (D-P24-5): before, the first one-shot job stayed beside the chained one, and the notification of
            // the chained occurrence was refused because notif-job-<id> already existed
            assertEquals(List.of("notif-job-" + schedule, "wo-job-chained-" + schedule + "-<time>"),
                    jobNamesOf(schedule));
            return pm;
        }

        private long completionBased(CommonData data) throws SchedulerException {
            return completionBased(data, Date.from(Instant.now().plus(10, ChronoUnit.DAYS)));
        }

        @Test
        void ca03_deleted_noJobLeft() throws SchedulerException {
            CommonData data = data();
            long pm = completionBased(data);
            long schedule = scheduleOf(pm);

            Response response = api.call(delete("/preventive-maintenances/" + pm), api.login(data.o1().admin()));

            // P-24 (E-2, TD-P15-01): before, the chained job survived
            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(List.of(), jobNamesOf(schedule)));
        }

        @Test
        void ca03_rescheduled_onlyTheNewJobs_theNextOccurrenceGeneratesOneWorkOrder() throws Exception {
            CommonData data = data();
            long pm = completionBased(data);
            long schedule = scheduleOf(pm);

            Response response = send(patch("/schedules/" + schedule), "{\"frequency\":2,\"recurrenceType\":"
                    + "\"DAILY\",\"recurrenceBasedOn\":\"COMPLETED_DATE\",\"startsOn\":\"" + FUTURE + "\"}",
                    data.o1().admin());
            List<String> jobs = jobNamesOf(schedule);
            fire(schedule, new Date());

            // P-24 (E-2, TD-P15-01): before, the chained job survived beside the new one
            assertAll(
                    () -> assertEquals(200, response.status(), response.toString()),
                    () -> assertEquals(List.of("notif-job-" + schedule, "wo-job-" + schedule), jobs),
                    () -> assertEquals(1, workOrdersOf(pm)));
        }

        @Test
        void ca03_pausedAndResumed_onlyTheNewJobs_theNextOccurrenceGeneratesOneWorkOrder() throws Exception {
            CommonData data = data();
            long pm = completionBased(data);
            long schedule = scheduleOf(pm);

            Response pause = send(patch("/preventive-maintenances/" + pm), "{\"disabled\":true}", data.o1().admin());
            Response resume = send(patch("/preventive-maintenances/" + pm), "{\"disabled\":false}",
                    data.o1().admin());
            List<String> jobs = jobNamesOf(schedule);
            fire(schedule, new Date());

            // P-24 (E-2, TD-P15-01): before, the chained job survived beside the new one
            assertAll(
                    () -> assertEquals(200, pause.status(), pause.toString()),
                    () -> assertEquals(200, resume.status(), resume.toString()),
                    () -> assertEquals(List.of("notif-job-" + schedule, "wo-job-" + schedule), jobs),
                    () -> assertEquals(1, workOrdersOf(pm)));
        }

        @Test
        void d5_chainedTwice_theNewOccurrenceReplacesThePendingOne_withItsNotification() throws SchedulerException {
            CommonData data = data();
            long pm = completionBased(data);
            long schedule = scheduleOf(pm);
            Date second = Date.from(Instant.now().plus(12, ChronoUnit.DAYS));

            // closing again after a reopening chains another occurrence
            scheduleService.scheduleNextWorkOrderJobAfterCompletion(schedule, second);

            // P-24 (D-P24-5): before, two chained jobs (two work orders) and the notification of the second refused
            List<JobKey> chained = chainedJobsOf(schedule);
            Date notification = scheduler.getTriggersOfJob(new JobKey("notif-job-" + schedule, "notif-group"))
                    .get(0).getStartTime();
            assertAll(
                    () -> assertEquals(List.of("notif-job-" + schedule, "wo-job-chained-" + schedule + "-<time>"),
                            jobNamesOf(schedule)),
                    () -> assertEquals(Date.from(second.toInstant().plus(1, ChronoUnit.DAYS)),
                            scheduler.getTriggersOfJob(chained.get(0)).get(0).getStartTime()),
                    () -> assertTrue(Math.abs(notification.getTime() - second.getTime()) <= 3_600_000,
                            "notification at " + notification));
        }
    }

    // =====================================================================================================
    // An occurrence missed with the scheduler stopped (CA-P24-04, CA-P24-05)
    // =====================================================================================================

    @Nested
    class MissedOccurrences {

        private final Date startsOn = Date.from(Instant.now().minus(3, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.MINUTES));

        private Trigger triggerOf(long schedule) throws SchedulerException {
            return scheduler.getTrigger(new TriggerKey("wo-trigger-" + schedule, "wo-group"));
        }

        private Trigger recurring(CommonData data, String recurrenceType, String extra) throws SchedulerException {
            long pm = preventiveMaintenance(data.o1().admin(), recurrenceType, "SCHEDULED_DATE", iso(startsOn),
                    extra);
            return triggerOf(scheduleOf(pm));
        }

        @Test
        void ca04_scheduledDate_byRecurrenceType_theMissedOccurrenceIsLost_theNextFollowsTheCalendar()
                throws SchedulerException {
            CommonData data = data();
            Date missed = Date.from(Instant.now().minus(2, ChronoUnit.HOURS));
            Trigger daily = recurring(data, "DAILY", "");
            Trigger weekly = recurring(data, "WEEKLY", ",\"daysOfWeek\":[0,1,2,3,4,5,6]");
            Trigger monthly = recurring(data, "MONTHLY", "");
            Trigger yearly = recurring(data, "YEARLY", "");
            Date now = new Date();

            Date dailyNext = nextFireTimeAfterOutage(daily, missed);
            Date weeklyNext = nextFireTimeAfterOutage(weekly, missed);
            Date monthlyNext = nextFireTimeAfterOutage(monthly, missed);
            Date yearlyNext = nextFireTimeAfterOutage(yearly, missed);
            System.out.println("P-24 characterization, misfire: daily " + daily.getClass().getSimpleName() + " "
                    + daily.getMisfireInstruction() + " -> " + dailyNext + "; weekly "
                    + weekly.getClass().getSimpleName() + " " + weekly.getMisfireInstruction() + " -> " + weeklyNext
                    + "; monthly " + monthly.getClass().getSimpleName() + " " + monthly.getMisfireInstruction()
                    + " -> " + monthlyNext + "; yearly " + yearly.getClass().getSimpleName() + " "
                    + yearly.getMisfireInstruction() + " -> " + yearlyNext);

            // none fires at the restart: the next fire time is the next one of the calendar, at the time of startsOn
            long minuteOfStart = startsOn.getTime() / 60_000 % (24 * 60);
            assertAll(
                    () -> assertInstanceOf(SimpleTrigger.class, daily),
                    () -> assertEquals(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
                            daily.getMisfireInstruction()),
                    () -> assertInstanceOf(CronTrigger.class, weekly),
                    () -> assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING, weekly.getMisfireInstruction()),
                    () -> assertInstanceOf(CalendarIntervalTrigger.class, monthly),
                    () -> assertEquals(CalendarIntervalTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,
                            monthly.getMisfireInstruction()),
                    () -> assertEquals(CalendarIntervalTrigger.MISFIRE_INSTRUCTION_DO_NOTHING,
                            yearly.getMisfireInstruction()),
                    () -> assertTrue(dailyNext.after(now), "daily " + dailyNext),
                    () -> assertTrue(weeklyNext.after(now), "weekly " + weeklyNext),
                    () -> assertTrue(monthlyNext.after(now), "monthly " + monthlyNext),
                    () -> assertTrue(yearlyNext.after(now), "yearly " + yearlyNext),
                    () -> assertEquals(minuteOfStart, dailyNext.getTime() / 60_000 % (24 * 60)),
                    () -> assertEquals(minuteOfStart, monthlyNext.getTime() / 60_000 % (24 * 60)));
        }

        @Test
        void ca04_completedDate_theOneShotJobs_fireAtTheRestart() throws SchedulerException {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "DAILY", "COMPLETED_DATE", FUTURE, "");
            long schedule = scheduleOf(pm);
            Trigger first = triggerOf(schedule);
            scheduleService.scheduleNextWorkOrderJobAfterCompletion(schedule,
                    Date.from(Instant.now().plus(10, ChronoUnit.DAYS)));
            Trigger chained = scheduler.getTriggersOfJob(chainedJobsOf(schedule).get(0)).get(0);
            Date missed = Date.from(Instant.now().minus(2, ChronoUnit.HOURS));

            // no misfire instruction set: the smart policy of a one-shot SimpleTrigger is "fire now"
            assertAll(
                    () -> assertEquals(Trigger.MISFIRE_INSTRUCTION_SMART_POLICY, first.getMisfireInstruction()),
                    () -> assertEquals(Trigger.MISFIRE_INSTRUCTION_SMART_POLICY, chained.getMisfireInstruction()),
                    () -> assertTrue(firesNow(nextFireTimeAfterOutage(first, missed))),
                    () -> assertTrue(firesNow(nextFireTimeAfterOutage(chained, missed))),
                    // the chained trigger has no end: it ignores endsOn
                    () -> assertNull(chained.getEndTime()));
        }

        @Test
        void quartz_aTriggerScheduledWithAStartInThePast_isTreatedAsMissed() {
            Date past = Date.from(Instant.now().minus(3, ChronoUnit.DAYS));
            OperableTrigger doNothing = (OperableTrigger) TriggerBuilder.newTrigger().startAt(past)
                    .withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(1)
                            .withMisfireHandlingInstructionDoNothing()).build();
            OperableTrigger fireAndProceed = (OperableTrigger) TriggerBuilder.newTrigger().startAt(past)
                    .withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withIntervalInDays(1)
                            .withMisfireHandlingInstructionFireAndProceed()).build();

            // what Quartz does when it stores a new trigger: its first fire time is its start, already passed
            Date firstDoNothing = doNothing.computeFirstFireTime(null);
            Date firstFireAndProceed = fireAndProceed.computeFirstFireTime(null);

            // every reschedule (edit, import, resume) builds the trigger again from startsOn: with "fire and
            // proceed" it would fire at once, as after an outage
            assertAll(
                    () -> assertEquals(past, firstDoNothing),
                    () -> assertEquals(past, firstFireAndProceed),
                    () -> assertTrue(nextFireTimeAfterOutage(doNothing, firstDoNothing).after(new Date())),
                    () -> assertTrue(firesNow(nextFireTimeAfterOutage(fireAndProceed, firstFireAndProceed))));
        }
    }

    // =====================================================================================================
    // Work orders not generated (CA-P24-06, CA-P24-07)
    // =====================================================================================================

    @Nested
    class NotGenerated {

        @Test
        void ca06_archivedAsset_noWorkOrder_noAuditRecord_theScheduleGoesOn() throws Exception {
            CommonData data = data();
            Asset asset = records.asset(data.o1().company());
            long pm = preventiveMaintenance(data.o1().admin(), ",\"asset\":" + ref(asset.getId()));
            long schedule = scheduleOf(pm);
            jdbc.update("UPDATE asset SET archived = true WHERE id = ?", asset.getId());

            fire(schedule, new Date());

            // before P-24: only log.info
            assertAll(
                    () -> assertEquals(0, workOrdersOf(pm)),
                    () -> assertFalse(disabled(schedule)),
                    () -> assertEquals(List.of(), audit(data)));
        }

        @Test
        void ca07_theJobFailsCopyingTheTasks_nothingStays_noAuditRecord_theErrorLeavesTheJob() throws Exception {
            CommonData data = data();
            long pm = preventiveMaintenance(data.o1().admin(), "");
            tasks(pm, data.o1().admin(), "P-24 first", "P-24 second");
            long schedule = scheduleOf(pm);

            Exception error;
            int hits;
            try (FaultInjector.Fault fault = faults.failOn("task", Operation.INSERT, 1)) {
                error = assertThrows(Exception.class, () -> fire(schedule, new Date()));
                hits = fault.hits();
            }

            // the job instance Quartz gets is a CGLIB proxy, so its @Transactional applies: all is rolled back
            WorkOrderCreationJob job = context.getAutowireCapableBeanFactory().createBean(WorkOrderCreationJob.class);
            Exception failure = error;
            int reached = hits;
            assertAll(
                    () -> assertTrue(AopUtils.isCglibProxy(job)),
                    () -> assertEquals(2, reached),
                    () -> assertTrue(failure.getMessage() != null || failure.getCause() != null, failure.toString()),
                    () -> assertEquals(0, workOrdersOf(pm)),
                    () -> assertEquals(0, tasksOfWorkOrdersOf(pm)),
                    () -> assertEquals(List.of(), audit(data)));
        }
    }

    // =====================================================================================================
    // The scheduler fails (CA-P24-08)
    // =====================================================================================================

    @Nested
    class SchedulerFails {

        /**
         * Runs {@code action} while every {@code scheduleJob} of {@link ScheduleService} fails.
         */
        private void whileSchedulingFails(ThrowingRunnable action) throws Exception {
            Object target = AopTestUtils.getUltimateTargetObject(scheduleService);
            Scheduler failing = spy(scheduler);
            doThrow(new SchedulerException("P-24 injected scheduler failure")).when(failing)
                    .scheduleJob(any(JobDetail.class), any(Trigger.class));
            ReflectionTestUtils.setField(target, "scheduler", failing);
            try {
                action.run();
            } finally {
                ReflectionTestUtils.setField(target, "scheduler", scheduler);
            }
        }

        @Test
        void ca08_createEditResumeAndChain_succeed_activeWithoutJobs_noAuditRecord() throws Exception {
            CommonData data = data();
            User admin = data.o1().admin();
            long existing = preventiveMaintenance(admin, "DAILY", "COMPLETED_DATE", FUTURE, "");
            long existingSchedule = scheduleOf(existing);
            for (JobKey key : jobsOf(existingSchedule)) scheduler.deleteJob(key);
            long paused = preventiveMaintenance(admin, "");
            long pausedSchedule = scheduleOf(paused);
            assertEquals(200, send(patch("/preventive-maintenances/" + paused), "{\"disabled\":true}", admin)
                    .status());
            for (JobKey key : jobsOf(pausedSchedule)) scheduler.deleteJob(key);
            List<Response> responses = new ArrayList<>();
            List<Long> createdSchedules = new ArrayList<>();

            whileSchedulingFails(() -> {
                String name = TestRecords.unique("P-24 PM");
                Response create = send(post("/preventive-maintenances"), "{\"name\":\"" + name + "\",\"title\":\""
                        + name + "\",\"frequency\":1,\"recurrenceType\":\"DAILY\",\"recurrenceBasedOn\":"
                        + "\"SCHEDULED_DATE\",\"startsOn\":\"" + FUTURE + "\"}", admin);
                responses.add(create);
                if (create.status() == 200) createdSchedules.add(scheduleOf(json(create).get("id").asLong()));
                responses.add(send(patch("/schedules/" + existingSchedule), "{\"frequency\":2,\"recurrenceType\":"
                        + "\"DAILY\",\"recurrenceBasedOn\":\"COMPLETED_DATE\",\"startsOn\":\"" + FUTURE + "\"}",
                        admin));
                responses.add(send(patch("/preventive-maintenances/" + paused), "{\"disabled\":false}", admin));
                scheduleService.scheduleNextWorkOrderJobAfterCompletion(existingSchedule, new Date());
            });
            schedules.addAll(createdSchedules);

            // before P-24: the SchedulerException is only logged; every route answers 200
            assertAll(
                    () -> assertEquals(List.of(200, 200, 200), responses.stream().map(Response::status).toList(),
                            responses.toString()),
                    () -> assertEquals(1, createdSchedules.size()),
                    () -> assertFalse(disabled(createdSchedules.get(0))),
                    () -> assertEquals(List.of(), jobsOf(createdSchedules.get(0))),
                    () -> assertFalse(disabled(existingSchedule)),
                    () -> assertEquals(List.of(), jobsOf(existingSchedule)),
                    () -> assertFalse(disabled(pausedSchedule)),
                    () -> assertEquals(List.of(), jobsOf(pausedSchedule)),
                    () -> assertEquals(List.of("RETIRE PREVENTIVE_MAINTENANCE API",
                            "RESTORE PREVENTIVE_MAINTENANCE API"), audit(data)));
        }
    }

    // =====================================================================================================
    // The audit log takes a new action without a changeset
    // =====================================================================================================

    @Nested
    class AuditLogColumn {

        @Test
        void theActionColumn_isVarchar32_withoutCheckConstraint() {
            assertAll(
                    () -> assertEquals("character varying 32", jdbc.queryForObject("SELECT data_type || ' ' || "
                            + "character_maximum_length FROM information_schema.columns WHERE table_name = "
                            + "'audit_log' AND column_name = 'action'", String.class)),
                    () -> assertEquals(0, count("SELECT count(*) FROM pg_constraint WHERE conrelid = "
                            + "'audit_log'::regclass AND contype = 'c'")));
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

}
