package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.model.*;
import com.grash.model.enums.DateFormat;
import com.grash.utils.ExportColumn;
import com.grash.utils.Helper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * XLSX files of the work order and request lists (INC-01, INC-03, RF-10). The criteria must already hold the scope
 * of the user, as computed for their list.
 */
@Service
@RequiredArgsConstructor
public class XlsxExportService {

    private final TabularExportService tabularExportService;
    private final WorkOrderService workOrderService;
    private final MessageSource messageSource;

    @Transactional(readOnly = true)
    public byte[] exportWorkOrders(User user, SearchCriteria scopedCriteria) throws IOException {
        Locale locale = Helper.getLocale(user);
        GeneralPreferences preferences = user.getCompany().getCompanySettings().getGeneralPreferences();
        DateFormat dateFormat = preferences.getDateFormat();
        String timeZone = preferences.getTimeZone();
        List<Long> ids = tabularExportService.findOrderedIds(WorkOrder.class,
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
}
