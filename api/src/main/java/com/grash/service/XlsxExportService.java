package com.grash.service;

import com.grash.advancedsearch.OrderedIdQuery;
import com.grash.advancedsearch.SearchCriteria;
import com.grash.model.*;
import com.grash.model.enums.DateFormat;
import com.grash.model.enums.FieldType;
import com.grash.utils.ExportColumn;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * XLSX files of the work order and request lists (INC-01, INC-03, RF-10). The criteria must already hold the scope
 * of the user, as computed for their list.
 */
@Service
@RequiredArgsConstructor
public class XlsxExportService {

    private final OrderedIdQuery orderedIdQuery;
    private final TabularExportService tabularExportService;
    private final WorkOrderService workOrderService;
    private final RequestService requestService;
    private final MessageSource messageSource;

    @Transactional(readOnly = true)
    public byte[] exportWorkOrders(User user, SearchCriteria scopedCriteria) throws IOException {
        Locale locale = Helper.getLocale(user);
        GeneralPreferences preferences = user.getCompany().getCompanySettings().getGeneralPreferences();
        DateFormat dateFormat = preferences.getDateFormat();
        String timeZone = preferences.getTimeZone();
        List<Long> ids = orderedIdQuery.findOrderedIds(WorkOrder.class,
                workOrderService.getSpecification(scopedCriteria), scopedCriteria.sortWithIdTiebreak());
        // D-02: the columns of the former CSV export, in the same order
        List<ExportColumn<WorkOrder>> columns = List.of(
                new ExportColumn<>("ID", WorkOrder::getId),
                new ExportColumn<>("Title", WorkOrder::getTitle),
                new ExportColumn<>("Status", workOrder -> workOrder.getStatus() == null ? null :
                        messageSource.getMessage(workOrder.getStatus().toString(), null, locale)),
                new ExportColumn<>("Priority", workOrder -> workOrder.getPriority() == null ? null :
                        messageSource.getMessage(workOrder.getPriority().toString(), null, locale)),
                new ExportColumn<>("Description", WorkOrder::getDescription),
                new ExportColumn<>("Due_Date", workOrder -> Helper.getFormattedDate(workOrder.getDueDate(),
                        dateFormat, timeZone)),
                new ExportColumn<>("Estimated_Duration", WorkOrder::getEstimatedDuration),
                new ExportColumn<>("Requires_Signature",
                        workOrder -> Helper.getStringFromBoolean(workOrder.isRequiredSignature(), messageSource,
                                locale)),
                new ExportColumn<>("Category", workOrder -> workOrder.getCategory() == null ? null :
                        workOrder.getCategory().getName()),
                new ExportColumn<>("Location_Name", workOrder -> workOrder.getLocation() == null ? null :
                        workOrder.getLocation().getName()),
                new ExportColumn<>("Team_Name", workOrder -> workOrder.getTeam() == null ? null :
                        workOrder.getTeam().getName()),
                new ExportColumn<>("Primary_User_Email", workOrder -> workOrder.getPrimaryUser() == null ? null :
                        workOrder.getPrimaryUser().getEmail()),
                new ExportColumn<>("Assigned_To_Emails", workOrder -> Helper.enumerate(workOrder.getAssignedTo()
                        .stream().map(User::getEmail).toList())),
                new ExportColumn<>("Asset_Name", workOrder -> workOrder.getAsset() == null ? null :
                        workOrder.getAsset().getName()),
                new ExportColumn<>("Completed_By_Email", workOrder -> workOrder.getCompletedBy() == null ? null :
                        workOrder.getCompletedBy().getEmail()),
                new ExportColumn<>("Completed_On", workOrder -> Helper.getFormattedDate(workOrder.getCompletedOn(),
                        dateFormat, timeZone)),
                new ExportColumn<>("Archived", workOrder -> Helper.getStringFromBoolean(workOrder.isArchived(),
                        messageSource, locale)),
                new ExportColumn<>("Feedback", WorkOrder::getFeedback),
                new ExportColumn<>("Customers", workOrder -> Helper.enumerate(workOrder.getCustomers().stream()
                        .map(Customer::getName).toList())),
                new ExportColumn<>("Created_At", workOrder -> Helper.getFormattedDate(workOrder.getCreatedAt(),
                        dateFormat, timeZone)));
        return tabularExportService.generate("Work Orders", columns, locale, ids,
                workOrderService::findAllForExport, WorkOrder::getId);
    }

    /**
     * RF-10: the columns of the request list (D-04); the due date only when the request configuration of the
     * company does not hide it (RF-01).
     */
    @Transactional(readOnly = true)
    public byte[] exportRequests(User user, SearchCriteria scopedCriteria) throws IOException {
        Locale locale = Helper.getLocale(user);
        CompanySettings companySettings = user.getCompany().getCompanySettings();
        DateFormat dateFormat = companySettings.getGeneralPreferences().getDateFormat();
        String timeZone = companySettings.getGeneralPreferences().getTimeZone();
        boolean dueDateHidden = companySettings.getWorkOrderRequestConfiguration().getFieldConfigurations().stream()
                .anyMatch(field -> field.getFieldName().equals("dueDate") && field.getFieldType() == FieldType.HIDDEN);
        List<Long> ids = orderedIdQuery.findOrderedIds(Request.class,
                requestService.getSpecification(scopedCriteria), scopedCriteria.sortWithIdTiebreak());
        List<ExportColumn<Request>> columns = new ArrayList<>(List.of(
                new ExportColumn<>("Folio", Request::getCustomId),
                new ExportColumn<>("Title", Request::getTitle),
                new ExportColumn<>("Description", Request::getDescription),
                new ExportColumn<>("Priority", request -> request.getPriority() == null ? null :
                        messageSource.getMessage(request.getPriority().toString(), null, locale)),
                // same rule as the status column of the request list
                new ExportColumn<>("Status", request -> messageSource.getMessage(request.isCancelled() ?
                        "Request_Rejected" : request.getWorkOrder() != null ? "Request_Approved" :
                        "Request_Pending", null, locale))));
        if (!dueDateHidden) {
            columns.add(new ExportColumn<>("Due_Date", request -> Helper.getFormattedDate(request.getDueDate(),
                    dateFormat, timeZone)));
        }
        // P-02: the real location of the request
        columns.addAll(List.of(
                new ExportColumn<>("Asset", request -> request.getAsset() == null ? null :
                        request.getAsset().getName()),
                new ExportColumn<>("Location", request -> request.getLocation() == null ? null :
                        request.getLocation().getName()),
                new ExportColumn<>("Created_At", request -> Helper.getFormattedDate(request.getCreatedAt(),
                        dateFormat, timeZone))));
        return tabularExportService.generate("Requests", columns, locale, ids, requestService::findAllForExport,
                Request::getId);
    }
}
