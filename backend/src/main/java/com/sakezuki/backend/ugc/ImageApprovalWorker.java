package com.sakezuki.backend.ugc;

import com.sakezuki.backend.image.ImageAssetService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.sql.SQLException;

@Component
class ImageApprovalWorker {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ImageApprovalWorker.class);
    private final ImageApprovalJobs jobs;
    private final ImageApprovalService approvals;
    private final ImageAssetService images;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private volatile boolean closing;

    ImageApprovalWorker(ImageApprovalJobs jobs, ImageApprovalService approvals, ImageAssetService images,
                        PlatformTransactionManager manager, @Value("${ugc.image-approval.enabled:false}") boolean enabled) {
        this.jobs = jobs;
        this.approvals = approvals;
        this.images = images;
        this.enabled = enabled;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(scheduler="imageApprovalScheduler",fixedDelayString="${ugc.image-approval.poll-ms:5000}",
            initialDelayString="${ugc.image-approval.poll-ms:5000}")
    void poll() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.warn("Image approval polling failed ({})",failure.getClass().getSimpleName());
        }
    }

    @EventListener(ContextClosedEvent.class)
    void stopClaiming() {
        closing = true;
    }

    void runOnce() {
        if (!enabled || closing) return;
        try (var permit = images.admit()) {
            if (closing) return;
            transaction.execute(status -> jobs.claim()).ifPresent(job -> process(permit,job));
        } catch (IOException busy) {
            // A busy JVM does not acquire a lease or consume an attempt.
        }
    }

    private void process(ImageAssetService.Processing permit, ImageApprovalJobs.Job job) {
        boolean publishing = false;
        try {
            var delivered = permit.promote(job.asset(),approvals.promotion(job));
            publishing = true;
            approvals.complete(job,delivered);
        } catch (ImageApprovalJobs.Superseded ignored) {
            // The job generation is already invalid; nothing to write.
        } catch (ImageAssetService.PromotionIo failure) {
            jobs.fail(job,failure.code(),failure.retryable());
        } catch (ImageAssetService.QuarantinedImageException failure) {
            jobs.fail(job,"DELIVERY_QUARANTINED",false);
        } catch (ImageAssetService.PromotionRejected failure) {
            jobs.fail(job,failure.code(),false);
        } catch (IOException failure) {
            jobs.fail(job,"STORAGE_IO",true);
        } catch (ResponseStatusException failure) {
            int code = failure.getStatusCode().value();
            jobs.fail(job,code==401 || code==403 ? "ADMIN_ACCESS_CHANGED" : "CATALOG_OR_SUBMISSION_CONFLICT",false);
        } catch (DataAccessException failure) {
            if (isLockContention(failure)) jobs.fail(job,"DB_LOCK_CONTENTION",true);
            else jobs.fail(job,publishing ? "DB_PUBLICATION_WRITE_REVIEW" : "DB_PROMOTION_WRITE_REVIEW",false);
        } catch (RuntimeException failure) {
            // If the prior commit actually succeeded, the PROCESSING/generation guard makes this a no-op.
            log.warn("Image approval outcome requires recovery ({})",failure.getClass().getSimpleName());
            jobs.fail(job,"PROCESSING_REVIEW",false);
        }
    }

    static boolean isLockContention(DataAccessException error) {
        for (Throwable cause=error; cause!=null; cause=cause.getCause()) {
            if (cause instanceof SQLException sql && (sql.getErrorCode()==1205 || sql.getErrorCode()==1213)) return true;
        }
        return false;
    }

    @Configuration(proxyBeanMethods=false)
    static class Schedule {
        @Bean(defaultCandidate=false)
        ThreadPoolTaskScheduler imageApprovalScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("image-approval-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(5);
            return scheduler;
        }
    }
}
