package com.grash.service;

import com.grash.configuration.ExternalSendConfig;
import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * P-23: the door of mail, notifications and webhooks. A send that fails or does not fit never fails the operation
 * that asked for it, is not retried, and is recorded in the audit log against the record it was about (D-1, D-2).
 * <p>
 * A record goes in a transaction of its own, so it survives a rollback and can be written after a commit. At most
 * one record per organization and action every {@link #WINDOW} (CA-P23-07); the others only go to the log. The audit
 * log and the log never keep the text of the exception, addresses or URLs.
 */
@Service
@Slf4j
public class ExternalSendService {

    /**
     * Fixed window of the rule that bounds the records (CA-P23-07).
     */
    static final Duration WINDOW = Duration.ofMinutes(10);

    public enum Channel {
        MAIL(AuditAction.MAIL_FAILED, AuditAction.MAIL_DISCARDED),
        NOTIFICATION(AuditAction.NOTIFICATION_FAILED, AuditAction.NOTIFICATION_DISCARDED),
        WEBHOOK(AuditAction.WEBHOOK_FAILED, AuditAction.WEBHOOK_DISCARDED);

        private final AuditAction failed;
        private final AuditAction discarded;

        Channel(AuditAction failed, AuditAction discarded) {
            this.failed = failed;
            this.discarded = discarded;
        }
    }

    private record Key(Long companyId, AuditAction action) {
    }

    private final TaskExecutor executor;
    private final AuditLogService auditLogService;
    private final TransactionTemplate newTransaction;
    private final boolean mailEnabled;
    private final Clock clock;
    private final Map<Key, Instant> lastRecorded = new ConcurrentHashMap<>();

    @Autowired
    public ExternalSendService(@Qualifier(ExternalSendConfig.EXECUTOR) TaskExecutor executor,
                               AuditLogService auditLogService, PlatformTransactionManager transactionManager,
                               @Value("${mail.enable:false}") boolean mailEnabled) {
        this(executor, auditLogService, transactionManager, mailEnabled, Clock.systemUTC());
    }

    ExternalSendService(TaskExecutor executor, AuditLogService auditLogService,
                        PlatformTransactionManager transactionManager, boolean mailEnabled, Clock clock) {
        this.executor = executor;
        this.auditLogService = auditLogService;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.mailEnabled = mailEnabled;
        this.clock = clock;
    }

    /**
     * Sends a mail through the asynchronous six-argument method of {@code MailService}, given as {@code send}. A
     * mail that does not fit is dropped and a failed one is recorded, against {@code origin}; neither reaches the
     * caller (CA-P23-01, CA-P23-03, CA-P23-04).
     */
    public void mail(AuditTarget origin, Supplier<CompletableFuture<Void>> send) {
        AuditSource source = currentSource();
        CompletableFuture<Void> sent;
        try {
            sent = send.get();
        } catch (RejectedExecutionException e) {
            discarded(Channel.MAIL, origin, source);
            return;
        } catch (RuntimeException e) {
            failed(Channel.MAIL, origin, source, e);
            return;
        }
        if (sent != null) sent.whenComplete((result, failure) -> {
            if (failure != null) failed(Channel.MAIL, origin, source, failure);
        });
    }

    /**
     * Runs {@code send} on the executor of the sends. If it does not fit, it is dropped; if it throws, it failed.
     * Both are recorded against {@code origin}, and neither reaches the caller (CA-P23-03, CA-P23-04).
     */
    public void submit(Channel channel, AuditTarget origin, Runnable send) {
        AuditSource source = currentSource();
        try {
            executor.execute(() -> {
                try {
                    send.run();
                } catch (RuntimeException e) {
                    failed(channel, origin, source, e);
                }
            });
        } catch (RejectedExecutionException e) {
            discarded(channel, origin, source);
        }
    }

    /**
     * Where a send comes from: a request of a user, or a job.
     */
    public static AuditSource currentSource() {
        return RequestContextHolder.getRequestAttributes() != null ? AuditSource.API : AuditSource.JOB;
    }

    /**
     * Records that a send about {@code origin} failed. Never throws.
     */
    public void failed(Channel channel, AuditTarget origin, AuditSource source, Throwable failure) {
        log.warn("P-23: {} about {} {} failed: {}", channel, origin.type(), origin.id(),
                failure.getClass().getSimpleName());
        record(channel.failed, origin, source);
    }

    /**
     * Records that a send about {@code origin} was dropped because the sends were full. Never throws.
     */
    public void discarded(Channel channel, AuditTarget origin, AuditSource source) {
        // CA-P23-06: with mail disabled, a mail that does not fit would not have been sent: no false alarm
        if (channel == Channel.MAIL && !mailEnabled) return;
        log.warn("P-23: {} about {} {} discarded: the sends are full", channel, origin.type(), origin.id());
        record(channel.discarded, origin, source);
    }

    private void record(AuditAction action, AuditTarget origin, AuditSource source) {
        Key key = new Key(origin.companyId(), action);
        Instant now = clock.instant();
        if (!claim(key, now)) {
            log.info("P-23: {} about {} {} not recorded: one {} already recorded for organization {} in the last {}",
                    action, origin.type(), origin.id(), action, origin.companyId(), WINDOW);
            return;
        }
        try {
            newTransaction.executeWithoutResult(status ->
                    auditLogService.record(origin, null, action, source, Map.of(), 0));
        } catch (RuntimeException e) {
            lastRecorded.remove(key, now);
            log.error("P-23: {} about {} {} could not be recorded: {}", action, origin.type(), origin.id(),
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Whether a record of {@code key} is due at {@code now}; if so, marks it.
     */
    private boolean claim(Key key, Instant now) {
        boolean[] due = {false};
        lastRecorded.compute(key, (k, last) -> {
            if (last != null && now.isBefore(last.plus(WINDOW))) return last;
            due[0] = true;
            return now;
        });
        return due[0];
    }
}
