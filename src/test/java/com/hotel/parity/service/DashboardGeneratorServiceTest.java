package com.hotel.parity.service;

import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class DashboardGeneratorServiceTest {

    @Test
    public void testBuildDashboardHtmlIncludesPropertyFilterAndBreachIndicator() throws Exception {
        HotelProperty prop = new HotelProperty();
        prop.setId("LHR");
        prop.setName("Legend Hotel & Resort");
        prop.setCity("Cherating");
        prop.setCountry("MY");

        ParityComparisonResult r = new ParityComparisonResult();
        r.setDate(LocalDate.of(2026, 11, 19));
        r.setCurrency("MYR");
        r.setWebsitePrice(450.0);
        r.setLowestOtaPrice(390.0);
        r.setLowestOtaName("Agoda");
        r.setPriceDifference(60.0);
        r.setBreached(true);
        r.setOtaSourceProvider("SerpApi");
        r.setAllOtaPricesMap(Collections.singletonMap("Agoda", 390.0));

        Map<HotelProperty, List<ParityComparisonResult>> data = new LinkedHashMap<HotelProperty, List<ParityComparisonResult>>();
        data.put(prop, Collections.singletonList(r));

        DashboardGeneratorService service = new DashboardGeneratorService();
        String html = service.buildDashboardHtml(data);

        assertTrue(html.contains("propertySelect"));
        assertTrue(html.contains("statusFilter"));
        assertTrue(html.contains("backBtn"));
        assertTrue(html.contains("hashchange"));
        assertTrue(html.contains("Data Pulled:"));
        assertTrue(html.contains("maxGapPct"));
        assertTrue(html.contains("gapPct"));
        assertTrue(html.contains("Legend Hotel & Resort"));
        assertTrue(html.contains("WEBSITE HIGHER"));
        assertTrue(html.contains("Agoda"));
        assertTrue(html.contains("NO OFFICIAL RATE"));
        assertTrue(html.contains("Verify Google Hotel PID"));
    }

    @Test
    public void testIncludesGoogleVerifyUrlAndMissingOfficialRateFlag() throws Exception {
        HotelProperty prop = new HotelProperty();
        prop.setId("GEOR");
        prop.setName("GEO Resort & Hotel");
        prop.setCountry("MY");

        ParityComparisonResult missingOfficial = new ParityComparisonResult();
        missingOfficial.setDate(LocalDate.of(2026, 11, 27));
        missingOfficial.setCurrency("MYR");
        missingOfficial.setWebsitePrice(null);
        missingOfficial.setLowestOtaPrice(301.0);
        missingOfficial.setLowestOtaName("Trip.com");
        missingOfficial.setBreached(true);
        missingOfficial.setOtaSourceProvider("DataForSEO");
        missingOfficial.setAllOtaPricesMap(Collections.singletonMap("Trip.com", 301.0));

        ProviderIdResolverService mockResolver = org.mockito.Mockito.mock(ProviderIdResolverService.class);
        org.mockito.Mockito.when(mockResolver.getGooglePropertyToken(prop))
                .thenReturn("ChkI3qbjyru-xNBXGg0vZy8xMWgwejk0N3ZnEAE");

        Map<HotelProperty, List<ParityComparisonResult>> data = new LinkedHashMap<HotelProperty, List<ParityComparisonResult>>();
        data.put(prop, Collections.singletonList(missingOfficial));

        String html = new DashboardGeneratorService().buildDashboardHtml(data, mockResolver);
        assertTrue(html.contains("https://www.google.com/travel/hotels/entity/ChkI3qbjyru-xNBXGg0vZy8xMWgwejk0N3ZnEAE"));
        assertTrue(html.contains("\"missingWebsiteRate\":true"));
        assertTrue(html.contains("\"breached\":true"));
    }
}
