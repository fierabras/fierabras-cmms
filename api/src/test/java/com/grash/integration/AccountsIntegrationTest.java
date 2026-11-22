package com.grash.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.FaultInjector;
import com.grash.integration.support.FaultInjector.Operation;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestOrganizations.O1;
import com.grash.integration.support.TestRecords;
import com.grash.integration.support.TestWorkOrders;
import com.grash.model.Asset;
import com.grash.model.Comment;
import com.grash.model.CustomField;
import com.grash.model.Labor;
import com.grash.model.Location;
import com.grash.model.Team;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.CustomFieldEntityType;
import com.grash.model.enums.CustomFieldType;
import com.grash.model.enums.Status;
import com.grash.repository.CommentRepository;
import com.grash.repository.CustomFieldRepository;
import com.grash.repository.LaborRepository;
import com.grash.repository.TeamRepository;
import com.grash.repository.UserRepository;
import com.grash.repository.WorkOrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * P-11: accounts and users (PPB-08, PPB-09, PPB-10 to PPB-13, PPB-23). The owner and the organization are never
 * deleted; "delete my account" disables; retiring and restoring users is recorded; a retired user is not accepted
 * as a new reference and keeps being shown by name; a user cited by other records is not deleted.
 */
@AutoConfigureMockMvc
class AccountsIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER_ACCOUNT = "OWNER_ACCOUNT";

    private final TestOrganizations organizations;
    private final TestRecords records;
    private final TestApi api;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final WorkOrderRepository workOrderRepository;
    private final TeamRepository teamRepository;
    private final CommentRepository commentRepository;
    private final LaborRepository laborRepository;
    private final CustomFieldRepository customFieldRepository;
    private final FaultInjector faults;
    private final List<CommonData> created = new ArrayList<>();
    private final List<User> createdUsers = new ArrayList<>();

    @Autowired
    AccountsIntegrationTest(TestOrganizations organizations, TestRecords records, MockMvc mockMvc,
                            ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
                            UserRepository userRepository,
                            WorkOrderRepository workOrderRepository, TeamRepository teamRepository,
                            CommentRepository commentRepository, LaborRepository laborRepository,
                            CustomFieldRepository customFieldRepository) {
        this.organizations = organizations;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.workOrderRepository = workOrderRepository;
        this.teamRepository = teamRepository;
        this.commentRepository = commentRepository;
        this.laborRepository = laborRepository;
        this.customFieldRepository = customFieldRepository;
        this.faults = new FaultInjector(jdbcTemplate);
    }

    @AfterEach
    void cleanUp() {
        faults.removeAll();
        createdUsers.forEach(organizations::deleteUser);
        createdUsers.clear();
        created.forEach(organizations::delete);
        created.clear();
    }

    private CommonData newData() {
        CommonData data = organizations.create();
        created.add(data);
        return data;
    }

    private O1 newO1() {
        return newData().o1();
    }

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("not JSON: " + response, e);
        }
    }

    private JsonNode assertConflict(Response response, String code) {
        JsonNode body = json(response);
        assertAll(
                () -> assertEquals(409, response.status(), response.toString()),
                () -> assertFalse(body.get("success").asBoolean(), response.toString()),
                () -> assertEquals(code, body.get("code").asText(), response.toString()),
                () -> assertFalse(body.get("message").asText().isBlank(), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("constraint"), response.toString()),
                () -> assertFalse(response.body().toLowerCase().contains("sql"), response.toString()));
        return body;
    }

    private List<String> blockingTypes(JsonNode body) {
        List<String> types = new ArrayList<>();
        if (body.has("blockingTypes")) body.get("blockingTypes").forEach(type -> types.add(type.asText()));
        return types;
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /**
     * The audit records of the organization, oldest first, as {@code ACTION TYPE entityId actorId}.
     */
    private List<String> audit(Long companyId) {
        return jdbcTemplate.queryForList("SELECT action || ' ' || entity_type || ' ' || entity_id || ' ' || "
                + "coalesce(actor_id::text, '-') FROM audit_log WHERE company_id = ? ORDER BY id", String.class,
                companyId);
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    /**
     * Number of rows of every table of the schema.
     */
    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = current_schema() "
                + "ORDER BY tablename", String.class).forEach(table ->
                counts.put(table, count("SELECT count(*) FROM \"" + table + "\"")));
        return counts;
    }

    private Response signIn(User user, String type) {
        return api.call(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + TestOrganizations.PASSWORD
                        + "\",\"type\":\"" + type + "\"}"), null);
    }

    // =====================================================================================================
    // Fixtures
    // =====================================================================================================

    private String superAdminToken() {
        User superAdmin = organizations.superAdmin();
        createdUsers.add(superAdmin);
        return api.login(superAdmin, "SUPER_ADMIN");
    }

    /**
     * A work order closed by {@code user}, with a labor assigned to and a comment written by that user.
     */
    private WorkOrder closedWorkOrderWithRecordsOf(User user) {
        WorkOrder workOrder = records.workOrder(user.getCompany(), null, Status.COMPLETE, false);
        workOrder.setCompletedBy(user);
        workOrder.setCompletedOn(new Date());
        WorkOrder saved = workOrderRepository.saveAndFlush(workOrder);
        labor(saved, user);
        comment(saved, user);
        return saved;
    }

    private Labor labor(WorkOrder workOrder, User user) {
        Labor labor = new Labor();
        labor.setWorkOrder(workOrder);
        labor.setAssignedTo(user);
        labor.setStartedAt(new Date());
        labor.setCompany(workOrder.getCompany());
        return laborRepository.saveAndFlush(labor);
    }

    private Comment comment(WorkOrder workOrder, User user) {
        Comment comment = new Comment();
        comment.setWorkOrder(workOrder);
        comment.setUser(user);
        comment.setContent("P-11 comment");
        comment.setCompany(workOrder.getCompany());
        return commentRepository.saveAndFlush(comment);
    }

    private WorkOrder assignedWorkOrder(User user, Status status, boolean archived) {
        WorkOrder workOrder = records.workOrder(user.getCompany(), null, status, false);
        workOrder.setAssignedTo(new ArrayList<>(List.of(user)));
        workOrder.setArchived(archived);
        return workOrderRepository.saveAndFlush(workOrder);
    }

    private Team team(O1 o1, User... members) {
        Team team = new Team();
        team.setName(TestRecords.unique("P-11 team"));
        team.setCompany(o1.company());
        team.setUsers(new ArrayList<>(List.of(members)));
        return teamRepository.saveAndFlush(team);
    }

    private void disable(O1 o1, User user) {
        Response response = api.call(patch("/users/" + user.getId() + "/disable"), api.login(o1.admin()));
        assertEquals(200, response.status(), "disable: " + response);
    }

    private List<Long> assignedIds(WorkOrder workOrder) {
        return jdbcTemplate.queryForList("SELECT assigned_to_id FROM work_order_assigned_to WHERE work_order_id = ? "
                + "ORDER BY assigned_to_id", Long.class, workOrder.getId());
    }

    private Long primaryUserId(WorkOrder workOrder) {
        return jdbcTemplate.queryForObject("SELECT primary_user_id FROM work_order WHERE id = ?", Long.class,
                workOrder.getId());
    }

    // =====================================================================================================
    // E-1: the owner's account (PPB-10, PPB-12)
    // =====================================================================================================

    @Nested
    class OwnerAccount {

        @Test
        void ca029_ownerDeletesOwnAccount_409OwnerAccount_noRowChanged_ownerEnabled() {
            O1 o1 = newO1();
            Asset asset = records.asset(o1.company());
            records.downtime(asset);
            Location location = records.location(o1.company());
            records.part(o1.company(), o1.admin());
            WorkOrder workOrder = closedWorkOrderWithRecordsOf(o1.technician());
            team(o1, o1.technician());
            CustomField field = new CustomField();
            field.setLabel(TestRecords.unique("P-11 field"));
            field.setFieldType(CustomFieldType.SHORT_TEXT);
            field.setEntityType(CustomFieldEntityType.ASSET);
            field.setCompanySettings(o1.company().getCompanySettings());
            CustomField savedField = customFieldRepository.saveAndFlush(field);
            jdbcTemplate.update("INSERT INTO custom_field_value (custom_field_id, asset_id, value) "
                    + "VALUES (?, ?, 'P-11')", savedField.getId(), asset.getId());
            String token = api.login(o1.owner());
            Map<String, Long> before = rowCounts();

            Response response = api.call(delete("/auth"), token);

            assertConflict(response, OWNER_ACCOUNT);
            assertEquals(before, rowCounts(), "no row of any table is created or deleted");
            assertTrue(reload(o1.owner()).isEnabled());
            assertTrue(workOrderRepository.existsById(workOrder.getId()));
            assertNotNull(location.getId());
            assertTrue(audit(o1.company().getId()).isEmpty());
        }

        @Test
        void ca033a_ownerSoftDeletesSelf_409OwnerAccount_emailKept() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/soft-delete/" + o1.owner().getId()), api.login(o1.owner()));

            assertConflict(response, OWNER_ACCOUNT);
            User owner = reload(o1.owner());
            assertTrue(owner.isEnabled());
            assertEquals(o1.owner().getEmail(), owner.getEmail());
            assertTrue(audit(o1.company().getId()).isEmpty());
        }

        @Test
        void ca033_ownerDisablesSelf_409OwnerAccount() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.owner().getId() + "/disable"), api.login(o1.owner()));

            assertConflict(response, OWNER_ACCOUNT);
            assertTrue(reload(o1.owner()).isEnabled());
        }

        @Test
        void ca033b_adminDisablesOwner_current403_ownerUnchanged() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.owner().getId() + "/disable"), api.login(o1.admin()));

            assertEquals(403, response.status(), response.toString());
            User owner = reload(o1.owner());
            assertTrue(owner.isEnabled());
            assertEquals(o1.owner().getEmail(), owner.getEmail());
        }
    }

    // =====================================================================================================
    // E-2: "delete my account" disables (PPB-11)
    // =====================================================================================================

    @Nested
    class DeleteOwnAccount {

        @Test
        void ca031_technicianDeletesOwnAccount_200_disabled_recordsKept_retireRecorded() {
            O1 o1 = newO1();
            WorkOrder workOrder = records.workOrder(o1.company(), null, Status.OPEN, false);
            Labor labor = labor(workOrder, o1.technician());
            Comment comment = comment(workOrder, o1.technician());

            Response response = api.call(delete("/auth"), api.login(o1.technician()));

            assertEquals(200, response.status(), response.toString());
            User technician = reload(o1.technician());
            assertFalse(technician.isEnabled());
            assertEquals(o1.technician().getEmail(), technician.getEmail(), "disabled, not given a suffix");
            assertTrue(laborRepository.existsById(labor.getId()));
            assertTrue(commentRepository.existsById(comment.getId()));
            assertEquals(List.of("RETIRE USER " + o1.technician().getId() + " " + o1.technician().getId()),
                    audit(o1.company().getId()));
        }

        @Test
        void ca032_disabledTechnician_previousTokenAnswers401_signInGetsNoToken() {
            O1 o1 = newO1();
            String token = api.login(o1.technician());
            assertEquals(200, api.call(get("/users/mini"), token).status(), "the user is now in the cache");
            disable(o1, o1.technician());

            Response withPreviousToken = api.call(get("/users/mini"), token);
            Response signIn = signIn(o1.technician(), "CLIENT");

            assertEquals(401, withPreviousToken.status(), withPreviousToken.toString());
            assertEquals(403, signIn.status(), "current answer of a rejected sign in (D-P11-2): " + signIn);
            assertFalse(signIn.body().contains("accessToken"), signIn.toString());
        }

        @Test
        void ca032_ownAccountDeleted_previousTokenAnswers401() {
            O1 o1 = newO1();
            String token = api.login(o1.technician());
            assertEquals(200, api.call(delete("/auth"), token).status());

            Response withPreviousToken = api.call(get("/users/mini"), token);

            assertEquals(401, withPreviousToken.status(), withPreviousToken.toString());
        }

        @Test
        void softDeletedTechnician_previousTokenAnswers401_not500() {
            O1 o1 = newO1();
            String token = api.login(o1.technician());
            assertEquals(200, api.call(get("/users/mini"), token).status(), "the user is now in the cache");
            Response softDelete = api.call(patch("/users/soft-delete/" + o1.technician().getId()),
                    api.login(o1.admin()));
            assertEquals(200, softDelete.status(), softDelete.toString());

            Response withPreviousToken = api.call(get("/users/mini"), token);

            assertEquals(401, withPreviousToken.status(), withPreviousToken.toString());
        }
    }

    // =====================================================================================================
    // E-3: retiring and restoring users is recorded (PPB-23)
    // =====================================================================================================

    @Nested
    class RetireAndRestore {

        @Test
        void ca034_adminDisablesThenEnablesTechnician_200_retireAndRestoreRecorded() {
            O1 o1 = newO1();
            String token = api.login(o1.admin());
            Long id = o1.technician().getId();

            Response disabled = api.call(patch("/users/" + id + "/disable"), token);
            Response enabled = api.call(patch("/users/" + id + "/enable"), token);

            assertEquals(200, disabled.status(), disabled.toString());
            assertEquals(200, enabled.status(), enabled.toString());
            assertTrue(reload(o1.technician()).isEnabled());
            String actor = String.valueOf(o1.admin().getId());
            assertEquals(List.of("RETIRE USER " + id + " " + actor, "RESTORE USER " + id + " " + actor),
                    audit(o1.company().getId()));
            assertEquals("P-00 T", jdbcTemplate.queryForObject("SELECT entity_name FROM audit_log WHERE "
                    + "company_id = ? ORDER BY id LIMIT 1", String.class, o1.company().getId()));
        }

        @Test
        void softDelete_retireRecorded() {
            O1 o1 = newO1();
            Long id = o1.technician().getId();

            Response response = api.call(patch("/users/soft-delete/" + id), api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(List.of("RETIRE USER " + id + " " + o1.admin().getId()), audit(o1.company().getId()));
        }

        @Test
        void disablingADisabledUser_200_noSecondRecord() {
            O1 o1 = newO1();
            disable(o1, o1.technician());

            Response response = api.call(patch("/users/" + o1.technician().getId() + "/disable"),
                    api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(1, audit(o1.company().getId()).size());
        }

        @Test
        void rejectedDisable_noRecord() {
            O1 o1 = newO1();

            Response response = api.call(patch("/users/" + o1.admin().getId() + "/disable"),
                    api.login(o1.technician()));

            assertEquals(406, response.status(), response.toString());
            assertTrue(audit(o1.company().getId()).isEmpty());
        }
    }

    // =====================================================================================================
    // E-4: a retired user (PPB-08, PPB-09, PPB-13)
    // =====================================================================================================

    @Nested
    class RetiredUser {

        @Test
        void ca080_retiredUserAssignedToWorkOrder_409UserRetired_workOrderUnchanged() {
            O1 o1 = newO1();
            WorkOrder workOrder = records.workOrder(o1.company(), null, Status.OPEN, false);
            disable(o1, o1.technician());
            String token = api.login(o1.admin());
            // P-12c (H1-08): the whole work order and its associations, and the audit log, after each attempt
            Map<String, Object> before = TestWorkOrders.rows(jdbcTemplate, workOrder.getId(), null);
            List<String> records = audit(o1.company().getId());

            Response asAssigned = api.call(patch("/work-orders/" + workOrder.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + workOrder.getTitle()
                            + "\",\"assignedTo\":[{\"id\":" + o1.technician().getId() + "}]}"), token);
            assertConflict(asAssigned, "USER_RETIRED");
            assertEquals(before, TestWorkOrders.rows(jdbcTemplate, workOrder.getId(), null));
            Response asPrimary = api.call(patch("/work-orders/" + workOrder.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + workOrder.getTitle()
                            + "\",\"primaryUser\":{\"id\":" + o1.technician().getId() + "}}"), token);
            assertConflict(asPrimary, "USER_RETIRED");

            assertEquals(before, TestWorkOrders.rows(jdbcTemplate, workOrder.getId(), null));
            assertEquals(records, audit(o1.company().getId()));
            assertEquals(List.of(), assignedIds(workOrder));
            assertNull(primaryUserId(workOrder));
        }

        @Test
        void ca080_newWorkOrderWithRetiredUser_409UserRetired_noWorkOrderCreated() {
            O1 o1 = newO1();
            disable(o1, o1.technician());
            long before = count("SELECT count(*) FROM work_order WHERE company_id = ?", o1.company().getId());
            List<String> records = audit(o1.company().getId());

            Response response = api.call(post("/work-orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"P-11 WO\",\"assignedTo\":[{\"id\":" + o1.technician().getId() + "}]}"),
                    api.login(o1.admin()));

            assertConflict(response, "USER_RETIRED");
            assertEquals(before, count("SELECT count(*) FROM work_order WHERE company_id = ?",
                    o1.company().getId()));
            assertEquals(records, audit(o1.company().getId()));
        }

        @Test
        void retiredUserAlreadyAssigned_isKept_workOrderStillEditable() {
            O1 o1 = newO1();
            WorkOrder workOrder = assignedWorkOrder(o1.technician(), Status.OPEN, false);
            disable(o1, o1.technician());

            Response response = api.call(patch("/work-orders/" + workOrder.getId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"P-11 edited\",\"assignedTo\":"
                            + "[{\"id\":" + o1.technician().getId() + "},{\"id\":" + o1.admin().getId() + "}]}"),
                    api.login(o1.admin()));

            assertEquals(200, response.status(), response.toString());
            assertEquals(List.of(o1.admin().getId(), o1.technician().getId()).stream().sorted().toList(),
                    assignedIds(workOrder));
        }

        @Test
        void retiredUserAddedToTeam_409UserRetired_onCreateAndOnEdit() {
            O1 o1 = newO1();
            Team team = team(o1, o1.admin());
            disable(o1, o1.technician());
            String token = api.login(o1.admin());
            long teams = count("SELECT count(*) FROM team WHERE company_id = ?", o1.company().getId());

            Response created = api.call(post("/teams").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"P-11 team\",\"users\":[{\"id\":" + o1.technician().getId() + "}]}"), token);
            Response edited = api.call(patch("/teams/" + team.getId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + team.getName() + "\",\"users\":[{\"id\":" + o1.admin().getId()
                            + "},{\"id\":" + o1.technician().getId() + "}]}"), token);

            assertConflict(created, "USER_RETIRED");
            assertConflict(edited, "USER_RETIRED");
            assertEquals(teams, count("SELECT count(*) FROM team WHERE company_id = ?", o1.company().getId()));
            assertEquals(List.of(o1.admin().getId()), jdbcTemplate.queryForList("SELECT id_user FROM "
                    + "t_team_user_associations WHERE id_team = ?", Long.class, team.getId()));
        }

        @Test
        void ca131_retiredUser_notInSelectors_closedWorkOrderShowsHimByName() {
            O1 o1 = newO1();
            WorkOrder workOrder = closedWorkOrderWithRecordsOf(o1.technician());
            disable(o1, o1.technician());
            String token = api.login(o1.admin());

            Response workers = api.call(get("/users/mini"), token);
            Response withRequesters = api.call(get("/users/mini?withRequesters=true"), token);
            Response detail = api.call(get("/work-orders/" + workOrder.getId()), token);

            String id = "\"id\":" + o1.technician().getId() + ",";
            assertEquals(200, workers.status(), workers.toString());
            assertFalse(workers.body().contains(id), workers.body());
            assertFalse(withRequesters.body().contains(id), withRequesters.body());
            JsonNode completedBy = json(detail).get("completedBy");
            assertEquals(o1.technician().getId(), completedBy.get("id").asLong());
            assertEquals("P-00", completedBy.get("firstName").asText());
            assertEquals("T", completedBy.get("lastName").asText());
        }

        @Test
        void ca035_softDeletedTechnician_historyShowsNameNotEmail_emailSuffixed() {
            O1 o1 = newO1();
            WorkOrder workOrder = records.workOrder(o1.company(), null, Status.COMPLETE, false);
            workOrder.setCompletedBy(o1.technician());
            workOrderRepository.saveAndFlush(workOrder);
            labor(workOrder, o1.technician());
            Comment comment = comment(workOrder, o1.technician());
            String token = api.login(o1.admin());
            assertEquals(200, api.call(patch("/users/soft-delete/" + o1.technician().getId()), token).status());

            JsonNode detail = json(api.call(get("/work-orders/" + workOrder.getId()), token));
            JsonNode labors = json(api.call(get("/labors/work-order/" + workOrder.getId()), token));
            JsonNode commentShown = json(api.call(get("/comments/" + comment.getId()), token));

            for (JsonNode shown : List.of(detail.get("completedBy"), labors.get(0).get("assignedTo"),
                    commentShown.get("user"))) {
                assertEquals("P-00", shown.get("firstName").asText(), shown.toString());
                assertEquals("T", shown.get("lastName").asText(), shown.toString());
            }
            assertFalse(detail.get("completedBy").has("email"), "the work order shows a name, not an email");
            assertFalse(commentShown.get("user").has("email"), "the comment shows a name, not an email");
            assertEquals(o1.technician().getEmail() + "_" + o1.technician().getId(),
                    reload(o1.technician()).getEmail());
        }
    }

    // =====================================================================================================
    // E-5: a user with records is not deleted (PPB-02, PPB-12)
    // =====================================================================================================

    @Nested
    class SuperAdminDeletion {

        @Test
        void ca036_userWithRecords_409UserInUse_withTypes_userKept_noRecord() {
            O1 o1 = newO1();
            closedWorkOrderWithRecordsOf(o1.technician());

            Response response = api.call(delete("/auth/" + o1.technician().getUsername()), superAdminToken());

            List<String> types = blockingTypes(assertConflict(response, "USER_IN_USE"));
            assertTrue(types.containsAll(List.of("COMMENT", "LABOR", "WORK_ORDER")), types.toString());
            assertTrue(userRepository.existsById(o1.technician().getId()));
            assertTrue(audit(o1.company().getId()).isEmpty());
        }

        @Test
        void userAssignedToClosedWorkOrder_409UserInUse_workOrderType() {
            O1 o1 = newO1();
            assignedWorkOrder(o1.technician(), Status.COMPLETE, false);

            Response response = api.call(delete("/auth/" + o1.technician().getUsername()), superAdminToken());

            assertEquals(List.of("WORK_ORDER"), blockingTypes(assertConflict(response, "USER_IN_USE")));
            assertTrue(userRepository.existsById(o1.technician().getId()));
        }

        @Test
        void userAssignedToArchivedWorkOrder_409UserInUse_workOrderType() {
            O1 o1 = newO1();
            assignedWorkOrder(o1.technician(), Status.OPEN, true);

            Response response = api.call(delete("/auth/" + o1.technician().getUsername()), superAdminToken());

            assertEquals(List.of("WORK_ORDER"), blockingTypes(assertConflict(response, "USER_IN_USE")));
        }

        @Test
        void userAssignedOnlyToOpenWorkOrder_200_deleted_deleteRecordedWithCascade() {
            O1 o1 = newO1();
            WorkOrder workOrder = assignedWorkOrder(o1.technician(), Status.OPEN, false);
            String token = superAdminToken();

            Response response = api.call(delete("/auth/" + o1.technician().getUsername()), token);

            assertEquals(200, response.status(), response.toString());
            assertEquals(o1.technician().getUsername(), response.body());
            assertFalse(userRepository.existsById(o1.technician().getId()));
            assertEquals(List.of(), assignedIds(workOrder));
            List<String> audit = audit(o1.company().getId());
            assertEquals(1, audit.size(), audit.toString());
            assertTrue(audit.get(0).startsWith("DELETE USER " + o1.technician().getId() + " "), audit.toString());
            String cascade = jdbcTemplate.queryForObject("SELECT cascade_rows::text FROM audit_log WHERE "
                    + "company_id = ?", String.class, o1.company().getId());
            assertTrue(cascade.contains("\"work_order_assigned_to\": 1"), cascade);
        }

        @Test
        void ownerWithoutRecords_409OwnerAccount() {
            O1 o1 = newO1();

            Response response = api.call(delete("/auth/" + o1.owner().getUsername()), superAdminToken());

            assertConflict(response, OWNER_ACCOUNT);
            assertTrue(userRepository.existsById(o1.owner().getId()));
        }

        @Test
        void recordAddedBetweenCheckAndDeletion_409UserInUse_never500() {
            O1 o1 = newO1();
            WorkOrder workOrder = records.workOrder(o1.company(), null, Status.OPEN, false);
            String token = superAdminToken();

            try (FaultInjector.Fault fault = faults.runBefore("own_user", Operation.DELETE, "UPDATE work_order SET "
                    + "primary_user_id = " + o1.technician().getId() + " WHERE id = " + workOrder.getId())) {
                Response response = api.call(delete("/auth/" + o1.technician().getUsername()), token);

                assertEquals(List.of("WORK_ORDER"), blockingTypes(assertConflict(response, "USER_IN_USE")));
                assertEquals(1, fault.hits());
            }
            assertTrue(userRepository.existsById(o1.technician().getId()));
            assertNull(primaryUserId(workOrder), "the change made inside the rejected deletion is rolled back");
        }
    }
}
