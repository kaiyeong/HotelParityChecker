package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ParityAnalyzerServiceTest {

    private AppConfig createTestConfig() {
        Properties props = new Properties();
        props.setProperty("parity.breach.threshold", "1.00");
        props.setProperty("fx.rate.USD.USD", "1.0");
        props.setProperty("fx.rate.USD.MYR", "4.40");
        return new AppConfig(props);
    }

    private HotelProperty createMalaysiaProperty() {
        HotelProperty p = new HotelProperty();
        p.setId("TBSR");
        p.setName("Tunamaya Beach & Spa Resort");
        p.setCountry("MY");
        return p;
    }

    @Test
    public void testDetectsBreachAndNormalizesCurrencyAndEnforcesBeforeTax() {
        AppConfig config = createTestConfig();
        ParityAnalyzerService analyzer = new ParityAnalyzerService(config);
        HotelProperty property = createMalaysiaProperty();

        LocalDate day1 = LocalDate.of(2026, 12, 1);
        LocalDate day2 = day1.plusDays(1);
        LocalDate day3 = day1.plusDays(2);

        List<DailyRate> directRates = Arrays.asList(
                new DailyRate(day1, "Direct Website", DailyRate.RateType.BEFORE_TAX, 450.0, "MYR", "Direct"),
                new DailyRate(day2, "Direct Website", DailyRate.RateType.BEFORE_TAX, 400.0, "MYR", "Direct"),
                new DailyRate(day3, "Direct Website", DailyRate.RateType.BEFORE_TAX, 420.0, "MYR", "Direct")
        );

        Map<LocalDate, List<DailyRate>> otaRatesByDate = new HashMap<LocalDate, List<DailyRate>>();

        // Day 1: Agoda is 420 MYR, Klook is 90 USD (= 396 MYR) -> Breached by 54.00 MYR
        otaRatesByDate.put(day1, Arrays.asList(
                new DailyRate(day1, "Agoda", DailyRate.RateType.BEFORE_TAX, 420.0, "MYR", "SerpApi"),
                new DailyRate(day1, "Klook", DailyRate.RateType.BEFORE_TAX, 90.0, "USD", "SerpApi")
        ));

        // Day 2: Booking.com BEFORE_TAX (399.50 MYR) vs Trip.com TOTAL (350 MYR) -> prefers BEFORE_TAX (gap 0.50 <= 1.00 -> NOT breached)
        otaRatesByDate.put(day2, Arrays.asList(
                new DailyRate(day2, "Booking.com", DailyRate.RateType.BEFORE_TAX, 399.50, "MYR", "DataForSEO"),
                new DailyRate(day2, "Trip.com", DailyRate.RateType.TOTAL, 350.0, "MYR", "DataForSEO")
        ));

        // Day 3: Only TOTAL rate available from OTA -> falls back to TOTAL when no BEFORE_TAX exists
        otaRatesByDate.put(day3, Collections.singletonList(
                new DailyRate(day3, "Trip.com", DailyRate.RateType.TOTAL, 350.0, "MYR", "Makcorps")
        ));

        List<ParityComparisonResult> results = analyzer.analyzeParity(
                property, directRates, otaRatesByDate, day1, 3);

        assertEquals(3, results.size());

        // Day 1 assertions
        ParityComparisonResult r1 = results.get(0);
        assertTrue(r1.isBreached());
        assertEquals("Klook", r1.getLowestOtaName());
        assertEquals(Double.valueOf(396.0), r1.getLowestOtaPrice());
        assertEquals(Double.valueOf(54.0), r1.getPriceDifference());
        assertEquals(2, r1.getAllOtaPricesMap().size());

        // Day 2 assertions (BEFORE_TAX preferred over TOTAL)
        ParityComparisonResult r2 = results.get(1);
        assertFalse(r2.isBreached());
        assertEquals("Booking.com", r2.getLowestOtaName());
        assertEquals(Double.valueOf(0.50), r2.getPriceDifference());

        // Day 3 assertions (falls back to TOTAL when no BEFORE_TAX exists)
        ParityComparisonResult r3 = results.get(2);
        assertTrue(r3.isBreached());
        assertEquals("Trip.com", r3.getLowestOtaName());
        assertEquals(Double.valueOf(350.0), r3.getLowestOtaPrice());
    }

    @Test
    public void testOfficialWebsiteRateFromApiOverridesMockAndIsExcludedFromOtas() {
        AppConfig config = createTestConfig();
        ParityAnalyzerService analyzer = new ParityAnalyzerService(config);
        HotelProperty property = createMalaysiaProperty();

        LocalDate day = LocalDate.of(2026, 12, 10);
        List<DailyRate> mockDirect = Collections.singletonList(
                new DailyRate(day, "Direct Website", DailyRate.RateType.BEFORE_TAX, 514.83, "MYR", "DirectWebsiteMock")
        );

        DailyRate officialFromSerpApi = new DailyRate(
                day, "Official Site", DailyRate.RateType.BEFORE_TAX, 145.0, "MYR", "SerpApi");
        officialFromSerpApi.setOfficialWebsite(true);

        DailyRate agoda = new DailyRate(
                day, "Agoda", DailyRate.RateType.BEFORE_TAX, 139.0, "MYR", "SerpApi");

        Map<LocalDate, List<DailyRate>> otaRatesByDate = new HashMap<LocalDate, List<DailyRate>>();
        otaRatesByDate.put(day, Arrays.asList(officialFromSerpApi, agoda));

        List<ParityComparisonResult> results = analyzer.analyzeParity(
                property, mockDirect, otaRatesByDate, day, 1);

        assertEquals(1, results.size());
        ParityComparisonResult res = results.get(0);
        assertEquals(Double.valueOf(145.0), res.getWebsitePrice());
        assertEquals(Double.valueOf(139.0), res.getLowestOtaPrice());
        assertEquals("Agoda", res.getLowestOtaName());
        assertEquals(Double.valueOf(6.0), res.getPriceDifference());
        assertTrue(res.isBreached());
        assertEquals(1, res.getAllOtaPricesMap().size());
    }

    @Test
    public void testMarksParityFailedWhenOfficialWebsiteRateIsMissing() {
        AppConfig config = createTestConfig();
        ParityAnalyzerService analyzer = new ParityAnalyzerService(config);
        HotelProperty property = createMalaysiaProperty();

        LocalDate day = LocalDate.of(2026, 12, 11);
        DailyRate agoda = new DailyRate(
                day, "Agoda", DailyRate.RateType.BEFORE_TAX, 320.0, "MYR", "DataForSEO");

        Map<LocalDate, List<DailyRate>> otaRatesByDate = new HashMap<LocalDate, List<DailyRate>>();
        otaRatesByDate.put(day, Collections.singletonList(agoda));

        List<ParityComparisonResult> results = analyzer.analyzeParity(
                property, Collections.<DailyRate>emptyList(), otaRatesByDate, day, 1);

        assertEquals(1, results.size());
        ParityComparisonResult res = results.get(0);
        assertNull(res.getWebsitePrice());
        assertEquals(Double.valueOf(320.0), res.getLowestOtaPrice());
        assertNull(res.getPriceDifference());
        assertTrue(res.isBreached());
    }
}
