package com.potlatech.pharmacy.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.potlatech.pharmacy.service.InventoryBatchService;

import java.util.List;
import java.util.UUID;

/**
 * Writes off stock past its expiry date.
 *
 * <p><strong>Disabled by default.</strong> A sweep that runs against a database
 * holding real batches, on a developer's machine, because a property default was
 * wrong, is a data-destroying bug that is very hard to attribute afterwards.
 * Production opts in explicitly. There is no safe default for an automated write
 * that is irreversible.
 *
 * <p><strong>One transaction per batch, via {@code REQUIRES_NEW} on the
 * collaborator</strong> — not one for the whole sweep. A single transaction across
 * the working set means one bad row rolls back every other write-off, and because
 * the sweep is deterministic, the next run fails identically: a poison row that
 * silently stops the entire pharmacy's expiry reporting forever. Per-batch
 * isolation means a poison row costs exactly itself, and the sweep's own log line
 * says how many succeeded.
 *
 * <p>That is also why the orchestrating loop is in this class and the transaction
 * boundary is not: a {@code @Transactional} method calling another
 * {@code @Transactional} method on <em>itself</em> joins the outer transaction
 * (self-invocation bypasses the proxy), so annotating this class's method would
 * give one big transaction and no isolation at all — the exact behaviour the design
 * is trying to avoid, arrived at by accident and looking correct in review.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.expiry-sweep.enabled", havingValue = "true")
public class ExpirySweepJob {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepJob.class);

    private final InventoryBatchService inventory;

    public ExpirySweepJob(InventoryBatchService inventory) {
        this.inventory = inventory;
    }

    /**
     * Runs at 02:15 rather than on the hour.
     *
     * <p>Every deployment of every service on the planet fires at midnight and
     * 02:00. A pharmacy's stock write-off is a handful of rows and has no business
     * competing for the database with a hundred other jobs at exactly 00:00.
     */
    @Scheduled(cron = "${app.jobs.expiry-sweep.cron:0 15 2 * * *}")
    public void sweep() {
        List<UUID> candidates = inventory.findExpirable();
        if (candidates.isEmpty()) {
            log.debug("Expiry sweep found nothing to write off");
            return;
        }

        int written = 0;
        int failed = 0;
        for (UUID id : candidates) {
            try {
                // Each call is its own REQUIRES_NEW transaction in the service.
                written += inventory.expireOne(id).size();
            } catch (RuntimeException e) {
                // One batch's failure must not stop the rest. Logged with the id
                // so the poison row is findable rather than just visible.
                failed++;
                log.error("Expiry sweep could not write off batch {}: {}", id, e.getMessage(), e);
            }
        }

        log.info("Expiry sweep wrote off {} of {} candidate batches ({} failed)",
                written, candidates.size(), failed);
    }
}
