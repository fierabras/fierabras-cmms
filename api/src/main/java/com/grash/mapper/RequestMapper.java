package com.grash.mapper;

import com.grash.dto.RequestPatchDTO;
import com.grash.dto.RequestPostDTO;
import com.grash.dto.RequestShowDTO;
import com.grash.model.Request;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring", uses = {FileMapper.class, CustomFieldValueMapper.class})
public interface RequestMapper {
    // INC-02 (T-10): only approve and cancel change the review state of a request.
    @Mapping(target = "cancelled", ignore = true)
    Request updateRequest(@MappingTarget Request entity, RequestPatchDTO dto);

    @Mappings({})
    RequestPatchDTO toPatchDto(Request model);
    
    @Mapping(target = "image", source = "image", qualifiedByName = "toThumbnailDto")
    RequestShowDTO toShowDto(Request model);

    Request fromPostDTO(RequestPostDTO requestPostDTO);
}
