package com.hotel.parity.tools;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.config.CsvPropertyLoaderService;
import com.hotel.parity.config.OkHttpClientFactory;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.service.ProviderIdResolverService;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Standalone CLI utility to generate/update provider-id-cache.json by matching
 * properties from google-hotel-list.csv to Xotelo hotel_key (and Makcorps hotelid if configured).
 */
public class XoteloIdCacheGenerator {

    private static final Logger log = LoggerFactory.getLogger(XoteloIdCacheGenerator.class);

    public static void main(String[] args) {
        AppConfig config = new AppConfig();
        boolean forceRefresh = false;
        if (args != null) {
            for (String arg : args) {
                if ("--refresh".equalsIgnoreCase(arg)) {
                    forceRefresh = true;
                    break;
                }
            }
        }
        generateCache(config, forceRefresh);
    }

    public static void generateCache(AppConfig config) {
        generateCache(config, false);
    }

    public static void generateCache(AppConfig config, boolean forceRefresh) {
        CsvPropertyLoaderService csvLoader = new CsvPropertyLoaderService();
        List<HotelProperty> properties;
        try {
            properties = csvLoader.loadProperties(config.getPropertiesCsvPath());
        } catch (Exception e) {
            log.error("Failed to load CSV from {}: {}", config.getPropertiesCsvPath(), e.getMessage(), e);
            return;
        }

        if (properties.isEmpty()) {
            log.warn("No properties found in {}.", config.getPropertiesCsvPath());
            return;
        }

        OkHttpClient httpClient = OkHttpClientFactory.create(config);
        int xoteloResolved = 0;
        int xoteloMissing = 0;

        try {
            ProviderIdResolverService resolver = new ProviderIdResolverService(config, httpClient);
            long delayMs = config.getRateLimitDelayMs();

            for (HotelProperty property : properties) {
                resolver.ensureEntryInCache(property);

                String xoteloKey = resolver.resolveXoteloHotelKey(property, forceRefresh);
                if (xoteloKey != null && !xoteloKey.isEmpty()) {
                    xoteloResolved++;
                    log.info("[MATCHED] {} ({}) -> xoteloHotelKey={}",
                            property.getId(), property.getName(), xoteloKey);
                } else {
                    xoteloMissing++;
                    log.warn("[UNRESOLVED] {} ({}) -> xoteloHotelKey not found (query='{}')",
                            property.getId(), property.getName(), property.getSearchQuery());
                }

                // Optionally resolve Makcorps ID if API key is configured
                resolver.resolveMakcorpsHotelId(property);

                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            resolver.flushCache();
            log.info("Finished generating {}: total={}, xoteloResolved={}, xoteloUnresolved={}",
                    config.getProviderIdCachePath(), properties.size(), xoteloResolved, xoteloMissing);
        } finally {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}
