package com.grash.service;

import com.grash.dto.TaskPatchDTO;
import com.grash.exception.CustomException;
import com.grash.mapper.TaskMapper;
import com.grash.model.Task;
import com.grash.model.User;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskRepository taskRepository;
    private final WorkOrderService workOrderService;
    private final CompanyService companyService;
    private final FileService fileService;
    private final TaskMapper taskMapper;
    private final EntityManager em;
    private final DeletionService deletionService;

    @Transactional
    public Task create(Task Task) {
        Task savedTask = taskRepository.saveAndFlush(Task);
        em.refresh(savedTask);
        return savedTask;
    }

    @Transactional
    public Task update(Long id, TaskPatchDTO task) {
        if (taskRepository.existsById(id)) {
            Task savedTask = taskRepository.findById(id).get();
            Task updatedTask = taskRepository.saveAndFlush(taskMapper.updateTask(savedTask, task));
            em.refresh(updatedTask);
            return updatedTask;
        } else throw new CustomException("Not found", HttpStatus.NOT_FOUND);
    }

    public Collection<Task> getAll() {
        return taskRepository.findAll();
    }

    public void delete(Long id) {
        taskRepository.deleteById(id);
    }

    /**
     * P-12 (E-6, PPB-23): removes a task of an open work order and records it, in one transaction. P-15 (E-5,
     * TD-P17-10): a task of a preventive maintenance too, with the folio of its preventive maintenance. Its task
     * definition stays: the tasks of the work orders generated from it keep using it (E-6).
     */
    @Transactional
    public void delete(Task task, User actor) {
        // P-17 (E-3): the images of the task go with it in cascade (file.task_id); their objects after the commit
        List<String> images = fileService.objectsOfTask(task.getId());
        // P-17 (C-1): an image cited by another record answers 409 RECORD_IN_USE, never 500 (no rule)
        AuditTarget target = task.getWorkOrder() == null
                ? AuditTarget.childOf(EntityType.TASK, task.getId(), task.getTaskBase().getLabel(),
                task.getPreventiveMaintenance())
                : AuditTarget.childOf(EntityType.TASK, task.getId(), task.getTaskBase().getLabel(), task.getWorkOrder());
        deletionService.delete(target, actor, AuditSource.API, null, () -> taskRepository.deleteById(task.getId()));
        fileService.deleteObjectsAfterCommit(images);
    }

    public Optional<Task> findById(Long id) {
        return taskRepository.findById(id);
    }

    public List<Task> findByWorkOrder(Long id) {
        return taskRepository.findByWorkOrder_IdOrderByCreatedAtAsc(id);
    }

    public List<Task> findByPreventiveMaintenance(Long id) {
        return taskRepository.findByPreventiveMaintenance_Id(id);
    }
}

