package com.grash.controller;

import com.grash.dto.RolePatchDTO;
import com.grash.dto.SuccessResponse;
import com.grash.exception.CustomException;
import com.grash.model.User;
import com.grash.model.Role;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.service.RoleService;
import com.grash.service.UserService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/roles")
@Tag(name = "Roles", description = "Operations on roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final UserService userService;

    @GetMapping("")
    @PreAuthorize("permitAll()")
    public Collection<Role> getAll(HttpServletRequest req) {
        User user = userService.whoami(req);
        if (user.getRole().getRoleType().equals(RoleType.ROLE_CLIENT)) {
            if (user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS)) {
                return roleService.findByCompany(user.getCompany().getId());
            } else throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        } else return roleService.getAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("permitAll()")
    public Role getById(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        Optional<Role> optionalRole = roleService.findById(id);
        if (optionalRole.isPresent()) {
            Role savedRole = optionalRole.get();
            if (user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS) && savedRole.belongsToCompany(user.getCompany())) {
                return savedRole;
            } else throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    @PostMapping("")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    Role create(@Parameter(description = "Role data to create") @Valid @RequestBody Role roleReq,
                HttpServletRequest req) {
        User user = userService.whoami(req);
        if (user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS)
                && user.getCompany().getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.ROLE)) {
            // INC-02 (T-06): always a new role. The client id is ignored, otherwise JPA would merge the request
            // into an existing role (a default one or one of another company). Company and type come from the server.
            Role role = Role.builder()
                    .name(roleReq.getName())
                    .description(roleReq.getDescription())
                    .externalId(roleReq.getExternalId())
                    .createPermissions(copyOf(roleReq.getCreatePermissions()))
                    .viewPermissions(copyOf(roleReq.getViewPermissions()))
                    .viewOtherPermissions(copyOf(roleReq.getViewOtherPermissions()))
                    .editOtherPermissions(copyOf(roleReq.getEditOtherPermissions()))
                    .deleteOtherPermissions(copyOf(roleReq.getDeleteOtherPermissions()))
                    .paid(true)
                    .code(RoleCode.USER_CREATED)
                    .roleType(RoleType.ROLE_CLIENT)
                    .companySettings(user.getCompany().getCompanySettings())
                    .build();
            roleService.assertCanGrant(user.getRole(), role);
            return roleService.create(role);
        } else throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public Role patch(@Parameter(description = "Role fields to update") @Valid @RequestBody RolePatchDTO role,
                      @PathVariable("id") Long id,
                      HttpServletRequest req) {
        User user = userService.whoami(req);
        if (!user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS)) {
            throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        }
        Optional<Role> optionalRole = roleService.findById(id);

        if (optionalRole.isPresent()) {
            Role savedRole = optionalRole.get();
            if (!savedRole.belongsOnlyToCompany(user.getCompany())) {
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            }
            assertNotDefaultRole(savedRole);
            roleService.assertCanManage(user.getRole(), savedRole, role);
            return roleService.update(id, role);
        } else throw new CustomException("Role not found", HttpStatus.NOT_FOUND);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_CLIENT')")
    public ResponseEntity delete(@PathVariable("id") Long id, HttpServletRequest req) {
        User user = userService.whoami(req);
        if (!user.getRole().getViewPermissions().contains(PermissionEntity.SETTINGS)) {
            throw new CustomException("Forbidden", HttpStatus.FORBIDDEN);
        }
        Optional<Role> optionalRole = roleService.findById(id);
        if (optionalRole.isPresent()) {
            Role savedRole = optionalRole.get();
            if (!savedRole.belongsOnlyToCompany(user.getCompany())) {
                throw new CustomException("Access denied", HttpStatus.FORBIDDEN);
            }
            assertNotDefaultRole(savedRole);
            roleService.delete(id);
            return new ResponseEntity(new SuccessResponse(true, "Deleted successfully"),
                    HttpStatus.OK);
        } else throw new CustomException("Role not found", HttpStatus.NOT_FOUND);
    }

    /**
     * Default roles are neither edited nor deleted (INC-02, D-2), even when attached to a company: the ownership check
     * alone only protects the global ones.
     */
    private static void assertNotDefaultRole(Role role) {
        if (role.getCode() != RoleCode.USER_CREATED)
            throw new CustomException("Default roles cannot be edited or deleted", HttpStatus.FORBIDDEN);
    }

    private static Set<PermissionEntity> copyOf(Set<PermissionEntity> permissions) {
        return permissions == null ? new HashSet<>() : new HashSet<>(permissions);
    }

}


