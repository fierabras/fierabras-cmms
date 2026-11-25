package com.grash.service;

import com.grash.advancedsearch.SearchCriteria;
import com.grash.factory.StorageServiceFactory;
import com.grash.model.*;
import com.grash.model.User;
import com.grash.repository.UserRepository;
import com.grash.utils.CsvFileGenerator;
import com.grash.utils.Helper;
import com.grash.utils.MultipartFileImpl;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncExportService {

    private final WorkOrderService workOrderService;
    private final AssetService assetService;
    private final LocationService locationService;
    private final PartService partService;
    private final PartTransactionService partTransactionService;
    private final UserRepository userRepository;
    private final MeterService meterService;
    private final PreventiveMaintenanceService preventiveMaintenanceService;
    private final CsvFileGenerator csvFileGenerator;
    private final XlsxExportService xlsxExportService;
    private final StorageServiceFactory storageServiceFactory;
    private final SimpMessageSendingOperations messagingTemplate;
    private final EntityManager entityManager;

    /**
     * INC-01: the work orders of the list, with its filters, order and scope ({@code scopedCriteria}).
     */
    @Async
    public void exportWorkOrders(User user, SearchCriteria scopedCriteria, String uuid) {
        deliver(user, uuid, "work-orders", "Work Orders.xlsx",
                () -> xlsxExportService.exportWorkOrders(user, scopedCriteria));
    }

    /**
     * RF-10: the requests of the list, with its filters, order and scope ({@code scopedCriteria}).
     */
    @Async
    public void exportRequests(User user, SearchCriteria scopedCriteria, String uuid) {
        deliver(user, uuid, "requests", "Requests.xlsx", () -> xlsxExportService.exportRequests(user, scopedCriteria));
    }

    /**
     * Uploads the generated file and publishes its signed URL, or publishes an error without any URL (I-4, I-5).
     */
    private void deliver(User user, String uuid, String entity, String fileName, Callable<byte[]> generator) {
        try {
            MultipartFile file = new MultipartFileImpl(generator.call(), fileName);
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/" + entity);
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for {}, uuid: {}", entity, uuid);
        } catch (Exception e) {
            log.error("Export failed for {}, uuid: {}", entity, uuid, e);
            // C-3: the detail of the failure only goes to the log
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: export failed");
        }
    }

    @Async
    public void exportAssets(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<Asset> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = assetService.findByCompanyForExport(user.getCompany().getId(), PageRequest.of(page, 100));
                csvFileGenerator.writeAssetsToCsv(
                        result.getContent(),
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Assets.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/assets");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for assets, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for assets, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }

    @Async
    public void exportLocations(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<Location> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = locationService.findByCompanyForExport(user.getCompany().getId(), PageRequest.of(page, 100));
                csvFileGenerator.writeLocationsToCsv(
                        result.getContent(),
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Locations.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/locations");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for locations, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for locations, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }

    @Async
    public void exportParts(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<Part> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = partService.findByCompanyForExport(user.getCompany().getId(), PageRequest.of(page, 100));
                csvFileGenerator.writePartsToCsv(
                        result.getContent(),
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Parts.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/parts");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for parts, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for parts, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }

    @Async
    public void exportMeters(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<Meter> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = meterService.findByCompanyForExport(user.getCompany().getId(), PageRequest.of(page, 100));
                csvFileGenerator.writeMetersToCsv(
                        result.getContent(),
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Meters.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/meters");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for meters, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for meters, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }

    @Async
    public void exportPreventiveMaintenances(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<PreventiveMaintenance> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = preventiveMaintenanceService.findByCompanyForExport(user.getCompany().getId(),
                        PageRequest.of(page, 100));
                csvFileGenerator.writePreventiveMaintenancesToCsv(
                        result.getContent(),
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Preventive Maintenances.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/preventive-maintenances");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for preventive-maintenances, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for preventive-maintenances, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }

    @Async
    public void exportPartTransactions(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<PartTransaction> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = partTransactionService.findByCompanyForExport(user.getCompany().getId(), PageRequest.of(page, 100));
                // P-16b (D-4): the names of the users of the page
                Map<Long, String> userNames = userRepository.findByIdInAndCompany_Id(result.getContent().stream()
                                .map(PartTransaction::getCreatedBy).filter(Objects::nonNull).collect(Collectors.toSet()),
                        user.getCompany().getId()).stream().collect(Collectors.toMap(User::getId, User::getFullName));
                csvFileGenerator.writePartTransactionsToCsv(
                        result.getContent(),
                        userNames,
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Part Transactions.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/part-transactions");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for part-transactions, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for part-transactions, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }

    @Async
    public void exportCostsAndTimes(User user, String uuid) {
        try {
            ByteArrayOutputStream target = new ByteArrayOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(target, StandardCharsets.UTF_8);
            int page = 0;
            Page<WorkOrder> result;
            String csvSeparator = user.getCompany().getCompanySettings().getGeneralPreferences().getCsvSeparator();
            Locale locale = Helper.getLocale(user);
            do {
                result = workOrderService.findByCompanyWithTimeAndCost(user.getCompany().getId(), PageRequest.of(page
                        , 100));
                csvFileGenerator.writeCostsAndTimesToCsv(
                        result.getContent(),
                        outputStreamWriter,
                        locale,
                        csvSeparator,
                        page == 0);

                entityManager.clear();
                page++;
            }
            while (result.hasNext());
            outputStreamWriter.close();
            byte[] bytes = target.toByteArray();
            MultipartFile file = new MultipartFileImpl(bytes, "Costs and Times.csv");
            String filePath = storageServiceFactory.getStorageService().uploadAndSign(file,
                    user.getCompany().getId() + "/exports/" + uuid + "/costs-times");
            messagingTemplate.convertAndSend("/exports/" + uuid, filePath);
            log.info("Export completed for costs-times, uuid: {}", uuid);
        } catch (Exception e) {
            log.error("Export failed for costs-times, uuid: {}", uuid, e);
            messagingTemplate.convertAndSend("/exports/" + uuid, "error: " + e.getMessage());
        }
    }
}