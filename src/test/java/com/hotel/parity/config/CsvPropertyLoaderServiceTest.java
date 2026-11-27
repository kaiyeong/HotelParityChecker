package com.hotel.parity.config;

import com.hotel.parity.model.HotelProperty;
import org.junit.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class CsvPropertyLoaderServiceTest {

    @Test
    public void testLoadPropertiesWithMissingOptionalFieldsAndQuotedAddresses() throws Exception {
        String csv = ""
                + "id,name,addr1,addr2,city,province,postal_code,country,latitude,longitude,category,hotel_brand,phone,star_rating,alternate_hotel_id\n"
                + "TBSR,Tunamaya Beach & Spa Resort - Tioman Island,\"Lot 20 & 21, Sarang Burung, Kg. Mukut\",,Pulau Tioman,Pahang,26800,MY,2.721315,104.153136,hotel,TBSR,+60 9-419 4988,4,TBSR\n"
                + "CLHOT,Classique Hotel,240 Jalan Besar,,,,208913,SG,1.3094,103.857939,hotel,CLHOT,+65 6392 3838,3,CLHOT\n"
                + "BHCM,Bay Hotel Ho Chi Minh,\"7 Ngo Van Nam Street, District 1\",,Ho Chi Minh City,,- ,VN,10.781022,106.706131,hotel,BHCM,+84 28 3829 6666,,BHCM\n";

        CsvPropertyLoaderService loader = new CsvPropertyLoaderService();
        List<HotelProperty> properties = loader.loadProperties(new StringReader(csv));

        assertEquals(3, properties.size());

        // Row 1: TBSR
        HotelProperty tbsr = properties.get(0);
        assertEquals("TBSR", tbsr.getId());
        assertEquals("Lot 20 & 21, Sarang Burung, Kg. Mukut", tbsr.getAddr1());
        assertNull(tbsr.getAddr2());
        assertEquals("Pulau Tioman", tbsr.getCity());
        assertEquals("MYR", tbsr.getDefaultCurrency());
        assertEquals("my", tbsr.getGoogleGl());
        assertEquals(Double.valueOf(4.0), tbsr.getStarRating());

        // Row 2: CLHOT (missing city & province -> falls back to addr1 in search query)
        HotelProperty clhot = properties.get(1);
        assertEquals("CLHOT", clhot.getId());
        assertNull(clhot.getCity());
        assertNull(clhot.getProvince());
        assertEquals("Classique Hotel 240 Jalan Besar SG", clhot.getSearchQuery());
        assertEquals("SGD", clhot.getDefaultCurrency());

        // Row 3: BHCM (missing star_rating & "-" postal_code)
        HotelProperty bhcm = properties.get(2);
        assertEquals("BHCM", bhcm.getId());
        assertNull(bhcm.getPostalCode());
        assertNull(bhcm.getStarRating());
        assertNotNull(bhcm.getLatitude());
        assertEquals("VND", bhcm.getDefaultCurrency());
        assertEquals("Bay Hotel Ho Chi Minh Ho Chi Minh City VN", bhcm.getSearchQuery());
    }
}
