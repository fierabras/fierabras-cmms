package com.grash.service;

import com.google.rpc.Help;
import com.grash.dto.RolePatchDTO;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.mapper.RoleMapper;
import com.grash.model.Company;
import com.grash.model.Role;
import com.grash.model.enums.PermissionEntity;
import com.grash.model.enums.RoleCode;
import com.grash.model.enums.RoleType;
import com.grash.repository.RoleRepository;
import com.grash.repository.UserRepository;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleService {
    private final RoleRepository roleRepository;
    private final RoleMapper roleMapper;
    private final CompanySettingsService companySettingsService;
    private final LicenseService licenseService;
    private final UserRepository userRepository;

    public Role create(Role role) {
        if (role.getCode().equals(RoleCode.USER_CREATED) && !licenseService.hasEntitlement(LicenseEntitlement.CUSTOM_ROLES))
            throw new CustomException("You need a license to create custom roles", HttpStatus.FORBIDDEN);
        return roleRepository.save(role);
    }

    public Role update(Long id, RolePatchDTO role) {
        if (roleRepository.existsById(id)) {
            Role savedRole = roleRepository.findById(id).get();
            return roleRepository.save(roleMapper.updateRole(savedRole, role));
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    /**
     * Nobody grants permissions they do not hold (INC-02, C10): each of the five requested sets must be a subset of
     * the same set of the grantor's role. A null requested set grants nothing; a null held set holds nothing.
     */
    public void assertCanGrant(Role grantor, Role requested) {
        assertCanGrant(grantor, requested.getCreatePermissions(), requested.getViewPermissions(),
                requested.getViewOtherPermissions(), requested.getEditOtherPermissions(),
                requested.getDeleteOtherPermissions());
    }

    public void assertCanGrant(Role grantor, RolePatchDTO requested) {
        assertCanGrant(grantor, requested.getCreatePermissions(), requested.getViewPermissions(),
                requested.getViewOtherPermissions(), requested.getEditOtherPermissions(),
                requested.getDeleteOtherPermissions());
    }

    private void assertCanGrant(Role grantor, Collection<PermissionEntity> create, Collection<PermissionEntity> view,
                                Collection<PermissionEntity> viewOther, Collection<PermissionEntity> editOther,
                                Collection<PermissionEntity> deleteOther) {
        if (!holds(grantor.getCreatePermissions(), create)
                || !holds(grantor.getViewPermissions(), view)
                || !holds(grantor.getViewOtherPermissions(), viewOther)
                || !holds(grantor.getEditOtherPermissions(), editOther)
                || !holds(grantor.getDeleteOtherPermissions(), deleteOther))
            throw new CustomException("You cannot grant permissions you do not have", HttpStatus.FORBIDDEN);
    }

    private static boolean holds(Collection<PermissionEntity> held, Collection<PermissionEntity> requested) {
        return requested == null || requested.isEmpty() || (held != null && held.containsAll(requested));
    }

    public Collection<Role> getAll() {
        return roleRepository.findAll();
    }

    /**
     * A role assigned to at least one user is never deleted (INC-02, C8). The foreign key own_user.role_id
     * (ON DELETE RESTRICT) rejects a user assigned between the check and the deletion.
     */
    public void delete(Long id) {
        if (userRepository.existsByRole_Id(id)) throw roleInUse();
        try {
            roleRepository.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            throw roleInUse();
        }
    }

    private static CustomException roleInUse() {
        return new CustomException("This role is assigned to at least one user. Assign another role to those users " +
                "before deleting it", HttpStatus.CONFLICT);
    }

    public Optional<Role> findById(Long id) {
        return roleRepository.findById(id);
    }

    public List<Role> findByCodeAndRoleType(RoleCode code, RoleType roleType) {
        return roleRepository.findByCodeAndRoleType(code, roleType);
    }

    public List<Role> findByCompany(Long id) {
        List<Role> result = findDefaultRoles();
        result.addAll(roleRepository.findByCompany_Id(id));
        return result;
    }

    public List<Role> findDefaultRoles() {
        return roleRepository.findDefaultRoles();
    }

    public Optional<Role> findDefaultRoleWithCode(RoleCode code) {
        return roleRepository.findDefaultRoleWithCode(code);
    }

    public List<Role> saveAll(List<Role> roles) {
        return roleRepository.saveAll(roles);
    }

    public void updateDefaultRoles() {
        List<Role> rolesToUpdate = new ArrayList<>();

        // Iterate through each tenant type's roles to find roles that need updates or additions
        List<Role> upToDateRoles = Helper.getDefaultRoles();
        List<Role> existingDefaultRoles = findDefaultRoles();
        List<Role> rolesToAdd =
                new ArrayList<>(upToDateRoles.stream().filter(upToDateRole -> existingDefaultRoles.stream().noneMatch(existingDefaultRole -> existingDefaultRole.getCode().equals(upToDateRole.getCode()))).toList());

        // Update roles by comparing privileges and 'paid' status between default and up-to-date roles
        for (Role existingDefaultRole : existingDefaultRoles) {
            for (Role upToDateRole : upToDateRoles) {
                if (existingDefaultRole.getCode().equals(upToDateRole.getCode())) {

                    // If privileges or 'paid' status differ, update default role
                    if (!CollectionUtils.isEqualCollection(existingDefaultRole.getCreatePermissions(),
                            upToDateRole.getCreatePermissions())
                            || !CollectionUtils.isEqualCollection(existingDefaultRole.getViewPermissions(),
                            upToDateRole.getViewPermissions())
                            || !CollectionUtils.isEqualCollection(existingDefaultRole.getViewOtherPermissions(),
                            upToDateRole.getViewOtherPermissions())
                            || !CollectionUtils.isEqualCollection(existingDefaultRole.getEditOtherPermissions(),
                            upToDateRole.getEditOtherPermissions())
                            || !CollectionUtils.isEqualCollection(existingDefaultRole.getDeleteOtherPermissions(),
                            upToDateRole.getDeleteOtherPermissions())
                            || existingDefaultRole.isPaid() != upToDateRole.isPaid()) {

                        // Clear and update privileges, and set 'paid' status
                        existingDefaultRole.getCreatePermissions().clear();
                        existingDefaultRole.getCreatePermissions().addAll(upToDateRole.getCreatePermissions());
                        existingDefaultRole.getViewPermissions().clear();
                        existingDefaultRole.getViewPermissions().addAll(upToDateRole.getViewPermissions());
                        existingDefaultRole.getViewOtherPermissions().clear();
                        existingDefaultRole.getViewOtherPermissions().addAll(upToDateRole.getViewOtherPermissions());
                        existingDefaultRole.getEditOtherPermissions().clear();
                        existingDefaultRole.getEditOtherPermissions().addAll(upToDateRole.getEditOtherPermissions());
                        existingDefaultRole.getDeleteOtherPermissions().clear();
                        existingDefaultRole.getDeleteOtherPermissions().addAll(upToDateRole.getDeleteOtherPermissions());
                        existingDefaultRole.setPaid(upToDateRole.isPaid());

                        // Add role to rolesToUpdate
                        rolesToUpdate.add(existingDefaultRole);
                    }
                    // Role matched, break loop to avoid redundant checks
                    break;
                }
            }
        }

        // Save any updated roles to the database
        if (!rolesToUpdate.isEmpty()) saveAll(rolesToUpdate);
        // Save any new roles to the database
        if (!rolesToAdd.isEmpty()) saveAll(rolesToAdd);
    }
}
