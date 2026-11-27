package com.hotel.parity.provider;

import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;

import java.time.LocalDate;
import java.util.List;

/**
 * Fetches direct booking engine rates from the hotel's own website.
 */
public interface WebsiteRateProvider {

    /**
     * Fetches daily room rates for the specified property over the given window.
     *
     * @param property  target hotel property (keyed by id / alternateHotelId)
     * @param startDate first check-in date
     * @param days      number of consecutive days to fetch (e.g. 90)
     * @return list of daily direct rates
     */
    List<DailyRate> fetchDirectRates(HotelProperty property, LocalDate startDate, int days);
}
