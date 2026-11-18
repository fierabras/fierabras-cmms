package com.grash.mapper;

import com.grash.dto.RolePatchDTO;
import com.grash.model.Role;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring")
public interface RoleMapper {
    // INC-02 (T-13): a null or omitted permission set keeps the current one; the version is checked by RoleService.
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createPermissions", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "viewPermissions", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "viewOtherPermissions", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "editOtherPermissions", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "deleteOtherPermissions", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    Role updateRole(@MappingTarget Role entity, RolePatchDTO dto);

    @Mappings({})
    RolePatchDTO toPatchDto(Role model);
}
