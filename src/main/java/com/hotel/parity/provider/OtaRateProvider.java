package com.hotel.parity.provider;

import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Fetches OTA room rates for a given hotel property and check-in date.
 */
public interface OtaRateProvider {

    /**
     * Human-readable provider identifier (e.g., "SerpApi", "DataForSEO", "Makcorps", "Xotelo").
     */
    String getProviderName();

    /**
     * Fetches OTA rates for a 1-night stay starting on {@code checkInDate}.
     *
     * @param property    target hotel property
     * @param checkInDate check-in date (check-out is checkInDate + 1 day)
     * @return non-empty list of OTA rates, or empty list if unavailable
     */
    List<DailyRate> fetchOtaRates(HotelProperty property, LocalDate checkInDate);
}
