package com.grash.repository;

import com.grash.model.File;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface FileRepository extends JpaRepository<File, Long>, JpaSpecificationExecutor<File> {
    Collection<File> findByCompany_Id(Long id);

    List<File> findByIdIn(List<Long> ids);

    /**
     * P-17: the images of a task, which go with it in cascade ({@code file.task_id}).
     */
    List<File> findByTask_Id(Long taskId);

    /**
     * P-17: the images of the tasks of a preventive maintenance, which go with it in cascade.
     */
    List<File> findByTask_PreventiveMaintenance_Id(Long preventiveMaintenanceId);
}
