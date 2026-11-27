package com.hotel.parity.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Loads and provides typed access to application.properties configuration.
 */
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final Properties properties;

    public AppConfig() {
        this.properties = new Properties();
        loadProperties(System.getProperty("config.file"));
    }

    public AppConfig(String configFilePath) {
        this.properties = new Properties();
        loadProperties(configFilePath);
    }

    public AppConfig(Properties properties) {
        this.properties = new Properties();
        if (properties != null) {
            this.properties.putAll(properties);
        }
    }

    private void loadProperties(String externalPath) {
        // 1. Load classpath defaults first
        try (InputStream classpathStream = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (classpathStream != null) {
                properties.load(classpathStream);
                log.debug("Loaded default application.properties from classpath.");
            }
        } catch (IOException e) {
            log.warn("Failed to load application.properties from classpath: {}", e.getMessage());
        }

        // 2. Override with external file if specified or present in working directory
        File externalFile = null;
        if (externalPath != null && !externalPath.trim().isEmpty()) {
            externalFile = new File(externalPath.trim());
        } else {
            File localFile = new File("application.properties");
            if (localFile.exists() && localFile.isFile()) {
                externalFile = localFile;
            }
        }

        if (externalFile != null && externalFile.exists()) {
            try (InputStream fis = new FileInputStream(externalFile)) {
                properties.load(fis);
                log.info("Loaded configuration overrides from {}", externalFile.getAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read config file: " + externalFile.getAbsolutePath(), e);
            }
        }
    }

    public String getString(String key, String defaultValue) {
        String val = properties.getProperty(key);
        return val != null ? val.trim() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String val = properties.getProperty(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        return Integer.parseInt(val.trim());
    }

    public long getLong(String key, long defaultValue) {
        String val = properties.getProperty(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        return Long.parseLong(val.trim());
    }

    public double getDouble(String key, double defaultValue) {
        String val = properties.getProperty(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        return Double.parseDouble(val.trim());
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = properties.getProperty(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(val.trim());
    }

    public String getPropertiesCsvPath() {
        return getString("properties.csv.path", "config/google-hotel-list.csv");
    }

    public String getProviderIdCachePath() {
        return getString("provider.id.cache.path", "config/provider-id-cache.json");
    }

    public String getWeeklyCronExpression() {
        return getString("weekly.cron.expression", "0 0 8 ? * MON");
    }

    public int getMonitoringDaysWindow() {
        return getInt("monitoring.days.window", 90);
    }

    public int getMonitoringStartOffsetDays() {
        return getInt("monitoring.start.offset.days", 7);
    }

    public double getParityBreachThreshold() {
        return getDouble("parity.breach.threshold", 1.00);
    }

    public int getThreadPoolSize() {
        return getInt("thread.pool.size", 5);
    }

    public long getRateLimitDelayMs() {
        return getLong("rate.limit.delay.ms", 250L);
    }

    public String getDirectWebsiteApiBaseUrl() {
        return getString("direct.website.api.base.url", "");
    }

    public String getDirectWebsiteApiKey() {
        return getString("direct.website.api.key", "");
    }

    public boolean isDirectWebsiteMockEnabled() {
        return getBoolean("direct.website.mock.enabled", true);
    }

    public String getBrightDataApiUrl() {
        return getString("brightdata.api.url", "https://api.brightdata.com/request");
    }

    public String getBrightDataApiKey() {
        return getString("brightdata.api.key", "");
    }

    public String getBrightDataZone() {
        return getString("brightdata.zone", "");
    }

    public String getSearchApiBaseUrl() {
        return getString("searchapi.base.url", "https://www.searchapi.io/api/v1/search");
    }

    public String getSearchApiKey() {
        return getString("searchapi.api.key", "");
    }

    public String getSerpApiBaseUrl() {
        return getString("serpapi.base.url", "https://serpapi.com/search.json");
    }

    public String getSerpApiKey() {
        return getString("serpapi.api.key", "");
    }

    public String getDataForSeoBaseUrl() {
        return getString("dataforseo.base.url", "https://api.dataforseo.com/v3");
    }

    public String getDataForSeoUsername() {
        return getString("dataforseo.username", "");
    }

    public String getDataForSeoPassword() {
        return getString("dataforseo.password", "");
    }

    public String getDataForSeoQueueMode() {
        return getString("dataforseo.queue.mode", "live");
    }

    public long getDataForSeoQueuePollIntervalMs() {
        return getLong("dataforseo.queue.poll.interval.ms", 3000L);
    }

    public int getDataForSeoQueueTimeoutSeconds() {
        return getInt("dataforseo.queue.timeout.seconds", 3000);
    }

    public String getMakcorpsBaseUrl() {
        return getString("makcorps.base.url", "https://api.makcorps.com");
    }

    public String getMakcorpsApiKey() {
        return getString("makcorps.api.key", "");
    }

    public String getXoteloBaseUrl() {
        return getString("xotelo.base.url", "https://data.xotelo.com");
    }

    public String getXoteloRapidApiKey() {
        return getString("xotelo.rapidapi.key", "");
    }

    public String getXoteloRapidApiHost() {
        return getString("xotelo.rapidapi.host", "xotelo-hotel-prices.p.rapidapi.com");
    }

    public int getHttpConnectTimeoutSeconds() {
        return getInt("http.connect.timeout.seconds", 15);
    }

    public int getHttpReadTimeoutSeconds() {
        return getInt("http.read.timeout.seconds", 30);
    }

    public int getHttpWriteTimeoutSeconds() {
        return getInt("http.write.timeout.seconds", 15);
    }

    public int getHttpMaxRetries() {
        return getInt("http.max.retries", 3);
    }

    public String getSmtpHost() {
        return getString("smtp.host", "localhost");
    }

    public int getSmtpPort() {
        return getInt("smtp.port", 587);
    }

    public String getSmtpUsername() {
        return getString("smtp.username", "");
    }

    public String getSmtpPassword() {
        return getString("smtp.password", "");
    }

    public boolean isSmtpAuth() {
        return getBoolean("smtp.auth", true);
    }

    public boolean isSmtpStartTls() {
        return getBoolean("smtp.starttls", true);
    }

    public String getSmtpFrom() {
        return getString("smtp.from", "alerts@hotelparity.example.com");
    }

    /**
     * Resolves per-property Revenue Manager email override (rm.email.<ID>) with global fallback (rm.email.default).
     */
    public String getRmEmailForProperty(String propertyId) {
        String override = getRmEmailOverride(propertyId);
        if (override != null) {
            return override;
        }
        return getDefaultRmEmails();
    }

    public String getRmEmailOverride(String propertyId) {
        if (propertyId != null && !propertyId.trim().isEmpty()) {
            String override = normalizeEmailList(properties.getProperty("rm.email." + propertyId.trim()));
            if (override != null) {
                return override;
            }
        }
        return null;
    }

    public String getDefaultRmEmails() {
        String defaults = normalizeEmailList(getString("rm.email.default", "revenue@company.com"));
        return defaults != null ? defaults : "revenue@company.com";
    }

    private static String normalizeEmailList(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(trimmed);
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    public boolean isEmailSendSummaryWhenNoBreach() {
        return getBoolean("email.send.summary.when.no.breach", false);
    }

    public boolean isEmailDryRun() {
        return getBoolean("email.dry.run", false);
    }

    public boolean isDebugRawApiEnabled() {
        return getBoolean("debug.raw.api.enabled", true);
    }

    public String getDebugRawApiDir() {
        return getString("debug.raw.api.dir", "logs/raw-api");
    }

    public String getDashboardOutputPath() {
        return getString("dashboard.output.path", "reports/parity-dashboard.html");
    }

    public boolean isAdminServerEnabled() {
        return getBoolean("admin.server.enabled", true);
    }

    public String getAdminServerHost() {
        return getString("admin.server.host", "0.0.0.0");
    }

    public int getAdminServerPort() {
        return getInt("admin.server.port", 8080);
    }

    public String getAdminServerContext() {
        String ctx = getString("admin.server.context", "/HotelPairityCheckerAdmin");
        if (!ctx.startsWith("/")) {
            ctx = "/" + ctx;
        }
        while (ctx.length() > 1 && ctx.endsWith("/")) {
            ctx = ctx.substring(0, ctx.length() - 1);
        }
        return ctx;
    }

    public String getAdminAuthUsername() {
        return getString("admin.auth.username", "admin");
    }

    public String getAdminAuthPassword() {
        return getString("admin.auth.password", "");
    }

    public boolean isFxApiEnabled() {
        return getBoolean("fx.api.enabled", true);
    }

    public String getFxApiUrl() {
        return getString("fx.api.url", "https://open.er-api.com/v6/latest/USD");
    }

    /**
     * Returns how many units of the given currency equal 1 USD.
     */
    public double getCurrencyPerUsd(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return 1.0;
        }
        String code = currency.trim().toUpperCase();
        return getDouble("fx.rate.USD." + code, 1.0);
    }
}
