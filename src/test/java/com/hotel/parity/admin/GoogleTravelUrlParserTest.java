package com.hotel.parity.admin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GoogleTravelUrlParserTest {

    @Test
    public void testExtractsPidFromRawEntityAndTravelSearchUrls() {
        assertEquals("ChkIi-SNtPjg4qtlGg0vZy8xMW4zbV9feHB4EAE",
                GoogleTravelUrlParser.extractPid("ChkIi-SNtPjg4qtlGg0vZy8xMW4zbV9feHB4EAE"));

        assertEquals("ChgIhv-Zq8rGjMueARoLL2cvMXRjejV2anMQAQ",
                GoogleTravelUrlParser.extractPid("https://www.google.com/travel/hotels/entity/ChgIhv-Zq8rGjMueARoLL2cvMXRjejV2anMQAQ?q=Strand+Hotel"));

        assertEquals("ChoI95fPn5SlqeuqARoNL2cvMTFsa3MzNmpjdxAB",
                GoogleTravelUrlParser.extractPid(
                        "https://www.google.com/travel/search?q=85%20SOHO%20Hotel&qs=CAAgACgAMihDaG9JOTVmUG41U2xxZXVxQVJvTkwyY3ZNVEZzYTNNek5tcGpkeEFCOA1IAA&ap=Kig"));

        assertEquals("ChkIi-SNtPjg4qtlGg0vZy8xMW4zbV9feHB4EAE",
                GoogleTravelUrlParser.extractPid(
                        "https://www.google.com/travel/search?q=verdant%20villas&qs=CAAgACgAMidDaGtJaS1TTnRQamc0cXRsR2cwdlp5OHhNVzR6YlY5ZmVIQjRFQUU4DUgA"));

        // Generic search list URL without hotel PID should return null
        assertNull(GoogleTravelUrlParser.extractPid(
                "https://www.google.com/travel/search?q=Harolds%20Hotel%20Quezon%20City&qs=CAAgACgA"));

        // Outbound click tracking URL has no PID
        assertNull(GoogleTravelUrlParser.extractPid(
                "https://www.google.com/travel/lodging/clk?pc=AJ0xEyhLxt&pcurl=https://example.com"));
    }
}
