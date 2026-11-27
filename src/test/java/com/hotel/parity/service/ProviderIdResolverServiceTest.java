package com.hotel.parity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.HotelProperty;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class ProviderIdResolverServiceTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testPersistsAndReloadsGooglePropertyToken() throws Exception {
        File cacheFile = new File(tempFolder.getRoot(), "provider-id-cache.json");
        Properties props = new Properties();
        props.setProperty("provider.id.cache.path", cacheFile.getAbsolutePath());
        AppConfig config = new AppConfig(props);

        HotelProperty clhot = new HotelProperty();
        clhot.setId("CLHOT");
        clhot.setName("Classique Hotel");

        ProviderIdResolverService resolver1 = new ProviderIdResolverService(config, null);
        assertNull(resolver1.getGooglePropertyToken(clhot));

        resolver1.saveGooglePropertyToken(clhot, "ChcIrvjgo4rdrpBgGgsvZy8xdHZ5ajhmNhAB");

        // Reload from disk in a new service instance
        ProviderIdResolverService resolver2 = new ProviderIdResolverService(config, null);
        assertEquals("ChcIrvjgo4rdrpBgGgsvZy8xdHZ5ajhmNhAB", resolver2.getGooglePropertyToken(clhot));
    }

    @Test
    public void testPickBestGooglePropertyUsesNameSimilarityAndTightGpsGuardrail() throws Exception {
        File cacheFile = new File(tempFolder.getRoot(), "provider-id-cache.json");
        Properties props = new Properties();
        props.setProperty("provider.id.cache.path", cacheFile.getAbsolutePath());
        ProviderIdResolverService resolver = new ProviderIdResolverService(new AppConfig(props), null);
        ObjectMapper mapper = new ObjectMapper();

        HotelProperty target = new HotelProperty();
        target.setId("85SH");
        target.setName("85 Soho Hotel & Service Apartment");
        target.setLatitude(11.5333);
        target.setLongitude(104.8246);

        // Closer unrelated hotel (~50m away) should NOT beat "85 SOHO Hotel & Serviced Apartment" (~200m away)
        JsonNode candidates = mapper.readTree("["
                + "{\"name\":\"Random Airport Guesthouse\",\"property_token\":\"WRONG_NEARBY\",\"gps_coordinates\":{\"latitude\":11.5336,\"longitude\":104.8248}},"
                + "{\"name\":\"85 SOHO Hotel & Serviced Apartment\",\"property_token\":\"CORRECT_85SH\",\"gps_coordinates\":{\"latitude\":11.5350,\"longitude\":104.8250}}"
                + "]");
        JsonNode matched = resolver.pickBestGoogleProperty(candidates, target);
        assertNotNull(matched);
        assertEquals("CORRECT_85SH", matched.get("property_token").asText());

        // Unrelated hotel 5 km away should return null instead of matching blindly within 15 km
        JsonNode farWrong = mapper.readTree("["
                + "{\"name\":\"Completely Different Hotel\",\"property_token\":\"WRONG_5KM\",\"gps_coordinates\":{\"latitude\":11.5783,\"longitude\":104.8246}}"
                + "]");
        assertNull(resolver.pickBestGoogleProperty(farWrong, target));

        // Same-name match farther than 2 km is rejected when coordinates are known
        JsonNode farSameName = mapper.readTree("["
                + "{\"name\":\"85 SOHO Hotel & Serviced Apartment\",\"property_token\":\"WRONG_CITY\",\"gps_coordinates\":{\"latitude\":11.6000,\"longitude\":104.8246}}"
                + "]");
        assertNull(resolver.pickBestGoogleProperty(farSameName, target));

        // Tight GPS fallback (<= 150m) still matches when name differs completely
        JsonNode tightGps = mapper.readTree("["
                + "{\"name\":\"Soho Residence\",\"property_token\":\"TIGHT_GPS_OK\",\"gps_coordinates\":{\"latitude\":11.5338,\"longitude\":104.8247}}"
                + "]");
        JsonNode gpsMatched = resolver.pickBestGoogleProperty(tightGps, target);
        assertNotNull(gpsMatched);
        assertEquals("TIGHT_GPS_OK", gpsMatched.get("property_token").asText());
    }

    @Test
    public void testLoadsMultipleRmEmailsFromProviderIdCache() throws Exception {
        File cacheFile = new File(tempFolder.getRoot(), "provider-id-cache.json");
        String json = "{\n"
                + "  \"CLHOT\": {\n"
                + "    \"propertyId\": \"CLHOT\",\n"
                + "    \"propertyName\": \"Classique Hotel\",\n"
                + "    \"rmEmails\": [\"rm@classiquehotel.com\", \"gm@classiquehotel.com\"]\n"
                + "  }\n"
                + "}\n";
        java.nio.file.Files.write(cacheFile.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Properties props = new Properties();
        props.setProperty("provider.id.cache.path", cacheFile.getAbsolutePath());
        ProviderIdResolverService resolver = new ProviderIdResolverService(new AppConfig(props), null);

        assertEquals("rm@classiquehotel.com, gm@classiquehotel.com", resolver.getRmEmailsForProperty("CLHOT"));
        assertNull(resolver.getRmEmailsForProperty("UNKNOWN"));
    }
}
