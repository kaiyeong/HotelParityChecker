package com.hotel.parity.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.service.RawApiDataService;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fetches direct booking engine rates keyed by the property's id / alternateHotelId.
 * Supports live REST JSON integration with a deterministic mock fallback for local testing.
 */
public class DirectHotelWebsiteProvider implements WebsiteRateProvider {

    private static final Logger log = LoggerFactory.getLogger(DirectHotelWebsiteProvider.class);
    private static final String CHANNEL_NAME = "Direct Website";

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RawApiDataService rawApiDataService;

    public DirectHotelWebsiteProvider(AppConfig config, OkHttpClient httpClient) {
        this(config, httpClient, null);
    }

    public DirectHotelWebsiteProvider(AppConfig config, OkHttpClient httpClient, RawApiDataService rawApiDataService) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.rawApiDataService = rawApiDataService;
    }

    @Override
    public List<DailyRate> fetchDirectRates(HotelProperty property, LocalDate startDate, int days) {
        String hotelCode = (property.getAlternateHotelId() != null && !property.getAlternateHotelId().isEmpty())
                ? property.getAlternateHotelId()
                : property.getId();

        String baseUrl = config.getDirectWebsiteApiBaseUrl();
        if (baseUrl != null && !baseUrl.isEmpty() && !baseUrl.contains("example.com")) {
            List<DailyRate> liveRates = fetchFromBookingEngineApi(baseUrl, hotelCode, property, startDate, days);
            if (!liveRates.isEmpty()) {
                return liveRates;
            }
        }

        if (config.isDirectWebsiteMockEnabled()) {
            log.info("Using realistic mock direct website rates for property {} ({} days)", hotelCode, days);
            return generateMockDirectRates(property, startDate, days);
        }

        return new ArrayList<DailyRate>();
    }

    private List<DailyRate> fetchFromBookingEngineApi(String baseUrl,
                                                      String hotelCode,
                                                      HotelProperty property,
                                                      LocalDate startDate,
                                                      int days) {
        List<DailyRate> rates = new ArrayList<DailyRate>();
        HttpUrl parsed = HttpUrl.parse(baseUrl + "/hotels/" + hotelCode + "/rates");
        if (parsed == null) {
            log.warn("Invalid direct website API URL: {}", baseUrl);
            return rates;
        }

        HttpUrl url = parsed.newBuilder()
                .addQueryParameter("startDate", startDate.toString())
                .addQueryParameter("days", String.valueOf(days))
                .addQueryParameter("currency", property.getDefaultCurrency())
                .build();

        Request.Builder reqBuilder = new Request.Builder().url(url).get();
        String apiKey = config.getDirectWebsiteApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Direct booking engine API returned HTTP {} for {}", response.code(), hotelCode);
                return rates;
            }
            String body = response.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse("DirectWebsite", property.getId(), startDate, days + "d", body);
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode rateNodes = root.isArray() ? root : root.path("rates");
            if (rateNodes.isArray()) {
                for (JsonNode node : rateNodes) {
                    String dateStr = node.path("date").asText(null);
                    if (dateStr == null || !node.hasNonNull("rateBeforeTax")) {
                        continue;
                    }
                    LocalDate date = LocalDate.parse(dateStr);
                    double price = node.get("rateBeforeTax").asDouble();
                    String currency = node.path("currency").asText(property.getDefaultCurrency());
                    rates.add(new DailyRate(
                            date,
                            CHANNEL_NAME,
                            DailyRate.RateType.BEFORE_TAX,
                            price,
                            currency,
                            "DirectBookingEngine"
                    ));
                }
            }
        } catch (IOException e) {
            log.warn("Error calling direct booking engine API for {}: {}", hotelCode, e.getMessage());
        }
        return rates;
    }

    private List<DailyRate> generateMockDirectRates(HotelProperty property, LocalDate startDate, int days) {
        List<DailyRate> rates = new ArrayList<DailyRate>(days);
        String currency = property.getDefaultCurrency();
        double baseRate = getBaseRateForCurrency(currency);
        Random random = new Random(property.getId().hashCode());

        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            int dow = date.getDayOfWeek().getValue();
            double weekendMultiplier = (dow >= 5) ? 1.18 : 1.0;
            double noise = 0.95 + (random.nextDouble() * 0.10);
            double price = Math.round(baseRate * weekendMultiplier * noise * 100.0) / 100.0;

            rates.add(new DailyRate(
                    date,
                    CHANNEL_NAME,
                    DailyRate.RateType.BEFORE_TAX,
                    price,
                    currency,
                    "DirectWebsiteMock"
            ));
        }
        return rates;
    }

    private double getBaseRateForCurrency(String currency) {
        if ("MYR".equalsIgnoreCase(currency)) {
            return 420.0;
        }
        if ("SGD".equalsIgnoreCase(currency)) {
            return 185.0;
        }
        if ("VND".equalsIgnoreCase(currency)) {
            return 2100000.0;
        }
        if ("PHP".equalsIgnoreCase(currency)) {
            return 5200.0;
        }
        return 140.0;
    }
}
