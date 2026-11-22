package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.advancedsearch.SpecificationBuilder;
import com.grash.factory.StorageServiceFactory;
import com.grash.model.File;
import com.grash.model.Task;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.FileRepository;
import com.grash.utils.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {
    public static final String FILE_IN_USE = "FILE_IN_USE";
    private static final String FILE_IN_USE_MESSAGE = "This file is used by other records and cannot be deleted. "
            + "Remove it from those records first";

    private final FileRepository fileRepository;
    private final DeletionService deletionService;
    private final StorageServiceFactory storageServiceFactory;

    public File create(File File) {
        return fileRepository.save(File);
    }

    public File update(File File) {
        return fileRepository.save(File);
    }

    public Collection<File> getAll() {
        return fileRepository.findAll();
    }

    /**
     * P-17 (PPB-18, PPB-19, PPB-23): deletes a file that no record uses, records it, and deletes its stored objects
     * after the commit. The image of a task of a closed or archived work order is not deleted (D-P17-2, PPB-15).
     */
    @Transactional
    public void delete(File file, User actor) {
        Task task = file.getTask();
        if (task != null && task.getWorkOrder() != null)
            WorkOrderStateGuard.check(task.getWorkOrder(), WorkOrderStateGuard.Change.CHILD);
        Long id = file.getId();
        List<String> objects = objectsOf(List.of(file));
        deletionService.delete(new AuditTarget(EntityType.FILE, id, null, file.getName(), file.getCompany().getId()),
                actor, AuditSource.API, inUse(id), () -> fileRepository.deleteById(id));
        deleteObjectsAfterCommit(objects);
    }

    /**
     * Every record that cites the file or has it attached: the 13 references (NO ACTION) and the 10 association
     * tables. A type is listed once, even when several tables lead to it.
     */
    private DeletionRule inUse(Long id) {
        return DeletionRule.inUse(FILE_IN_USE, FILE_IN_USE_MESSAGE)
                .blockedBy(EntityType.ASSET, () -> cited(id, "asset", "image_id")
                        || cited(id, "t_asset_file_associations", "id_file"))
                .blockedBy(EntityType.COMMENT, () -> cited(id, "comment_files", "files_id"))
                .blockedBy(EntityType.COMPANY, () -> cited(id, "company", "logo_id"))
                .blockedBy(EntityType.FLOOR_PLAN, () -> cited(id, "floor_plan", "image_id"))
                .blockedBy(EntityType.LOCATION, () -> cited(id, "location", "image_id")
                        || cited(id, "t_location_file_associations", "id_file"))
                .blockedBy(EntityType.METER, () -> cited(id, "meter", "image_id"))
                .blockedBy(EntityType.PART, () -> cited(id, "part", "image_id")
                        || cited(id, "t_part_file_associations", "id_file"))
                .blockedBy(EntityType.PREVENTIVE_MAINTENANCE, () -> cited(id, "preventive_maintenance", "image_id")
                        || cited(id, "preventive_maintenance_files", "files_id"))
                .blockedBy(EntityType.REQUEST, () -> cited(id, "request", "image_id")
                        || cited(id, "request", "audio_description_id")
                        || cited(id, "request_files", "files_id")
                        || cited(id, "t_request_file_associations", "id_file"))
                .blockedBy(EntityType.USER, () -> cited(id, "own_user", "image_id"))
                .blockedBy(EntityType.WORK_ORDER, () -> cited(id, "work_order", "image_id")
                        || cited(id, "work_order_files", "files_id")
                        || cited(id, "t_work_order_file_associations", "id_file"))
                .blockedBy(EntityType.WORK_ORDER_HISTORY, () -> cited(id, "work_order_aud", "image_id"))
                .blockedBy(EntityType.WORK_ORDER_METER_TRIGGER, () -> cited(id, "work_order_meter_trigger", "image_id")
                        || cited(id, "work_order_meter_trigger_files", "files_id"));
    }

    private boolean cited(Long id, String table, String column) {
        return deletionService.isCited(table, column, id);
    }

    /**
     * The stored objects of the files: each path and thumbnail path.
     */
    public static List<String> objectsOf(Collection<File> files) {
        List<String> objects = new ArrayList<>();
        files.forEach(file -> {
            objects.add(file.getPath());
            if (file.getThumbnailPath() != null) objects.add(file.getThumbnailPath());
        });
        return objects;
    }

    /**
     * The stored objects of the images of a task, read before the task is removed.
     */
    public List<String> objectsOfTask(Long taskId) {
        return objectsOf(fileRepository.findByTask_Id(taskId));
    }

    /**
     * The stored objects of the images of the tasks of a preventive maintenance, read before it is deleted.
     */
    public List<String> objectsOfPreventiveMaintenance(Long preventiveMaintenanceId) {
        return objectsOf(fileRepository.findByTask_PreventiveMaintenance_Id(preventiveMaintenanceId));
    }

    /**
     * P-17 (PPB-19, PPB-25): deletes the stored objects once the transaction commits, and never if it rolls back.
     * Each object is deleted on its own: a failure is logged with the path of the object and does not stop the other
     * objects nor the other effects of the commit. The operation stays committed.
     */
    public void deleteObjectsAfterCommit(List<String> objects) {
        if (objects.isEmpty()) return;
        AfterCommit.run(() -> objects.forEach(this::deleteObject));
    }

    private void deleteObject(String path) {
        try {
            storageServiceFactory.getStorageService().delete(path);
        } catch (RuntimeException failure) {
            // D-P17-1: only the path and the type of the failure; its message may carry an address of the storage
            log.error("P-17 stored object not deleted: path={}, failure={}", path, failure.getClass().getName());
        }
    }

    public Optional<File> findById(Long id) {
        return fileRepository.findById(id);
    }

    public Collection<File> findByCompany(Long id) {
        return fileRepository.findByCompany_Id(id);
    }

    public Page<File> findBySearchCriteria(SearchCriteria searchCriteria) {
        SpecificationBuilder<File> builder = new SpecificationBuilder<>();
        searchCriteria.getFilterFields().forEach(builder::with);
        Pageable page = PageRequest.of(searchCriteria.getPageNum(), searchCriteria.getPageSize(),
                searchCriteria.getDirection(), searchCriteria.getSortField());
        return fileRepository.findAll(builder.build(), page);
    }
}
