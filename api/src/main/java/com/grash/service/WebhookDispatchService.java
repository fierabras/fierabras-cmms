package com.grash.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.dto.license.LicenseEntitlement;
import com.grash.exception.CustomException;
import com.grash.model.Company;
import com.grash.model.WebhookEndpoint;
import com.grash.model.WorkOrderCategory;
import com.grash.model.enums.AssetStatus;
import com.grash.model.enums.EntityType;
import com.grash.model.enums.PlanFeatures;
import com.grash.model.enums.Status;
import com.grash.model.enums.webhook.PartField;
import com.grash.model.enums.webhook.WOField;
import com.grash.model.enums.webhook.WebhookEvent;
import com.grash.repository.CompanyRepository;
import com.grash.repository.WebhookEndpointRepository;
import com.grash.utils.WebhookUrlValidator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDispatchService {

    private final WebhookEndpointRepository webhookEndpointRepository;
    private final CompanyRepository companyRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper;
    private final LicenseService licenseService;
    private final ExternalSendService externalSendService;

    @Value("${external-http.connect-timeout-ms:10000}")
    private int connectTimeoutMs;
    @Value("${external-http.read-timeout-ms:20000}")
    private int readTimeoutMs;

    /**
     * P-23 (E-1): a destination that does not answer never holds a dispatch for longer than these limits.
     */
    @PostConstruct
    void limitTheTimeOfTheRequests() {
        restTemplate.setRequestFactory(requestFactory(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * P-23 (E-1): the HTTP client of the webhooks, with its time limits in milliseconds.
     */
    public static ClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * P-23 (E-2, E-3): dispatches the event through {@link ExternalSendService}, off the thread of the caller. A
     * dispatch that does not fit or whose delivery fails is recorded against the record of the event; it never
     * reaches the caller. Nothing is submitted when the organization has no enabled endpoint for the event.
     */
    public void dispatchWebhook(
            Company company,
            WebhookEvent eventType,
            Map<String, Object> result,
            String serializedField,
            Object serializedPayload,
            Collection<WOField> changedFields,
            AssetStatus assetStatus,
            Status workOrderStatus,
            Collection<WorkOrderCategory> workOrderCategories,
            Collection<PartField> partFields
    ) {
        if (webhookEndpointRepository.findByCompanyIdAndEnabled(company.getId(), true).stream()
                .noneMatch(endpoint -> endpoint.getEvent() == eventType)) return;
        externalSendService.submit(ExternalSendService.Channel.WEBHOOK, origin(company, eventType, result),
                () -> deliver(company, eventType, result, serializedField, serializedPayload, changedFields,
                        assetStatus, workOrderStatus, workOrderCategories, partFields));
    }

    /**
     * P-23: the record a webhook is about, from its event and the identifier its payload carries; the organization
     * when the payload has none. Without folio or name: the audit log shows its identifier.
     */
    static AuditTarget origin(Company company, WebhookEvent eventType, Map<String, Object> payload) {
        EntityType type;
        String key;
        switch (eventType) {
            case NEW_ASSET, ASSET_STATUS_CHANGE -> { type = EntityType.ASSET; key = "assetId"; }
            case METER_TRIGGER_STATUS_CHANGE -> { type = EntityType.METER; key = "meterId"; }
            case NEW_LOCATION -> { type = EntityType.LOCATION; key = "locationId"; }
            case NEW_PART, PART_CHANGE, PART_DELETE, PART_QUANTITY_CHANGED -> { type = EntityType.PART; key = "partId"; }
            case NEW_PURCHASE_ORDER, PURCHASE_ORDER_CHANGE, PURCHASE_ORDER_STATUS_CHANGE -> {
                type = EntityType.PURCHASE_ORDER;
                key = "purchaseOrderId";
            }
            case NEW_VENDOR -> { type = EntityType.VENDOR; key = "vendorId"; }
            case NEW_REQUEST, WORK_REQUEST_STATUS_CHANGE -> { type = EntityType.REQUEST; key = "requestId"; }
            default -> { type = EntityType.WORK_ORDER; key = "workOrderId"; }
        }
        if (payload.get(key) instanceof Number id) {
            return new AuditTarget(type, id.longValue(), null, null, company.getId());
        }
        return new AuditTarget(EntityType.COMPANY, company.getId(), null, null, company.getId());
    }

    private void deliver(
            Company company,
            WebhookEvent eventType,
            Map<String, Object> result,
            String serializedField,
            Object serializedPayload,
            Collection<WOField> changedFields,
            AssetStatus assetStatus,
            Status workOrderStatus,
            Collection<WorkOrderCategory> workOrderCategories,
            Collection<PartField> partFields
    ) {
        // Reload company in the async thread's session to avoid LazyInitializationException
        Company managedCompany = companyRepository.findByIdWithSubscription(company.getId()).orElse(null);
        if (managedCompany == null) return;

        if (!(licenseService.hasEntitlement(LicenseEntitlement.WEBHOOK) && managedCompany.getSubscription().getSubscriptionPlan().getFeatures().contains(PlanFeatures.WEBHOOK)))
            return;
        List<WebhookEndpoint> endpoints = webhookEndpointRepository
                .findByCompanyIdAndEnabled(managedCompany.getId(), true)
                .stream()
                .filter(endpoint -> endpoint.getEvent().equals(eventType))
                .filter(endpoint -> {
                    if (changedFields != null && !changedFields.isEmpty()
                            && endpoint.getWoFields() != null && !endpoint.getWoFields().isEmpty()) {
                        if (changedFields.stream().noneMatch(endpoint.getWoFields()::contains)) {
                            return false;
                        }
                    }
                    if (assetStatus != null
                            && endpoint.getAssetStatuses() != null && !endpoint.getAssetStatuses().isEmpty()) {
                        if (!endpoint.getAssetStatuses().contains(assetStatus)) {
                            return false;
                        }
                    }
                    if (workOrderStatus != null
                            && endpoint.getWorkOrderStatuses() != null && !endpoint.getWorkOrderStatuses().isEmpty()) {
                        if (!endpoint.getWorkOrderStatuses().contains(workOrderStatus)) {
                            return false;
                        }
                    }
                    if (workOrderCategories != null && !workOrderCategories.isEmpty()
                            && endpoint.getWorkOrderCategories() != null && !endpoint.getWorkOrderCategories().isEmpty()) {
                        Set<Long> endpointCategoryIds = endpoint.getWorkOrderCategories().stream()
                                .map(WorkOrderCategory::getId)
                                .collect(Collectors.toSet());
                        if (workOrderCategories.stream().noneMatch(cat -> endpointCategoryIds.contains(cat.getId()))) {
                            return false;
                        }
                    }
                    if (partFields != null && !partFields.isEmpty()
                            && endpoint.getPartFields() != null && !endpoint.getPartFields().isEmpty()) {
                        if (partFields.stream().noneMatch(endpoint.getPartFields()::contains)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();
        result.put("occurredAt", new Date());
        result.put("companyId", managedCompany.getId());
        boolean failed = false;
        for (WebhookEndpoint endpoint : endpoints) {
            try {
                result.put(serializedField, endpoint.isSerialize() ? serializedPayload : null);
                sendWebhook(endpoint, eventType, result);
                endpoint.setLastTriggeredAt(new Date());
                webhookEndpointRepository.save(endpoint);

            } catch (Exception e) {
                log.error("Failed to send webhook to: {}", endpoint.getUrl(), e);
                failed = true;
            }
        }
        // P-23 (E-3): the other endpoints still got the event; ExternalSendService records the failure once
        if (failed) throw new IllegalStateException("A webhook endpoint did not receive the event");
    }

    private void sendWebhook(WebhookEndpoint endpoint, WebhookEvent eventType, Object payload) {
        try {
            WebhookUrlValidator.validate(endpoint.getUrl());
            log.info("Sending webhook to: {}", endpoint.getUrl());
            String jsonPayload = objectMapper.writeValueAsString(payload);
            String signature = generateSignature(jsonPayload, endpoint.getSecret());
            String timestamp = String.valueOf(System.currentTimeMillis());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Webhook-Signature", signature);
            headers.set("X-Webhook-Timestamp", timestamp);
            headers.set("X-Webhook-Id", UUID.randomUUID().toString());
            headers.set("X-Webhook-Event", eventType.name());

            HttpEntity<String> request = new HttpEntity<>(jsonPayload, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    endpoint.getUrl(),
                    request,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Webhook failed with status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to send webhook", e);
        }
    }

    private String generateSignature(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate signature", e);
        }
    }
}
