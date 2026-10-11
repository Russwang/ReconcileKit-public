package dev.reconcilekit.transaction;

import org.springframework.stereotype.Component;

/** Tests replace this bean with an explicit @Primary observer in src/test only. */
@Component
public class NoopTransactionObserver implements TransactionObserver {}
