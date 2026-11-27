package com.hotel.parity.scheduler;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.config.CsvPropertyLoaderService;
import com.hotel.parity.config.OkHttpClientFactory;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import com.hotel.parity.provider.BrightDataOtaProvider;
import com.hotel.parity.provider.CascadingOtaRateService;
import com.hotel.parity.provider.DataForSeoOtaProvider;
import com.hotel.parity.provider.DirectHotelWebsiteProvider;
import com.hotel.parity.provider.MakcorpsOtaProvider;
import com.hotel.parity.provider.OtaRateProvider;
import com.hotel.parity.provider.SearchApiOtaProvider;
import com.hotel.parity.provider.SerpApiOtaProvider;
import com.hotel.parity.provider.WebsiteRateProvider;
import com.hotel.parity.service.ChartGeneratorService;
import com.hotel.parity.service.DashboardGeneratorService;
import com.hotel.parity.service.EmailNotificationService;
import com.hotel.parity.service.ExchangeRateService;
import com.hotel.parity.service.ParityAnalyzerService;
import com.hotel.parity.service.ProviderIdResolverService;
import com.hotel.parity.service.RawApiDataService;
import com.hotel.parity.service.ReportExportService;
import okhttp3.OkHttpClient;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the weekly rate parity monitoring workflow across all configured hotel properties.
 */
public class WeeklyParityJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(WeeklyParityJob.class);

    public WeeklyParityJob() {
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            AppConfig config = new AppConfig();
            runParityCheck(config);
        } catch (Exception e) {
            log.error("WeeklyParityJob failed: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }

    public void runParityCheck(AppConfig config) {
        long startMs = System.currentTimeMillis();
        LocalDate startDate = LocalDate.now().plusDays(config.getMonitoringStartOffsetDays());
        int daysWindow = config.getMonitoringDaysWindow();

        log.info("Starting Hotel Rate Parity Check for {} days starting {}...", daysWindow, startDate);

        CsvPropertyLoaderService csvLoader = new CsvPropertyLoaderService();
        List<HotelProperty> properties;
        try {
            properties = csvLoader.loadProperties(config.getPropertiesCsvPath());
        } catch (Exception e) {
            log.error("Failed to load property CSV from {}: {}", config.getPropertiesCsvPath(), e.getMessage(), e);
            return;
        }

        if (properties.isEmpty()) {
            log.warn("No properties found in CSV {}. Nothing to process.", config.getPropertiesCsvPath());
            return;
        }

        OkHttpClient httpClient = OkHttpClientFactory.create(config);
        Map<HotelProperty, List<ParityComparisonResult>> allResults = new LinkedHashMap<HotelProperty, List<ParityComparisonResult>>();
        try {
            RawApiDataService rawApiDataService = new RawApiDataService(config);
            ProviderIdResolverService idResolver = new ProviderIdResolverService(config, httpClient);
            boolean useDirectApi = config.getDirectWebsiteApiBaseUrl() != null
                    && !config.getDirectWebsiteApiBaseUrl().isEmpty()
                    && !config.getDirectWebsiteApiBaseUrl().contains("example.com");
            WebsiteRateProvider websiteProvider = (useDirectApi || config.isDirectWebsiteMockEnabled())
                    ? new DirectHotelWebsiteProvider(config, httpClient, rawApiDataService)
                    : null;
            if (websiteProvider == null) {
                log.info("Direct booking engine API disabled; using official website rates directly from OTA/Google Hotels APIs.");
            }

            List<OtaRateProvider> otaProviders = Arrays.<OtaRateProvider>asList(
                    // new BrightDataOtaProvider(config, httpClient, rawApiDataService, idResolver),
                    // new SearchApiOtaProvider(config, httpClient, rawApiDataService, idResolver),
                    // new SerpApiOtaProvider(config, httpClient, rawApiDataService, idResolver),
                    new DataForSeoOtaProvider(config, httpClient, rawApiDataService, idResolver)
                    // new MakcorpsOtaProvider(config, httpClient, idResolver, rawApiDataService)
            );

            CascadingOtaRateService otaService = new CascadingOtaRateService(
                    otaProviders, config.getThreadPoolSize(), config.getRateLimitDelayMs());
            ExchangeRateService exchangeRateService = new ExchangeRateService(config, httpClient);
            ParityAnalyzerService analyzerService = new ParityAnalyzerService(config, exchangeRateService);
            ChartGeneratorService chartService = new ChartGeneratorService();
            ReportExportService reportService = new ReportExportService();
            EmailNotificationService emailService = new EmailNotificationService(config, idResolver);

            for (HotelProperty property : properties) {
                List<ParityComparisonResult> comparisons = processProperty(
                        property,
                        startDate,
                        daysWindow,
                        config,
                        idResolver,
                        websiteProvider,
                        otaService,
                        analyzerService,
                        chartService,
                        reportService,
                        emailService
                );
                if (comparisons != null) {
                    allResults.put(property, comparisons);
                }
            }

            try {
                new DashboardGeneratorService().generateDashboard(config.getDashboardOutputPath(), allResults, idResolver);
            } catch (Exception e) {
                log.error("Failed to generate parity dashboard: {}", e.getMessage(), e);
            }
        } finally {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }

        long elapsedSec = (System.currentTimeMillis() - startMs) / 1000L;
        log.info("Completed Hotel Rate Parity Check for {} properties in {}s.", properties.size(), elapsedSec);
    }

    private List<ParityComparisonResult> processProperty(HotelProperty property,
                                                         LocalDate startDate,
                                                         int daysWindow,
                                                         AppConfig config,
                                                         ProviderIdResolverService idResolver,
                                                         WebsiteRateProvider websiteProvider,
                                                         CascadingOtaRateService otaService,
                                                         ParityAnalyzerService analyzerService,
                                                         ChartGeneratorService chartService,
                                                         ReportExportService reportService,
                                                         EmailNotificationService emailService) {
        log.info("Processing property {} ({})", property.getName(), property.getId());
        try {
            if (idResolver != null) {
                idResolver.resolveGooglePropertyToken(property);
            }
            List<DailyRate> directRates = websiteProvider != null
                    ? websiteProvider.fetchDirectRates(property, startDate, daysWindow)
                    : Collections.<DailyRate>emptyList();
            Map<LocalDate, List<DailyRate>> otaRates = otaService.fetchOtaRatesForWindow(property, startDate, daysWindow);

            List<ParityComparisonResult> comparisons = analyzerService.analyzeParity(
                    property, directRates, otaRates, startDate, daysWindow);

            int breachedCount = 0;
            for (ParityComparisonResult r : comparisons) {
                if (r.isBreached()) {
                    breachedCount++;
                }
            }

            log.info("Property {} ({}): {} breached days out of {} days checked.",
                    property.getName(), property.getId(), breachedCount, comparisons.size());

            if (breachedCount > 0 || config.isEmailSendSummaryWhenNoBreach()) {
                byte[] chartPng = chartService.generateParityChartPng(property, comparisons, daysWindow);
                byte[] csvReport = reportService.generateCsvReport(property, comparisons);
                String htmlBody = reportService.buildHtmlEmailBody(property, comparisons, daysWindow);
                emailService.sendParityAlert(property, breachedCount, htmlBody, chartPng, csvReport);
            }
            return comparisons;
        } catch (Exception e) {
            log.error("Failed processing property {} ({}): {}", property.getName(), property.getId(), e.getMessage(), e);
            return null;
        }
    }
}
