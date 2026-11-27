package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Persists raw JSON payloads returned by external hotel/OTA APIs for debugging and auditing.
 */
public class RawApiDataService {

    private static final Logger log = LoggerFactory.getLogger(RawApiDataService.class);

    private final boolean enabled;
    private final File baseDir;
    private final String runDateFolder;

    public RawApiDataService(AppConfig config) {
        this.enabled = config.isDebugRawApiEnabled();
        this.baseDir = new File(config.getDebugRawApiDir());
        this.runDateFolder = LocalDate.now().toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void saveRawResponse(String providerName,
                                String propertyId,
                                LocalDate checkInDate,
                                String tag,
                                String rawPayload) {
        if (!enabled || rawPayload == null) {
            return;
        }
        String safeProvider = sanitize(providerName);
        String safeProperty = sanitize(propertyId);
        String datePart = checkInDate != null ? checkInDate.toString() : "window";
        String suffix = (tag != null && !tag.trim().isEmpty()) ? ("_" + sanitize(tag)) : "";

        File propertyDir = new File(new File(baseDir, runDateFolder), safeProperty);
        if (!propertyDir.exists() && !propertyDir.mkdirs()) {
            log.warn("Could not create raw API debug directory: {}", propertyDir.getAbsolutePath());
            return;
        }

        File outFile = new File(propertyDir, safeProvider + "_" + datePart + suffix + ".json");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(rawPayload.getBytes(StandardCharsets.UTF_8));
            log.debug("Saved raw API payload ({} bytes) to {}", rawPayload.length(), outFile.getPath());
        } catch (IOException e) {
            log.warn("Failed to write raw API payload to {}: {}", outFile.getPath(), e.getMessage());
        }
    }

    private String sanitize(String input) {
        if (input == null || input.trim().isEmpty()) {
            return "unknown";
        }
        return input.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
