package com.hotel.parity.provider;

import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CascadingOtaRateServiceTest {

    private HotelProperty createSampleProperty() {
        HotelProperty property = new HotelProperty();
        property.setId("TBSR");
        property.setName("Tunamaya Beach & Spa Resort");
        property.setCountry("MY");
        return property;
    }

    @Test
    public void testFallsBackThroughChainWhenEarlierProvidersFailOrReturnEmpty() {
        HotelProperty property = createSampleProperty();
        LocalDate date = LocalDate.of(2026, 12, 1);

        OtaRateProvider serpApi = mock(OtaRateProvider.class);
        OtaRateProvider dataForSeo = mock(OtaRateProvider.class);
        OtaRateProvider makcorps = mock(OtaRateProvider.class);
        OtaRateProvider xotelo = mock(OtaRateProvider.class);

        when(serpApi.getProviderName()).thenReturn("SerpApi");
        when(dataForSeo.getProviderName()).thenReturn("DataForSEO");
        when(makcorps.getProviderName()).thenReturn("Makcorps");
        when(xotelo.getProviderName()).thenReturn("Xotelo");

        // Primary throws exception, Secondary returns empty, Tertiary succeeds
        when(serpApi.fetchOtaRates(property, date)).thenThrow(new RuntimeException("HTTP 429 Rate Limit"));
        when(dataForSeo.fetchOtaRates(property, date)).thenReturn(Collections.<DailyRate>emptyList());
        DailyRate makcorpsRate = new DailyRate(
                date, "Agoda", DailyRate.RateType.BEFORE_TAX, 380.0, "MYR", "Makcorps");
        when(makcorps.fetchOtaRates(property, date)).thenReturn(Collections.singletonList(makcorpsRate));

        CascadingOtaRateService service = new CascadingOtaRateService(
                Arrays.asList(serpApi, dataForSeo, makcorps, xotelo), 2, 0L);

        List<DailyRate> result = service.fetchOtaRatesForDate(property, date);

        assertEquals(1, result.size());
        assertEquals("Agoda", result.get(0).getChannelName());
        assertEquals("Makcorps", result.get(0).getSourceProvider());
        verify(xotelo, never()).fetchOtaRates(property, date);
    }

    @Test
    public void testFallsBackToFinalProviderXotelo() {
        HotelProperty property = createSampleProperty();
        LocalDate date = LocalDate.of(2026, 12, 2);

        OtaRateProvider serpApi = mock(OtaRateProvider.class);
        OtaRateProvider dataForSeo = mock(OtaRateProvider.class);
        OtaRateProvider makcorps = mock(OtaRateProvider.class);
        OtaRateProvider xotelo = mock(OtaRateProvider.class);

        when(serpApi.getProviderName()).thenReturn("SerpApi");
        when(dataForSeo.getProviderName()).thenReturn("DataForSEO");
        when(makcorps.getProviderName()).thenReturn("Makcorps");
        when(xotelo.getProviderName()).thenReturn("Xotelo");

        when(serpApi.fetchOtaRates(property, date)).thenReturn(Collections.<DailyRate>emptyList());
        when(dataForSeo.fetchOtaRates(property, date)).thenReturn(Collections.<DailyRate>emptyList());
        when(makcorps.fetchOtaRates(property, date)).thenThrow(new RuntimeException("Timeout"));

        DailyRate xoteloRate = new DailyRate(
                date, "Booking.com", DailyRate.RateType.BEFORE_TAX, 395.0, "MYR", "Xotelo");
        when(xotelo.fetchOtaRates(property, date)).thenReturn(Collections.singletonList(xoteloRate));

        CascadingOtaRateService service = new CascadingOtaRateService(
                Arrays.asList(serpApi, dataForSeo, makcorps, xotelo), 2, 0L);

        List<DailyRate> result = service.fetchOtaRatesForDate(property, date);

        assertEquals(1, result.size());
        assertEquals("Xotelo", result.get(0).getSourceProvider());
    }

    @Test
    public void testAllProvidersFailReturnsEmptyList() {
        HotelProperty property = createSampleProperty();
        LocalDate date = LocalDate.of(2026, 12, 3);

        OtaRateProvider serpApi = mock(OtaRateProvider.class);
        when(serpApi.getProviderName()).thenReturn("SerpApi");
        when(serpApi.fetchOtaRates(property, date)).thenReturn(Collections.<DailyRate>emptyList());

        CascadingOtaRateService service = new CascadingOtaRateService(
                Collections.singletonList(serpApi), 1, 0L);

        List<DailyRate> result = service.fetchOtaRatesForDate(property, date);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testConcurrentWindowFetchCollectsAllDates() {
        HotelProperty property = createSampleProperty();
        LocalDate start = LocalDate.of(2026, 12, 1);

        OtaRateProvider serpApi = mock(OtaRateProvider.class);
        when(serpApi.getProviderName()).thenReturn("SerpApi");
        for (int i = 0; i < 5; i++) {
            LocalDate d = start.plusDays(i);
            when(serpApi.fetchOtaRates(property, d)).thenReturn(Collections.singletonList(
                    new DailyRate(d, "Agoda", DailyRate.RateType.BEFORE_TAX, 400.0 + i, "MYR", "SerpApi")));
        }

        CascadingOtaRateService service = new CascadingOtaRateService(
                Collections.singletonList(serpApi), 3, 0L);

        Map<LocalDate, List<DailyRate>> window = service.fetchOtaRatesForWindow(property, start, 5);
        assertEquals(5, window.size());
        assertEquals(404.0, window.get(start.plusDays(4)).get(0).getPrice(), 0.001);
    }

    @Test
    public void testProviderFailureDisablesProviderForRemainingDays() {
        HotelProperty property = createSampleProperty();
        LocalDate start = LocalDate.of(2026, 12, 1);

        OtaRateProvider serpApi = mock(OtaRateProvider.class);
        OtaRateProvider dataForSeo = mock(OtaRateProvider.class);
        when(serpApi.getProviderName()).thenReturn("SerpApi");
        when(dataForSeo.getProviderName()).thenReturn("DataForSEO");

        when(serpApi.fetchOtaRates(eq(property), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("SerpApi API key is not configured"));

        for (int i = 0; i < 5; i++) {
            LocalDate d = start.plusDays(i);
            when(dataForSeo.fetchOtaRates(property, d)).thenReturn(Collections.singletonList(
                    new DailyRate(d, "Agoda", DailyRate.RateType.BEFORE_TAX, 390.0 + i, "MYR", "DataForSEO")));
        }

        // Single-threaded to make call count deterministic
        CascadingOtaRateService service = new CascadingOtaRateService(
                Arrays.asList(serpApi, dataForSeo), 1, 0L);

        Map<LocalDate, List<DailyRate>> window = service.fetchOtaRatesForWindow(property, start, 5);

        assertEquals(5, window.size());
        verify(serpApi, times(1)).fetchOtaRates(eq(property), any(LocalDate.class));
        verify(dataForSeo, times(5)).fetchOtaRates(eq(property), any(LocalDate.class));
    }
}
