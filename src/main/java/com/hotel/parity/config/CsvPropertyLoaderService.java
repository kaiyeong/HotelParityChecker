package com.hotel.parity.config;

import com.hotel.parity.model.HotelProperty;
import com.opencsv.CSVReaderHeaderAware;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads and validates target hotel properties from the configured 15-column CSV file.
 */
public class CsvPropertyLoaderService {

    private static final Logger log = LoggerFactory.getLogger(CsvPropertyLoaderService.class);

    public List<HotelProperty> loadProperties(String csvFilePath) throws IOException {
        if (csvFilePath == null || csvFilePath.trim().isEmpty()) {
            throw new IllegalArgumentException("CSV file path must not be null or empty.");
        }
        File file = new File(csvFilePath.trim());
        if (!file.exists() || !file.isFile()) {
            throw new IOException("Property CSV file not found at: " + file.getAbsolutePath());
        }
        log.info("Loading hotel properties from CSV: {}", file.getAbsolutePath());
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            return loadProperties(reader);
        }
    }

    public List<HotelProperty> loadProperties(Reader reader) throws IOException {
        List<HotelProperty> properties = new ArrayList<HotelProperty>();
        try (CSVReaderHeaderAware csvReader = new CSVReaderHeaderAware(reader)) {
            Map<String, String> row;
            int rowNumber = 1;
            while ((row = csvReader.readMap()) != null) {
                rowNumber++;
                String id = cleanString(row.get("id"));
                String name = cleanString(row.get("name"));
                if (id == null || name == null) {
                    log.warn("Skipping CSV row {} due to missing required 'id' or 'name'.", rowNumber);
                    continue;
                }

                HotelProperty property = new HotelProperty();
                property.setId(id);
                property.setName(name);
                property.setAddr1(cleanString(row.get("addr1")));
                property.setAddr2(cleanString(row.get("addr2")));
                property.setCity(cleanString(row.get("city")));
                property.setProvince(cleanString(row.get("province")));
                property.setPostalCode(cleanString(row.get("postal_code")));
                property.setCountry(cleanString(row.get("country")));
                property.setLatitude(parseNullableDouble(row.get("latitude"), "latitude", rowNumber));
                property.setLongitude(parseNullableDouble(row.get("longitude"), "longitude", rowNumber));
                property.setCategory(cleanString(row.get("category")));
                property.setHotelBrand(cleanString(row.get("hotel_brand")));
                property.setPhone(cleanString(row.get("phone")));
                property.setStarRating(parseNullableDouble(row.get("star_rating"), "star_rating", rowNumber));
                property.setAlternateHotelId(cleanString(row.get("alternate_hotel_id")));

                properties.add(property);
            }
        } catch (CsvValidationException e) {
            throw new IOException("Invalid CSV format: " + e.getMessage(), e);
        }
        log.info("Successfully loaded {} hotel properties from CSV.", properties.size());
        return properties;
    }

    private String cleanString(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private Double parseNullableDouble(String raw, String columnName, int rowNumber) {
        String cleaned = cleanString(raw);
        if (cleaned == null) {
            return null;
        }
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            log.warn("Invalid numeric value '{}' for column '{}' at CSV row {}; treating as null.",
                    cleaned, columnName, rowNumber);
            return null;
        }
    }
}
