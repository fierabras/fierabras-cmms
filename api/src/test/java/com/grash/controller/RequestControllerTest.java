package com.grash.controller;

import com.grash.factory.MailServiceFactory;
import com.grash.mapper.RequestMapper;
import com.grash.mapper.WorkOrderMapper;
import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.UserSettings;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleType;
import com.grash.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.HashSet;

import static com.grash.utils.Helper.setCurrentUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-INC06-02 A3: regression for {@code GET /requests/pending}, which keeps requiring view REQUESTS.
 */
@WebMvcTest(RequestController.class)
@AutoConfigureMockMvc(addFilters = false)
class RequestControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RequestService requestService;
    @MockBean
    private UserService userService;
    @MockBean
    private WorkOrderMapper workOrderMapper;
    @MockBean
    private RequestMapper requestMapper;
    @MockBean
    private NotificationService notificationService;
    @MockBean
    private WorkflowService workflowService;
    @MockBean
    private MailServiceFactory mailServiceFactory;
    @MockBean
    private AssetService assetService;
    @MockBean
    private RequestPortalService requestPortalService;
    @MockBean
    private WebhookDispatchService webhookDispatchService;

    @Test
    void getPendingCount_withViewRequests_success() throws Exception {
        User user = clientUser(PermissionEntity.REQUESTS);
        setCurrentUser(user);
        when(userService.whoami(any())).thenReturn(user);
        when(requestService.countPending(user)).thenReturn(4);

        mockMvc.perform(get("/requests/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("4"));
    }

    @Test
    void getPendingCount_withoutViewRequests_forbidden() throws Exception {
        User user = clientUser(PermissionEntity.WORK_ORDERS);
        setCurrentUser(user);
        when(userService.whoami(any())).thenReturn(user);

        mockMvc.perform(get("/requests/pending"))
                .andExpect(status().isForbidden());
        verify(requestService, never()).countPending(any());
    }

    private static User clientUser(PermissionEntity viewPermission) {
        Role role = Role.builder()
                .id(1L)
                .roleType(RoleType.ROLE_CLIENT)
                .name("Client Role")
                .viewPermissions(new HashSet<>(Collections.singletonList(viewPermission)))
                .build();
        User user = new User();
        user.setId(1L);
        user.setFirstName("John");
        user.setLastName("Doe");
        user.setEmail("john@test.com");
        user.setRole(role);
        user.setEnabled(true);
        user.setUserSettings(new UserSettings());
        return user;
    }
}
