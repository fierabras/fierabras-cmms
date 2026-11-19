package com.grash.utils;

import com.grash.model.Role;
import com.grash.model.User;
import com.grash.model.WorkOrder;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * INC-02 (D-27): planning and work order exports now take {@link WorkOrder#canViewAll} (view and view other work
 * orders) instead of view other work orders alone. The default roles are rebuilt from
 * {@link Helper#getDefaultRoles()} at startup (RoleService.updateDefaultRoles), so these roles are the ones every
 * company has: none of them may gain or lose access to planning or exports with the new rule.
 */
class HelperDefaultRolesAccessTest {

    // Rules of fierabras-main before INC-02 R-05 (ExportController and WorkloadController.checkAccess)
    private static final Predicate<User> MAIN_EXPORT =
            user -> user.getRole().getViewOtherPermissions().contains(PermissionEntity.WORK_ORDERS);
    private static final Predicate<User> MAIN_PLANNING = user -> MAIN_EXPORT.test(user)
            && user.getRole().getEditOtherPermissions().contains(PermissionEntity.PEOPLE_AND_TEAMS);

    // Rules of this branch
    private static final Predicate<User> EXPORT = WorkOrder::canViewAll;
    private static final Predicate<User> PLANNING = user -> WorkOrder.canViewAll(user)
            && user.getRole().getEditOtherPermissions().contains(PermissionEntity.PEOPLE_AND_TEAMS);

    @Test
    @DisplayName("INC-02 D-27: no default role changes its access to work order exports")
    void exports_sameAccessForEveryDefaultRole() {
        Map<RoleCode, Boolean> expected = Map.of(
                RoleCode.ADMIN, true,
                RoleCode.LIMITED_ADMIN, true,
                RoleCode.TECHNICIAN, true,
                RoleCode.LIMITED_TECHNICIAN, false,
                RoleCode.VIEW_ONLY, true,
                RoleCode.REQUESTER, false);

        assertAll(
                () -> assertEquals(expected, accessByRole(MAIN_EXPORT), "fierabras-main"),
                () -> assertEquals(expected, accessByRole(EXPORT), "INC-02"));
    }

    @Test
    @DisplayName("INC-02 D-27: no default role changes its access to resource planning")
    void planning_sameAccessForEveryDefaultRole() {
        Map<RoleCode, Boolean> expected = Map.of(
                RoleCode.ADMIN, true,
                RoleCode.LIMITED_ADMIN, false,
                RoleCode.TECHNICIAN, false,
                RoleCode.LIMITED_TECHNICIAN, false,
                RoleCode.VIEW_ONLY, false,
                RoleCode.REQUESTER, false);

        assertAll(
                () -> assertEquals(expected, accessByRole(MAIN_PLANNING), "fierabras-main"),
                () -> assertEquals(expected, accessByRole(PLANNING), "INC-02"));
    }

    @Test
    @DisplayName("INC-02 D-27: the six default roles are the ones covered")
    void defaultRoles_areTheSixCovered() {
        assertEquals(EnumSet.of(RoleCode.ADMIN, RoleCode.LIMITED_ADMIN, RoleCode.TECHNICIAN,
                        RoleCode.LIMITED_TECHNICIAN, RoleCode.VIEW_ONLY, RoleCode.REQUESTER),
                Helper.getDefaultRoles().stream().map(Role::getCode)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(RoleCode.class))));
    }

    private static Map<RoleCode, Boolean> accessByRole(Predicate<User> rule) {
        return Helper.getDefaultRoles().stream().collect(Collectors.toMap(Role::getCode, role -> {
            User user = new User();
            user.setRole(role);
            return rule.test(user);
        }));
    }
}
