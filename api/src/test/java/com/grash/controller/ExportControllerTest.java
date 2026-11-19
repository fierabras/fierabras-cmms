package com.grash.controller;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.model.Company;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.UserSettings;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleType;
import com.grash.service.AsyncExportService;
import com.grash.service.UserService;
import com.grash.service.WorkOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static com.grash.utils.Helper.setCurrentUser;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D-03: whoever can see a list can export it, limited to what the list shows them.
 */
@WebMvcTest(ExportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExportControllerTest extends AbstractControllerTest {

    private static final String CRITERIA = "{\"filterFields\":[],\"pageNum\":3,\"pageSize\":10,"
            + "\"sortField\":\"title\",\"direction\":\"DESC\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;
    @MockBean
    private AsyncExportService asyncExportService;
    @MockBean
    private WorkOrderService workOrderService;

    private User user(Set<PermissionEntity> view, Set<PermissionEntity> viewOther) {
        Role role = Role.builder().id(1L).roleType(RoleType.ROLE_CLIENT).name("Role")
                .viewPermissions(new HashSet<>(view)).viewOtherPermissions(new HashSet<>(viewOther))
                .createPermissions(new HashSet<>()).editOtherPermissions(new HashSet<>())
                .deleteOtherPermissions(new HashSet<>()).build();
        User user = new User();
        user.setId(7L);
        user.setEmail("export@test.com");
        user.setRole(role);
        user.setEnabled(true);
        user.setCompany(new Company());
        user.setUserSettings(new UserSettings());
        setCurrentUser(user);
        when(userService.whoami(any())).thenReturn(user);
        return user;
    }

    // T-05
    @Test
    void workOrders_withoutViewPermission_isForbidden() throws Exception {
        user(Set.of(), Set.of());

        mockMvc.perform(post("/export/work-orders").param("uuid", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(CRITERIA))
                .andExpect(status().isForbidden());

        verifyNoInteractions(asyncExportService);
    }

    // D-03 / D-08
    @Test
    void workOrders_withViewPermission_exportsTheScopeOfTheList() throws Exception {
        User user = user(Set.of(PermissionEntity.WORK_ORDERS), Set.of());
        SearchCriteria scoped = new SearchCriteria();
        when(workOrderService.getSearchCriteria(eq(user), any(SearchCriteria.class))).thenReturn(scoped);

        mockMvc.perform(post("/export/work-orders").param("uuid", "u1")
                        .contentType(MediaType.APPLICATION_JSON).content(CRITERIA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("u1"));

        verify(workOrderService).getSearchCriteria(eq(user), argThat(criteria ->
                "title".equals(criteria.getSortField())));
        verify(asyncExportService).exportWorkOrders(user, scoped, "u1");
    }

    @Test
    void workOrders_getIsNoLongerExposed() throws Exception {
        user(Set.of(PermissionEntity.WORK_ORDERS), Set.of(PermissionEntity.WORK_ORDERS));

        mockMvc.perform(get("/export/work-orders").param("uuid", "u1"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(asyncExportService);
    }

    // §4.2: costs-times keeps WorkOrder.canViewAll
    @Test
    void costsAndTimes_stillRequiresViewOther() throws Exception {
        user(Set.of(PermissionEntity.WORK_ORDERS), Set.of());

        mockMvc.perform(get("/export/costs-times").param("uuid", "u1"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(asyncExportService);
    }
}
