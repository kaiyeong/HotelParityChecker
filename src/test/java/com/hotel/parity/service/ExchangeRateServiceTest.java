package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;

public class ExchangeRateServiceTest {

    @Test
    public void testParsesLiveRatesAndFallsBackToConfig() throws Exception {
        Properties props = new Properties();
        props.setProperty("fx.api.enabled", "false");
        props.setProperty("fx.rate.USD.MYR", "4.45");
        props.setProperty("fx.rate.USD.SGD", "1.34");
        AppConfig config = new AppConfig(props);

        ExchangeRateService service = new ExchangeRateService(config, null);
        // Before live load, uses config fallback
        assertEquals(4.45, service.getCurrencyPerUsd("MYR"), 0.001);
        assertEquals(1.34, service.getCurrencyPerUsd("SGD"), 0.001);

        // After parsing live JSON, live rates override config
        service.parseRatesJson("{\"result\":\"success\",\"base_code\":\"USD\",\"rates\":{\"USD\":1,\"MYR\":4.41,\"THB\":34.2}}");
        assertEquals(4.41, service.getCurrencyPerUsd("MYR"), 0.001);
        assertEquals(34.2, service.getCurrencyPerUsd("THB"), 0.001);
        // Unlisted currency still falls back to config
        assertEquals(1.34, service.getCurrencyPerUsd("SGD"), 0.001);
    }
}
