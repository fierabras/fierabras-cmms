package com.grash.mapper;

import com.grash.dto.WorkOrderBaseMiniDTO;
import com.grash.dto.workOrder.WorkOrderMiniDTO;
import com.grash.dto.workOrder.WorkOrderPatchDTO;
import com.grash.dto.workOrder.WorkOrderShowDTO;
import com.grash.dto.workOrder.WorkOrderPostDTO;
import com.grash.dto.workload.WorkloadWorkOrderDTO;
import com.grash.model.WorkOrder;
import org.mapstruct.*;

@Mapper(componentModel = "spring", uses = {PartMapper.class, FileMapper.class, LocationMapper.class
        , TeamMapper.class, UserMapper.class,
        CustomerMapper.class, AssetMapper.class, CustomFieldValueMapper.class})
public interface WorkOrderMapper {
    // INC-02 (T-11): closing data is set only by the server when the status changes (WorkOrderService.changeStatus).
    @Mapping(target = "completedBy", ignore = true)
    @Mapping(target = "completedOn", ignore = true)
    // P-12 (E-2): archiving and restoring are decided by WorkOrderService.patch, never by the mapping
    @Mapping(target = "archived", ignore = true)
    WorkOrder updateWorkOrder(@MappingTarget WorkOrder entity, WorkOrderPatchDTO dto);

    @Mappings({})
    WorkOrderPatchDTO toPatchDto(WorkOrder model);

    @Mappings({
            @Mapping(source = "parentRequest.audioDescription", target = "audioDescription"),
            @Mapping(target = "image", source = "image", qualifiedByName = "toThumbnailDto"),

    })
    WorkOrderShowDTO toShowDto(WorkOrder model);

    WorkOrderMiniDTO toMiniDto(WorkOrder model);

    WorkOrderBaseMiniDTO toBaseMiniDto(WorkOrder model);

    WorkOrder fromPostDto(WorkOrderPostDTO workOrderPostDTO);

    WorkloadWorkOrderDTO toWorkloadDto(WorkOrder model);

}
