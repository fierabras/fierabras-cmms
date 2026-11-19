package com.grash.utils;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * INC-02 (E-05): runs an external effect (notification, mail, webhook) only once the data it announces is committed.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Runs the action after the current transaction commits, and never if it rolls back. Without a transaction, runs
     * it at once.
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
