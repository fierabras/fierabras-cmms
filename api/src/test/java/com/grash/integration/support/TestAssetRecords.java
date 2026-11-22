package com.grash.integration.support;

import com.grash.model.Asset;
import com.grash.model.Company;
import com.grash.model.FloorPlan;
import com.grash.model.Location;
import com.grash.model.Meter;
import com.grash.model.PreventiveMaintenance;
import com.grash.model.Reading;
import com.grash.model.Request;
import com.grash.model.TaskBase;
import com.grash.model.WorkOrderMeterTrigger;
import com.grash.model.Workflow;
import com.grash.model.WorkflowAction;
import com.grash.model.WorkflowCondition;
import com.grash.model.enums.WorkOrderMeterTriggerCondition;
import com.grash.model.enums.workflow.WFMainCondition;
import com.grash.model.enums.workflow.WorkOrderAction;
import com.grash.model.enums.workflow.WorkOrderCondition;
import com.grash.repository.FloorPlanRepository;
import com.grash.repository.MeterRepository;
import com.grash.repository.PreventiveMaintenanceRepository;
import com.grash.repository.ReadingRepository;
import com.grash.repository.RequestRepository;
import com.grash.repository.TaskBaseRepository;
import com.grash.repository.WorkOrderMeterTriggerRepository;
import com.grash.repository.WorkflowRepository;
import org.springframework.boot.test.context.TestComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * P-14: creates and commits the records of the assets, locations and meters module and those that cite them. Every
 * record belongs to the given organization; names are unique. Readings have no organization: delete them before the
 * organization ({@link TestOrganizations#delete} does it).
 */
@TestComponent
public class TestAssetRecords {

    private final MeterRepository meterRepository;
    private final ReadingRepository readingRepository;
    private final RequestRepository requestRepository;
    private final PreventiveMaintenanceRepository preventiveMaintenanceRepository;
    private final TaskBaseRepository taskBaseRepository;
    private final WorkOrderMeterTriggerRepository triggerRepository;
    private final WorkflowRepository workflowRepository;
    private final FloorPlanRepository floorPlanRepository;

    public TestAssetRecords(MeterRepository meterRepository, ReadingRepository readingRepository,
                            RequestRepository requestRepository,
                            PreventiveMaintenanceRepository preventiveMaintenanceRepository,
                            TaskBaseRepository taskBaseRepository, WorkOrderMeterTriggerRepository triggerRepository,
                            WorkflowRepository workflowRepository, FloorPlanRepository floorPlanRepository) {
        this.meterRepository = meterRepository;
        this.readingRepository = readingRepository;
        this.requestRepository = requestRepository;
        this.preventiveMaintenanceRepository = preventiveMaintenanceRepository;
        this.taskBaseRepository = taskBaseRepository;
        this.triggerRepository = triggerRepository;
        this.workflowRepository = workflowRepository;
        this.floorPlanRepository = floorPlanRepository;
    }

    public Meter meter(Company company, Asset asset) {
        Meter meter = new Meter();
        meter.setName(TestRecords.unique("P-14 meter"));
        meter.setUpdateFrequency(1);
        meter.setAsset(asset);
        meter.setCompany(company);
        return meterRepository.saveAndFlush(meter);
    }

    public Reading reading(Meter meter, double value) {
        Reading reading = new Reading();
        reading.setMeter(meter);
        reading.setValue(value);
        return readingRepository.saveAndFlush(reading);
    }

    public Request request(Company company) {
        Request request = new Request();
        request.setTitle(TestRecords.unique("P-14 request"));
        request.setCompany(company);
        return requestRepository.saveAndFlush(request);
    }

    public PreventiveMaintenance preventiveMaintenance(Company company) {
        PreventiveMaintenance preventiveMaintenance = new PreventiveMaintenance();
        preventiveMaintenance.setName(TestRecords.unique("P-14 PM"));
        preventiveMaintenance.setTitle(preventiveMaintenance.getName());
        preventiveMaintenance.setCompany(company);
        return preventiveMaintenanceRepository.saveAndFlush(preventiveMaintenance);
    }

    public TaskBase taskBase(Company company, Asset asset, Meter meter) {
        TaskBase taskBase = new TaskBase();
        taskBase.setLabel(TestRecords.unique("P-14 task"));
        taskBase.setCompany(company);
        taskBase.setAsset(asset);
        taskBase.setMeter(meter);
        return taskBaseRepository.saveAndFlush(taskBase);
    }

    public WorkOrderMeterTrigger trigger(Meter meter, WorkOrderMeterTriggerCondition condition, int value) {
        WorkOrderMeterTrigger trigger = new WorkOrderMeterTrigger();
        trigger.setName(TestRecords.unique("P-14 trigger"));
        trigger.setTitle(trigger.getName());
        trigger.setTriggerCondition(condition);
        trigger.setValue(value);
        trigger.setMeter(meter);
        trigger.setCompany(meter.getCompany());
        return triggerRepository.saveAndFlush(trigger);
    }

    /**
     * A workflow whose action assigns {@code actionAsset} or {@code actionLocation} and whose condition is on
     * {@code conditionAsset} or {@code conditionLocation}; any of them may be {@code null}.
     */
    public Workflow workflow(Company company, Asset actionAsset, Location actionLocation, Asset conditionAsset,
                             Location conditionLocation) {
        WorkflowAction action = new WorkflowAction();
        action.setCompany(company);
        action.setWorkOrderAction(actionLocation != null ? WorkOrderAction.ASSIGN_LOCATION
                : WorkOrderAction.ASSIGN_ASSET);
        action.setAsset(actionAsset);
        action.setLocation(actionLocation);
        WorkflowCondition condition = new WorkflowCondition();
        condition.setCompany(company);
        condition.setWorkOrderCondition(conditionLocation != null ? WorkOrderCondition.LOCATION_IS
                : WorkOrderCondition.ASSET_IS);
        condition.setAsset(conditionAsset);
        condition.setLocation(conditionLocation);
        Workflow workflow = new Workflow();
        workflow.setCompany(company);
        workflow.setTitle(TestRecords.unique("P-14 workflow"));
        workflow.setMainCondition(WFMainCondition.WORK_ORDER_CREATED);
        workflow.setAction(action);
        workflow.setSecondaryConditions(new ArrayList<>(List.of(condition)));
        return workflowRepository.saveAndFlush(workflow);
    }

    public FloorPlan floorPlan(Location location) {
        FloorPlan floorPlan = new FloorPlan();
        floorPlan.setName(TestRecords.unique("P-14 floor plan"));
        floorPlan.setLocation(location);
        return floorPlanRepository.saveAndFlush(floorPlan);
    }
}
