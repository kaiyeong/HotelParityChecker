package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.config.CsvPropertyLoaderService;
import com.hotel.parity.config.OkHttpClientFactory;
import com.hotel.parity.model.HotelProperty;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI runner that pulls and caches Google Hotels property tokens (PIDs) into provider-id-cache.json
 * and prints direct Google Hotels entity URLs for manual verification.
 */
public class PidResolutionRunner {

    private static final Logger log = LoggerFactory.getLogger(PidResolutionRunner.class);
    private static final String GOOGLE_HOTEL_ENTITY_URL_PREFIX = "https://www.google.com/travel/hotels/entity/";

    private final AppConfig config;
    private final CsvPropertyLoaderService csvLoader;
    private final ProviderIdResolverService idResolver;

    public PidResolutionRunner(AppConfig config,
                               CsvPropertyLoaderService csvLoader,
                               ProviderIdResolverService idResolver) {
        this.config = config;
        this.csvLoader = csvLoader;
        this.idResolver = idResolver;
    }

    public static void execute(AppConfig config, String propertyFilter, boolean forceRefresh) {
        OkHttpClient httpClient = OkHttpClientFactory.create(config);
        try {
            PidResolutionRunner runner = new PidResolutionRunner(
                    config,
                    new CsvPropertyLoaderService(),
                    new ProviderIdResolverService(config, httpClient));
            runner.run(propertyFilter, forceRefresh, System.out);
        } finally {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    public List<PidResolutionResult> run(String propertyFilter, boolean forceRefresh, PrintStream out) {
        List<HotelProperty> properties;
        try {
            properties = csvLoader.loadProperties(config.getPropertiesCsvPath());
        } catch (Exception e) {
            log.error("Failed to load property CSV from {}: {}", config.getPropertiesCsvPath(), e.getMessage(), e);
            if (out != null) {
                out.println("ERROR: Failed to load property CSV from " + config.getPropertiesCsvPath() + ": " + e.getMessage());
            }
            return new ArrayList<PidResolutionResult>();
        }

        List<HotelProperty> targets = filterProperties(properties, propertyFilter);
        if (targets.isEmpty()) {
            String msg = propertyFilter != null && !propertyFilter.trim().isEmpty()
                    ? "No property found matching ID '" + propertyFilter.trim() + "' in " + config.getPropertiesCsvPath()
                    : "No properties found in " + config.getPropertiesCsvPath();
            log.warn(msg);
            if (out != null) {
                out.println(msg);
            }
            return new ArrayList<PidResolutionResult>();
        }

        List<PidResolutionResult> results = new ArrayList<PidResolutionResult>();
        int resolvedCount = 0;
        int cachedCount = 0;
        int missingCount = 0;

        if (out != null) {
            out.println("=== Google Hotels PID Resolution ===");
            out.println("CSV:   " + config.getPropertiesCsvPath());
            out.println("Cache: " + config.getProviderIdCachePath());
            out.println("Mode:  " + (forceRefresh ? "Force refresh (SerpApi -> SearchApi)" : "Use cache if present, otherwise resolve (SerpApi -> SearchApi)"));
            out.println();
        }

        for (HotelProperty property : targets) {
            String existing = idResolver.getGooglePropertyToken(property);
            boolean wasCached = !forceRefresh && existing != null && !existing.isEmpty();

            String pid = idResolver.resolveGooglePropertyToken(property, forceRefresh);
            String status;
            if (pid == null || pid.trim().isEmpty()) {
                status = "MISSING";
                missingCount++;
            } else if (wasCached) {
                status = "CACHED";
                cachedCount++;
            } else {
                status = "RESOLVED";
                resolvedCount++;
            }

            String url = (pid != null && !pid.trim().isEmpty())
                    ? GOOGLE_HOTEL_ENTITY_URL_PREFIX + pid.trim()
                    : "-";
            results.add(new PidResolutionResult(property.getId(), property.getName(), pid, status, url));

            if (out != null) {
                out.printf("%-8s | %-38s | %-8s | %s%n",
                        property.getId(),
                        truncate(property.getName(), 38),
                        status,
                        pid != null ? pid : "NOT FOUND");
                out.printf("         Verify: %s%n", url);
            }
        }

        if (out != null) {
            out.println();
            out.printf("Summary: %d total (%d cached, %d newly resolved, %d missing)%n",
                    targets.size(), cachedCount, resolvedCount, missingCount);
        }
        log.info("PID resolution finished: {} total ({} cached, {} newly resolved, {} missing)",
                targets.size(), cachedCount, resolvedCount, missingCount);
        return results;
    }

    private List<HotelProperty> filterProperties(List<HotelProperty> properties, String propertyFilter) {
        if (propertyFilter == null || propertyFilter.trim().isEmpty()) {
            return properties;
        }
        String target = propertyFilter.trim();
        List<HotelProperty> filtered = new ArrayList<HotelProperty>();
        for (HotelProperty property : properties) {
            if (property.getId() != null && target.equalsIgnoreCase(property.getId().trim())) {
                filtered.add(property);
            }
        }
        return filtered;
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen - 3) + "...";
    }

    public static final class PidResolutionResult {
        private final String propertyId;
        private final String propertyName;
        private final String pid;
        private final String status;
        private final String verifyUrl;

        public PidResolutionResult(String propertyId, String propertyName, String pid, String status, String verifyUrl) {
            this.propertyId = propertyId;
            this.propertyName = propertyName;
            this.pid = pid;
            this.status = status;
            this.verifyUrl = verifyUrl;
        }

        public String getPropertyId() {
            return propertyId;
        }

        public String getPropertyName() {
            return propertyName;
        }

        public String getPid() {
            return pid;
        }

        public String getStatus() {
            return status;
        }

        public String getVerifyUrl() {
            return verifyUrl;
        }
    }
}
