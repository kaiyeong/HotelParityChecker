package com.hotel.parity.provider;

import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Executes the 4-tier cascading OTA fallback chain (SerpApi -> DataForSEO -> Makcorps -> Xotelo)
 * and fetches the 90-day window concurrently using a bounded ExecutorService with rate-limit throttling.
 */
public class CascadingOtaRateService {

    private static final Logger log = LoggerFactory.getLogger(CascadingOtaRateService.class);

    private final List<OtaRateProvider> providers;
    private final int threadPoolSize;
    private final long rateLimitDelayMs;
    private final Set<String> failedProviders;

    public CascadingOtaRateService(List<OtaRateProvider> providers,
                                   int threadPoolSize,
                                   long rateLimitDelayMs) {
        this.providers = providers != null
                ? new ArrayList<OtaRateProvider>(providers)
                : Collections.<OtaRateProvider>emptyList();
        this.threadPoolSize = Math.max(1, threadPoolSize);
        this.rateLimitDelayMs = Math.max(0L, rateLimitDelayMs);
        this.failedProviders = ConcurrentHashMap.newKeySet();
    }

    /**
     * Fetches OTA rates for a single check-in date using the cascading fallback chain.
     * Once a provider fails (throws an exception), it is disabled for the remainder of the run.
     */
    public List<DailyRate> fetchOtaRatesForDate(HotelProperty property, LocalDate checkInDate) {
        for (int i = 0; i < providers.size(); i++) {
            OtaRateProvider provider = providers.get(i);
            String providerName = provider.getProviderName();
            if (failedProviders.contains(providerName)) {
                log.debug("Skipping disabled provider {} for {} on {}", providerName, property.getId(), checkInDate);
                continue;
            }
            try {
                List<DailyRate> rates = provider.fetchOtaRates(property, checkInDate);
                if (rates != null && !rates.isEmpty()) {
                    log.debug("Fetched {} OTA rates for {} on {} via {}",
                            rates.size(), property.getId(), checkInDate, providerName);
                    return rates;
                }
                log.warn("Provider {} returned empty OTA rates for {} on {}; falling back to next tier.",
                        providerName, property.getId(), checkInDate);
            } catch (Exception ex) {
                if (failedProviders.add(providerName)) {
                    log.warn("Provider {} failed for {} on {}: {}; disabling {} for the remainder of this run.",
                            providerName, property.getId(), checkInDate, ex.getMessage(), providerName);
                } else {
                    log.debug("Provider {} already disabled ({}); falling back to next tier.",
                            providerName, ex.getMessage());
                }
            }
        }
        log.error("All {} OTA providers failed or returned empty results for {} on {}.",
                providers.size(), property.getId(), checkInDate);
        return Collections.emptyList();
    }

    /**
     * Fetches OTA rates across all days in the rolling window concurrently.
     */
    public Map<LocalDate, List<DailyRate>> fetchOtaRatesForWindow(final HotelProperty property,
                                                                  LocalDate startDate,
                                                                  int days) {
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        Map<LocalDate, Future<List<DailyRate>>> futures = new TreeMap<LocalDate, Future<List<DailyRate>>>();

        try {
            for (int i = 0; i < days; i++) {
                final LocalDate checkInDate = startDate.plusDays(i);
                Future<List<DailyRate>> future = executor.submit(new Callable<List<DailyRate>>() {
                    @Override
                    public List<DailyRate> call() throws Exception {
                        if (rateLimitDelayMs > 0) {
                            Thread.sleep(rateLimitDelayMs);
                        }
                        return fetchOtaRatesForDate(property, checkInDate);
                    }
                });
                futures.put(checkInDate, future);
            }

            Map<LocalDate, List<DailyRate>> results = new TreeMap<LocalDate, List<DailyRate>>();
            for (Map.Entry<LocalDate, Future<List<DailyRate>>> entry : futures.entrySet()) {
                LocalDate date = entry.getKey();
                try {
                    results.put(date, entry.getValue().get());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while fetching OTA rates for {} on {}", property.getId(), date);
                    results.put(date, Collections.<DailyRate>emptyList());
                } catch (ExecutionException ee) {
                    log.warn("Execution error fetching OTA rates for {} on {}: {}",
                            property.getId(), date, ee.getMessage());
                    results.put(date, Collections.<DailyRate>emptyList());
                }
            }
            return results;
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
