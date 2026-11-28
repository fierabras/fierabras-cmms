package com.grash.service;

import com.grash.model.enums.AuditAction;
import com.grash.model.enums.AuditSource;
import com.grash.model.enums.EntityType;
import com.grash.service.ExternalSendService.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-23 (E-3): how a failed or dropped send is recorded, and the rule that bounds the records (CA-P23-07).
 */
class ExternalSendServiceTest {

    private static final AuditTarget WORK_ORDER = new AuditTarget(EntityType.WORK_ORDER, 7L, "WO000007",
            "Pump", 1L);

    /**
     * A clock the test moves.
     */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-27T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private AuditLogService auditLogService;
    private PlatformTransactionManager transactionManager;
    private TestClock clock;
    private ExternalSendService service;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        clock = new TestClock();
        service = new ExternalSendService(new SyncTaskExecutor(), auditLogService, transactionManager, clock);
    }

    @Test
    void sendThatThrows_recordedAsFailed_neverReachesTheCaller() {
        assertDoesNotThrow(() -> service.submit(Channel.WEBHOOK, WORK_ORDER, () -> {
            throw new IllegalStateException("destination down");
        }));

        verify(auditLogService).record(eq(WORK_ORDER), isNull(), eq(AuditAction.WEBHOOK_FAILED),
                eq(AuditSource.JOB), eq(Map.of()), eq(0));
    }

    @Test
    void sendThatDoesNotFit_recordedAsDropped_neverReachesTheCaller() {
        TaskExecutor full = task -> {
            throw new TaskRejectedException("full");
        };
        service = new ExternalSendService(full, auditLogService, transactionManager, clock);
        boolean[] ran = {false};

        assertDoesNotThrow(() -> service.submit(Channel.NOTIFICATION, WORK_ORDER, () -> ran[0] = true));

        assertFalse(ran[0]);
        verify(auditLogService).record(eq(WORK_ORDER), isNull(), eq(AuditAction.NOTIFICATION_DISCARDED),
                eq(AuditSource.JOB), eq(Map.of()), eq(0));
    }

    @Test
    void sendThatWorks_recordsNothing() {
        boolean[] ran = {false};

        service.submit(Channel.MAIL, WORK_ORDER, () -> ran[0] = true);

        assertTrue(ran[0]);
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), eq(0));
    }

    private void verifyRecorded(AuditTarget origin, AuditAction action, int times) {
        verify(auditLogService, times(times)).record(eq(origin), isNull(), eq(action), eq(AuditSource.API),
                eq(Map.of()), eq(0));
    }

    @Test
    void failure_recordedAgainstItsOrigin_withoutUser_inATransactionOfItsOwn() {
        service.failed(Channel.WEBHOOK, WORK_ORDER, AuditSource.API, new IllegalStateException("https://x?token=y"));

        verifyRecorded(WORK_ORDER, AuditAction.WEBHOOK_FAILED, 1);
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getValue().getPropagationBehavior());
    }

    @Test
    void discard_recordedWithItsOwnAction() {
        service.discarded(Channel.MAIL, WORK_ORDER, AuditSource.API);

        verifyRecorded(WORK_ORDER, AuditAction.MAIL_DISCARDED, 1);
    }

    @Test
    void eachChannel_hasItsFailureAndItsDiscard() {
        for (Channel channel : Channel.values()) {
            service.failed(channel, WORK_ORDER, AuditSource.API, new RuntimeException());
            service.discarded(channel, WORK_ORDER, AuditSource.API);
        }

        for (AuditAction action : new AuditAction[]{AuditAction.MAIL_FAILED, AuditAction.MAIL_DISCARDED,
                AuditAction.NOTIFICATION_FAILED, AuditAction.NOTIFICATION_DISCARDED, AuditAction.WEBHOOK_FAILED,
                AuditAction.WEBHOOK_DISCARDED}) {
            verifyRecorded(WORK_ORDER, action, 1);
        }
    }

    @Test
    void repeatedFailures_oneRecordPerOrganizationAndActionEveryTenMinutes() {
        AuditTarget other = new AuditTarget(EntityType.ASSET, 8L, "A000008", "Boiler", 1L);
        AuditTarget otherOrganization = new AuditTarget(EntityType.WORK_ORDER, 9L, "WO000001", "Fan", 2L);

        service.failed(Channel.MAIL, WORK_ORDER, AuditSource.API, new RuntimeException());
        service.failed(Channel.MAIL, other, AuditSource.API, new RuntimeException());
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        service.failed(Channel.MAIL, WORK_ORDER, AuditSource.API, new RuntimeException());
        service.failed(Channel.MAIL, otherOrganization, AuditSource.API, new RuntimeException());
        service.discarded(Channel.MAIL, WORK_ORDER, AuditSource.API);

        verifyRecorded(WORK_ORDER, AuditAction.MAIL_FAILED, 1);
        verifyRecorded(other, AuditAction.MAIL_FAILED, 0);
        verifyRecorded(otherOrganization, AuditAction.MAIL_FAILED, 1);
        verifyRecorded(WORK_ORDER, AuditAction.MAIL_DISCARDED, 1);

        clock.advance(Duration.ofSeconds(1));
        service.failed(Channel.MAIL, other, AuditSource.API, new RuntimeException());

        verifyRecorded(other, AuditAction.MAIL_FAILED, 1);
    }

    @Test
    void recordThatFails_neverReachesTheSender_andDoesNotUseTheWindow() {
        when(auditLogService.record(any(), any(), any(), any(), any(), eq(0)))
                .thenThrow(new IllegalStateException("database down"));

        assertDoesNotThrow(() -> service.failed(Channel.NOTIFICATION, WORK_ORDER, AuditSource.API,
                new RuntimeException()));
        assertDoesNotThrow(() -> service.failed(Channel.NOTIFICATION, WORK_ORDER, AuditSource.API,
                new RuntimeException()));

        verifyRecorded(WORK_ORDER, AuditAction.NOTIFICATION_FAILED, 2);
        verify(auditLogService, never()).record(any(), any(), eq(AuditAction.NOTIFICATION_DISCARDED), any(), any(),
                eq(0));
    }
}
