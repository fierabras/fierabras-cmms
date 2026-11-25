package com.grash.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.integration.support.TestApi;
import com.grash.integration.support.TestApi.Response;
import com.grash.integration.support.TestAssetRecords;
import com.grash.integration.support.TestOrganizations;
import com.grash.integration.support.TestOrganizations.CommonData;
import com.grash.integration.support.TestRecords;
import com.grash.model.Company;
import com.grash.model.Request;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.enums.Priority;
import com.grash.model.enums.workflow.RequestAction;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.repository.WorkflowRepository;
import com.grash.service.WorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * P-18 (D-P18-5, TD-P13-01, PPB-15): the "request rejected" automations run while the request is still pending, as
 * the "request approved" ones do, so a closed request is not changed after it is closed. The final row is the same in
 * both orders; what changes is the state the automation sees, so the automation service is a spy (its own context).
 */
@AutoConfigureMockMvc
class RequestRejectedAutomationIntegrationTest extends AbstractIntegrationTest {

    @SpyBean
    private WorkflowService workflowService;

    private final TestOrganizations organizations;
    private final TestAssetRecords assetRecords;
    private final TestRecords records;
    private final TestApi api;
    private final JdbcTemplate jdbc;
    private final WorkflowRepository workflowRepository;
    private final List<CommonData> created = new ArrayList<>();

    @Autowired
    RequestRejectedAutomationIntegrationTest(TestOrganizations organizations, TestAssetRecords assetRecords,
                                             TestRecords records, MockMvc mockMvc, ObjectMapper objectMapper,
                                             JdbcTemplate jdbc, WorkflowRepository workflowRepository) {
        this.organizations = organizations;
        this.assetRecords = assetRecords;
        this.records = records;
        this.api = new TestApi(mockMvc, objectMapper);
        this.jdbc = jdbc;
        this.workflowRepository = workflowRepository;
    }

    @AfterEach
    void cleanUp() {
        created.forEach(records::deleteRequestPortals);
        created.forEach(organizations::delete);
        created.clear();
    }

    @Test
    void dP18_5_requestRejectedAutomation_runsWhileTheRequestIsPending_thenTheRequestIsClosed() {
        // before P-18: the automation ran on the request already marked as rejected
        CommonData data = organizations.create();
        created.add(data);
        Company company = data.o1().company();
        Request request = assetRecords.request(company);
        WorkflowAction action = new WorkflowAction();
        action.setCompany(company);
        action.setRequestAction(RequestAction.ASSIGN_PRIORITY);
        action.setPriority(Priority.HIGH);
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-18 workflow"));
        workflow.setMainCondition(WFMainCondition.REQUEST_REJECTED);
        workflow.setAction(action);
        workflowRepository.saveAndFlush(workflow);
        List<Boolean> cancelledWhenRun = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            cancelledWhenRun.add(((Request) invocation.getArgument(1)).isCancelled());
            return invocation.callRealMethod();
        }).when(workflowService).runRequest(any(), any());

        Response response = api.call(patch("/requests/" + request.getId() + "/cancel").param("reason", "P-18")
                .contentType(MediaType.APPLICATION_JSON), api.login(data.o1().admin()));

        assertEquals(200, response.status(), response.toString());
        assertEquals(List.of(false), cancelledWhenRun, "the automation saw the request still pending");
        Map<String, Object> after = jdbc.queryForMap("SELECT cancelled, cancellation_reason, priority FROM request "
                + "WHERE id = ?", request.getId());
        assertEquals(List.of(true, "P-18", Priority.HIGH.ordinal()), List.of(after.get("cancelled"),
                after.get("cancellation_reason"), ((Number) after.get("priority")).intValue()), after.toString());
    }
}
