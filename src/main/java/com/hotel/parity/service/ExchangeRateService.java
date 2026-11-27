package com.hotel.parity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.parity.config.AppConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches live USD-based exchange rates from an online FX API once per run,
 * falling back to application.properties (fx.rate.USD.*) if disabled or unreachable.
 */
public class ExchangeRateService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

    private final AppConfig config;
    private final Map<String, Double> liveRatesPerUsd;

    public ExchangeRateService(AppConfig config, OkHttpClient httpClient) {
        this.config = config;
        this.liveRatesPerUsd = new ConcurrentHashMap<String, Double>();
        if (config.isFxApiEnabled() && httpClient != null) {
            fetchLiveRates(httpClient, config.getFxApiUrl());
        }
    }

    public double getCurrencyPerUsd(String currency) {
        if (currency == null || currency.trim().isEmpty()) {
            return 1.0;
        }
        String code = currency.trim().toUpperCase();
        Double liveRate = liveRatesPerUsd.get(code);
        if (liveRate != null && liveRate > 0) {
            return liveRate;
        }
        return config.getCurrencyPerUsd(code);
    }

    public int getLoadedLiveRateCount() {
        return liveRatesPerUsd.size();
    }

    void parseRatesJson(String json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode ratesNode = root.path("rates");
        if (!ratesNode.isObject()) {
            throw new IOException("FX API response missing 'rates' object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = ratesNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            double val = entry.getValue().asDouble(0.0);
            if (val > 0) {
                liveRatesPerUsd.put(entry.getKey().trim().toUpperCase(), val);
            }
        }
    }

    private void fetchLiveRates(OkHttpClient httpClient, String apiUrl) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return;
        }
        Request request = new Request.Builder().url(apiUrl).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("FX API {} returned HTTP {}; falling back to configured fx.rate.USD.* values.",
                        apiUrl, response.code());
                return;
            }
            parseRatesJson(response.body().string());
            log.info("Loaded {} live FX rates from {} (MYR={}, SGD={}, VND={}, PHP={})",
                    liveRatesPerUsd.size(),
                    apiUrl,
                    getCurrencyPerUsd("MYR"),
                    getCurrencyPerUsd("SGD"),
                    getCurrencyPerUsd("VND"),
                    getCurrencyPerUsd("PHP"));
        } catch (IOException e) {
            log.warn("Failed to fetch live FX rates from {}: {}; falling back to configured fx.rate.USD.* values.",
                    apiUrl, e.getMessage());
        }
    }
}
