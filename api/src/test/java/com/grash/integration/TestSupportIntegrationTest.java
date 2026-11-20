package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.CallRecorder;
import com.grash.integration.support.Concurrently;
import com.grash.integration.support.Concurrently.Outcome;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Fault;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.integration.support.WebhookDouble;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.WebhookEndpoint;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.RoleRepository;
import com.grash.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Smoke tests of the P-00 test support: each support is used once to show that it works.
 */
@AutoConfigureMockMvc
class TestSupportIntegrationTest extends AbstractIntegrationTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

    private final TestOrganizations organizations;
    private final TestApi api;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final RoleRepository roleRepository;
    private final WebhookEndpointRepository webhookEndpointRepository;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    TestSupportIntegrationTest(TestOrganizations organizations, MockMvc mockMvc, ObjectMapper objectMapper,
                               JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                               RoleRepository roleRepository, WebhookEndpointRepository webhookEndpointRepository) {
        this.organizations = organizations;
        this.api = new TestApi(mockMvc, objectMapper);
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.roleRepository = roleRepository;
        this.webhookEndpointRepository = webhookEndpointRepository;
        this.faults = new FaultInjector(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData newData() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private Response createRequest(String token, String title) {
        return api.call(post("/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}"), token);
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    // =====================================================================================================
    // E-4 Common starting data
    // =====================================================================================================

    @Nested
    class CommonDataTests {

        @Test
        void create_buildsO1WithOwnerAdminAndTechnician_andO2WithItsAdmin() {
            CommonData data = newData();
            O1 o1 = data.o1();
            Long o2CompanyId = data.o2().company().getId();

            assertAll(
                    () -> assertNotEquals(o1.company().getId(), o2CompanyId),
                    () -> assertEquals(List.of(true, false, false), Stream.of(o1.owner(), o1.admin(), o1.technician())
                            .map(user -> jdbcTemplate.queryForObject(
                                    "SELECT owns_company FROM own_user WHERE id = ?", Boolean.class, user.getId()))
                            .toList(), "only U0 owns O1"),
                    () -> assertEquals(3, count("SELECT count(*) FROM own_user WHERE company_id = ? AND id IN (?, ?, ?)",
                            o1.company().getId(), o1.owner().getId(), o1.admin().getId(),
                            o1.technician().getId()), "U0, A and T belong to O1"),
                    () -> assertEquals(o2CompanyId, jdbcTemplate.queryForObject(
                            "SELECT company_id FROM own_user WHERE id = ?", Long.class, data.o2().admin().getId())),
                    () -> assertEquals(RoleCode.ADMIN, roleOf(o1.owner()).getCode()),
                    () -> assertEquals(RoleCode.ADMIN, roleOf(data.o2().admin()).getCode()));
        }

        @Test
        void create_givesAdminAndTechnicianThePermissionsOfTheCriteria() {
            O1 o1 = newData().o1();
            Role admin = roleOf(o1.admin());
            Role technician = roleOf(o1.technician());
            Set<PermissionEntity> all = EnumSet.allOf(PermissionEntity.class);

            assertAll(
                    () -> assertTrue(admin.getViewPermissions().contains(PermissionEntity.SETTINGS), "A sees SETTINGS"),
                    () -> assertEquals(all, Set.copyOf(admin.getEditOtherPermissions()), "A edits others' everything"),
                    () -> assertEquals(all, Set.copyOf(admin.getDeleteOtherPermissions()), "A deletes others' everything"),
                    () -> assertTrue(technician.getEditOtherPermissions().isEmpty(), "T has no editOther"),
                    () -> assertTrue(technician.getDeleteOtherPermissions().isEmpty(), "T has no deleteOther"));
        }

        @Test
        void create_everyoneCanLogIn() {
            CommonData data = newData();

            for (User user : List.of(data.o1().owner(), data.o1().admin(), data.o1().technician(),
                    data.o2().admin())) {
                assertFalse(api.login(user).isBlank(), "login of " + user.getEmail());
            }
        }

        @Test
        void create_twice_givesUnrelatedData() {
            CommonData first = newData();
            CommonData second = newData();

            assertAll(
                    () -> assertNotEquals(first.o1().company().getId(), second.o1().company().getId()),
                    () -> assertNotEquals(first.o1().admin().getEmail(), second.o1().admin().getEmail()));
        }

        @Test
        void delete_removesTheOrganizationsAndTheirUsers() {
            CommonData data = organizations.create();
            Long o1 = data.o1().company().getId();
            Long o2 = data.o2().company().getId();

            organizations.delete(data);

            assertEquals(0, count("SELECT count(*) FROM company WHERE id IN (?, ?)", o1, o2));
            assertEquals(0, count("SELECT count(*) FROM own_user WHERE company_id IN (?, ?)", o1, o2));
        }

        private Role roleOf(User user) {
            Long roleId = jdbcTemplate.queryForObject("SELECT role_id FROM own_user WHERE id = ?", Long.class,
                    user.getId());
            return roleRepository.findById(roleId).orElseThrow();
        }
    }

    // =====================================================================================================
    // E-5 Doubles of external services
    // =====================================================================================================

    @Nested
    class ExternalDoublesTests {

        @Test
        void mail_recordsCallsAndSimulatesAFailure() {
            String[] to = {"p00@example.com"};
            assertTrue(mailDouble.calls().isEmpty(), "a new double per test");

            mailServiceFactory.getMailService().sendSimpleMessage(to, "subject", "text");
            mailDouble.failOn("sendSimpleMessage", new IllegalStateException("P-00 mail down"));

            assertThrows(IllegalStateException.class,
                    () -> mailServiceFactory.getMailService().sendSimpleMessage(to, "subject", "text"));
            List<CallRecorder.Call> calls = mailDouble.calls("sendSimpleMessage");
            assertEquals(List.of(false, true), calls.stream().map(CallRecorder.Call::failed).toList());
            assertArrayEquals(to, (String[]) calls.get(0).arguments().get(0));
            verify(mailService, times(2)).sendSimpleMessage(any(), any(), any());
        }

        @Test
        void storage_recordsTheUploadOfTheApi() {
            O1 o1 = newData().o1();

            Response response = upload(api.login(o1.admin()), "p00-ok.txt");

            assertTrue(response.is2xx(), "upload: " + response);
            List<CallRecorder.Call> uploads = storageDouble.calls("upload");
            assertEquals(1, uploads.size());
            String path = jdbcTemplate.queryForObject("SELECT path FROM file WHERE company_id = ? AND name = ?",
                    String.class, o1.company().getId(), "p00-ok.txt");
            assertTrue(storageDouble.contains(path), "the double keeps the object at " + path);
        }

        @Test
        void storage_simulatedFailure_failsTheUpload() {
            O1 o1 = newData().o1();
            storageDouble.failOn(CallRecorder.ANY, new IllegalStateException("P-00 storage down"));

            Response response = upload(api.login(o1.admin()), "p00-fail.txt");

            assertFalse(response.is2xx(), "upload: " + response);
            assertTrue(storageDouble.calls("upload").get(0).failed());
            assertEquals(0, count("SELECT count(*) FROM file WHERE company_id = ?", o1.company().getId()));
        }

        @Test
        void webhook_recordsTheDispatchOfARealEvent() {
            O1 o1 = newData().o1();
            addEndpoint(o1, WebhookEvent.NEW_REQUEST);

            Response response = createRequest(api.login(o1.admin()), "P-00 webhook " + UUID.randomUUID());

            assertTrue(response.is2xx(), "create request: " + response);
            CallRecorder.Call call = webhookDouble.awaitCalls(WebhookEvent.NEW_REQUEST.name(), 1, ASYNC_TIMEOUT).get(0);
            assertAll(
                    () -> assertFalse(call.failed()),
                    () -> assertEquals(WebhookDouble.ENDPOINT_URL, call.arguments().get(0).toString()),
                    () -> assertTrue(call.arguments().get(1).toString().contains("\"requestId\""),
                            "body: " + call.arguments().get(1)));
        }

        @Test
        void webhook_simulatedFailure_isRecordedAndDoesNotFailTheOperation() {
            O1 o1 = newData().o1();
            addEndpoint(o1, WebhookEvent.NEW_REQUEST);
            webhookDouble.failOn(CallRecorder.ANY, new IllegalStateException("P-00 receiver down"));

            Response response = createRequest(api.login(o1.admin()), "P-00 webhook " + UUID.randomUUID());

            assertTrue(response.is2xx(), "create request: " + response);
            assertTrue(webhookDouble.awaitCalls(WebhookEvent.NEW_REQUEST.name(), 1, ASYNC_TIMEOUT).get(0).failed());
        }

        private Response upload(String token, String name) {
            MockMultipartFile file = new MockMultipartFile("files", name, MediaType.TEXT_PLAIN_VALUE,
                    "P-00".getBytes(StandardCharsets.UTF_8));
            return api.call(multipart("/files/upload").file(file).param("folder", "p00").param("hidden", "false")
                    .param("type", "OTHER"), token);
        }

        private void addEndpoint(O1 o1, WebhookEvent event) {
            WebhookEndpoint endpoint = new WebhookEndpoint();
            endpoint.setUrl(WebhookDouble.ENDPOINT_URL);
            endpoint.setSecret("p00-secret");
            endpoint.setEvent(event);
            endpoint.setCompany(o1.company());
            webhookEndpointRepository.save(endpoint);
        }
    }

    // =====================================================================================================
    // E-6 Concurrency
    // =====================================================================================================

    @Nested
    class ConcurrencyTests {

        @Test
        void run_keepsAllTasksInFlightAtTheSameTime() {
            int count = 8;
            CountDownLatch inFlight = new CountDownLatch(count);

            List<Outcome<Boolean>> outcomes = Concurrently.run(count, i -> () -> {
                inFlight.countDown();
                return inFlight.await(5, TimeUnit.SECONDS);
            });

            assertEquals(count, outcomes.size());
            assertTrue(outcomes.stream().allMatch(outcome -> outcome.succeeded() && outcome.value()),
                    "every task saw the others in flight: " + outcomes);
        }

        @Test
        void run_collectsEveryResponseOfTheApi() {
            O1 o1 = newData().o1();
            String token = api.login(o1.admin());

            List<Outcome<Response>> outcomes = Concurrently.run(8, i -> () -> api.call(get("/auth/me"), token));

            assertEquals(8, outcomes.size());
            assertTrue(outcomes.stream().allMatch(outcome -> outcome.succeeded() && outcome.value().status() == 200),
                    "all answered 200: " + outcomes);
        }

        @Test
        void run_keepsFailuresInTaskOrderWithoutLosingTheOtherOutcomes() {
            List<Callable<Integer>> tasks = List.of(() -> 0, () -> {
                throw new IllegalStateException("P-00 task 1");
            }, () -> 2);

            List<Outcome<Integer>> outcomes = Concurrently.run(tasks, ASYNC_TIMEOUT);

            assertAll(
                    () -> assertEquals(0, outcomes.get(0).value()),
                    () -> assertInstanceOf(IllegalStateException.class, outcomes.get(1).error()),
                    () -> assertEquals(2, outcomes.get(2).value()));
        }
    }

    // =====================================================================================================
    // E-7 Injected failure
    // =====================================================================================================

    @Nested
    class FaultInjectionTests {

        private static final String PROBE = "p00_probe";

        @BeforeEach
        void createProbeTable() {
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS " + PROBE + " (id bigserial PRIMARY KEY, v text)");
            jdbcTemplate.execute("TRUNCATE " + PROBE);
        }

        @AfterEach
        void dropProbeTable() {
            faults.removeAll();
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE);
        }

        @Test
        void failOn_insert_failsTheApiOperationAndLeavesNoRow() {
            O1 o1 = newData().o1();
            String token = api.login(o1.admin());
            String title = "P-00 fault " + UUID.randomUUID();

            Response failed;
            try (Fault fault = faults.failOn("request", Operation.INSERT)) {
                failed = createRequest(token, title);
                assertEquals(1, fault.hits());
            }
            Response afterClose = createRequest(token, title);

            assertAll(
                    () -> assertFalse(failed.is2xx(), "with the fault: " + failed),
                    () -> assertTrue(afterClose.is2xx(), "after close: " + afterClose),
                    () -> assertEquals(1, count("SELECT count(*) FROM request WHERE title = ?", title)));
        }

        @Test
        void failOn_afterRows_failsInTheMiddleAndRollsBackEverything() {
            Fault fault = faults.failOn(PROBE, Operation.INSERT, 2);

            assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status -> {
                for (int i = 0; i < 3; i++) {
                    jdbcTemplate.update("INSERT INTO " + PROBE + " (v) VALUES (?)", "row " + i);
                }
            }));

            assertEquals(3, fault.hits());
            assertEquals(0, count("SELECT count(*) FROM " + PROBE));
        }

        @Test
        void failAtCommit_letsTheStatementsPassAndFailsTheCommit() {
            Fault fault = faults.failAtCommit(PROBE, Operation.INSERT);
            List<Integer> inserted = new ArrayList<>();

            assertThrows(RuntimeException.class, () -> transactionTemplate.executeWithoutResult(status ->
                    inserted.add(jdbcTemplate.update("INSERT INTO " + PROBE + " (v) VALUES ('x')"))));

            assertEquals(List.of(1), inserted, "the statement itself succeeded");
            assertEquals(1, fault.hits());
            assertEquals(0, count("SELECT count(*) FROM " + PROBE));
        }

        @Test
        void runBefore_interleavesAStatementOnce() {
            jdbcTemplate.update("INSERT INTO " + PROBE + " (v) VALUES ('a'), ('b')");
            Fault fault = faults.runBefore(PROBE, Operation.DELETE,
                    "INSERT INTO " + PROBE + " (v) VALUES ('interleaved')");

            jdbcTemplate.update("DELETE FROM " + PROBE + " WHERE v IN ('a', 'b')");

            assertEquals(List.of("interleaved"), jdbcTemplate.queryForList("SELECT v FROM " + PROBE, String.class),
                    "the statement ran once, before the first deleted row, and its row is not deleted");
            assertEquals(2, fault.hits(), "both deleted rows crossed the fault point");
        }

        @Test
        void failOn_unknownTable_isRejected() {
            assertThrows(IllegalArgumentException.class, () -> faults.failOn("p00_no_such_table", Operation.INSERT));
            assertThrows(IllegalArgumentException.class, () -> faults.failOn("request; DROP TABLE x", Operation.INSERT));
        }

        @Test
        void removeAll_leavesNoTrigger() {
            faults.failOn(PROBE, Operation.INSERT);
            faults.failAtCommit(PROBE, Operation.UPDATE);

            faults.removeAll();

            assertEquals(1, jdbcTemplate.update("INSERT INTO " + PROBE + " (v) VALUES ('ok')"));
            assertEquals(0, count("SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'p00\\_fault\\_%'"));
        }
    }
}
