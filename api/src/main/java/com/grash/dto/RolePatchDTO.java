package com.grash.dto;

import com.grash.model.enums.PermissionEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@Schema(description = "DTO for patching an existing role. Each permission set sent replaces the current one; a null " +
        "or omitted set keeps the current one, and an empty set removes every permission of that set.")
public class RolePatchDTO {

    @Schema(description = "Version of the role as read by the client. When sent and stale, the PATCH is rejected " +
            "with 409")
    private Long version;

    @Schema(description = "Name")
    private String name;

    @Schema(description = "Description")
    private String description;

    @Schema(description = "External identifier")
    private String externalId;

    @Schema(description = "List of entities with create permissions")
    private List<PermissionEntity> createPermissions;
    
    @Schema(description = "List of entities with view permissions")
    private List<PermissionEntity> viewPermissions;

    @Schema(description = "List of entities with view-other permissions")
    private List<PermissionEntity> viewOtherPermissions;

    @Schema(description = "List of entities with edit-other permissions")
    private List<PermissionEntity> editOtherPermissions;
    
    @Schema(description = "List of entities with delete-other permissions")
    private List<PermissionEntity> deleteOtherPermissions;
}
