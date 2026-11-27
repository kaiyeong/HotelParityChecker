package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.config.CsvPropertyLoaderService;
import com.hotel.parity.model.HotelProperty;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PidResolutionRunnerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testResolvesFiltersAndPrintsVerifyUrls() throws Exception {
        File csvFile = new File(tempFolder.getRoot(), "hotels.csv");
        String csvContent =
                "id,name,addr1,addr2,city,province,postal_code,country,latitude,longitude,category,hotel_brand,phone,star_rating,alternate_hotel_id\n"
                + "CHPP,Cambodiana Hotel Phnom Penh,313 Sisowath Quay,,Phnom Penh,,12207,KH,11.558,104.934,Hotel,,,,\n"
                + "HEC,Hotel Equatorial Ho Chi Minh City,242 Tran Binh Trong,,Ho Chi Minh City,,700000,VN,10.761,106.681,Hotel,,,,\n";
        Files.write(csvFile.toPath(), csvContent.getBytes(StandardCharsets.UTF_8));

        File cacheFile = new File(tempFolder.getRoot(), "provider-id-cache.json");
        Properties props = new Properties();
        props.setProperty("properties.csv.path", csvFile.getAbsolutePath());
        props.setProperty("provider.id.cache.path", cacheFile.getAbsolutePath());
        AppConfig config = new AppConfig(props);

        ProviderIdResolverService mockResolver = mock(ProviderIdResolverService.class);
        when(mockResolver.getGooglePropertyToken(any(HotelProperty.class))).thenReturn(null);
        when(mockResolver.resolveGooglePropertyToken(any(HotelProperty.class), eq(false)))
                .thenReturn("ChI_CHPP_TOKEN")
                .thenReturn(null);

        PidResolutionRunner runner = new PidResolutionRunner(config, new CsvPropertyLoaderService(), mockResolver);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<PidResolutionRunner.PidResolutionResult> allResults =
                runner.run(null, false, new PrintStream(baos, true, "UTF-8"));

        assertEquals(2, allResults.size());
        assertEquals("RESOLVED", allResults.get(0).getStatus());
        assertEquals("ChI_CHPP_TOKEN", allResults.get(0).getPid());
        assertEquals("https://www.google.com/travel/hotels/entity/ChI_CHPP_TOKEN", allResults.get(0).getVerifyUrl());
        assertEquals("MISSING", allResults.get(1).getStatus());
        assertEquals("-", allResults.get(1).getVerifyUrl());

        String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("https://www.google.com/travel/hotels/entity/ChI_CHPP_TOKEN"));

        // Filtered single-property run with forceRefresh=true
        when(mockResolver.resolveGooglePropertyToken(any(HotelProperty.class), eq(true)))
                .thenReturn("ChI_HEC_REFRESHED");
        List<PidResolutionRunner.PidResolutionResult> filtered =
                runner.run("hec", true, new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"));
        assertEquals(1, filtered.size());
        assertEquals("HEC", filtered.get(0).getPropertyId());
        assertEquals("RESOLVED", filtered.get(0).getStatus());
        assertEquals("ChI_HEC_REFRESHED", filtered.get(0).getPid());
        verify(mockResolver).resolveGooglePropertyToken(any(HotelProperty.class), eq(true));
    }
}
