package com.hotel.parity;

import com.hotel.parity.admin.AdminWebServer;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.scheduler.WeeklyParityJob;
import com.hotel.parity.service.PidResolutionRunner;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Entry point for the Multi-Property Hotel Rate Parity Monitoring System.
 * Supports scheduled weekly execution via Quartz, immediate manual execution via --run-now,
 * and manual Google Hotel PID resolution/caching via --resolve-pids.
 */
public class MainApplication {

    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);

    public static void main(String[] args) {
        AppConfig config = new AppConfig();

        boolean runNow = false;
        boolean resolvePids = false;
        boolean adminOnly = false;
        boolean forceRefresh = false;
        String propertyFilter = null;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--run-now".equalsIgnoreCase(arg)) {
                    runNow = true;
                } else if ("--resolve-pids".equalsIgnoreCase(arg) || "--pull-pids".equalsIgnoreCase(arg)) {
                    resolvePids = true;
                } else if ("--admin-only".equalsIgnoreCase(arg)) {
                    adminOnly = true;
                } else if ("--refresh".equalsIgnoreCase(arg) || "--force".equalsIgnoreCase(arg)) {
                    forceRefresh = true;
                } else if (arg != null && arg.toLowerCase().startsWith("--property=")) {
                    propertyFilter = arg.substring("--property=".length()).trim();
                } else if ("--property".equalsIgnoreCase(arg) && i + 1 < args.length) {
                    propertyFilter = args[++i].trim();
                }
            }
        }

        if (resolvePids) {
            log.info("Manual PID resolution triggered via --resolve-pids flag (property={}, refresh={}).",
                    propertyFilter != null ? propertyFilter : "ALL", forceRefresh);
            PidResolutionRunner.execute(config, propertyFilter, forceRefresh);
            return;
        }

        if (adminOnly) {
            startAdminServerIfEnabled(config, true);
            return;
        }

        if (runNow) {
            log.info("Manual execution triggered via --run-now flag.");
            WeeklyParityJob job = new WeeklyParityJob();
            job.runParityCheck(config);
            return;
        }

        startAdminServerIfEnabled(config, false);
        startWeeklyScheduler(config);
    }

    private static void startAdminServerIfEnabled(AppConfig config, boolean forceStart) {
        if (!forceStart && !config.isAdminServerEnabled()) {
            return;
        }
        try {
            final AdminWebServer adminServer = new AdminWebServer(config);
            adminServer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    adminServer.stop();
                }
            }));
        } catch (IOException e) {
            log.error("Failed to start Admin Web UI: {}", e.getMessage(), e);
            if (forceStart) {
                System.exit(1);
            }
        }
    }

    private static void startWeeklyScheduler(AppConfig config) {
        String cronExpr = config.getWeeklyCronExpression();
        try {
            final Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

            JobDetail jobDetail = JobBuilder.newJob(WeeklyParityJob.class)
                    .withIdentity("weeklyParityJob", "parityGroup")
                    .build();

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("weeklyParityTrigger", "parityGroup")
                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpr))
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            scheduler.start();

            log.info("Hotel Rate Parity Scheduler started with cron expression: '{}'", cronExpr);

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!scheduler.isShutdown()) {
                            log.info("Shutting down Hotel Rate Parity Scheduler...");
                            scheduler.shutdown(true);
                        }
                    } catch (SchedulerException e) {
                        log.warn("Error shutting down scheduler: {}", e.getMessage());
                    }
                }
            }));
        } catch (SchedulerException e) {
            log.error("Failed to start Quartz scheduler with cron '{}': {}", cronExpr, e.getMessage(), e);
            System.exit(1);
        }
    }
}
