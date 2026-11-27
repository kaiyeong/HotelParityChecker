package com.hotel.parity.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.service.ProviderIdResolverService;
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

/**
 * Primary OTA Rate Provider: SerpApi Google Hotels API.
 */
public class SerpApiOtaProvider implements OtaRateProvider {

    private static final Logger log = LoggerFactory.getLogger(SerpApiOtaProvider.class);
    private static final String PROVIDER_NAME = "SerpApi";

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RawApiDataService rawApiDataService;
    private final ProviderIdResolverService idResolverService;

    public SerpApiOtaProvider(AppConfig config, OkHttpClient httpClient) {
        this(config, httpClient, null, null);
    }

    public SerpApiOtaProvider(AppConfig config, OkHttpClient httpClient, RawApiDataService rawApiDataService) {
        this(config, httpClient, rawApiDataService, null);
    }

    public SerpApiOtaProvider(AppConfig config,
                              OkHttpClient httpClient,
                              RawApiDataService rawApiDataService,
                              ProviderIdResolverService idResolverService) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.rawApiDataService = rawApiDataService;
        this.idResolverService = idResolverService;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<DailyRate> fetchOtaRates(HotelProperty property, LocalDate checkInDate) {
        List<DailyRate> rates = new ArrayList<DailyRate>();
        String apiKey = config.getSerpApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("SerpApi API key is not configured");
        }

        LocalDate checkOutDate = checkInDate.plusDays(1);
        HttpUrl baseUrl = HttpUrl.parse(config.getSerpApiBaseUrl());
        if (baseUrl == null) {
            return rates;
        }

        // 1. If Google property_token is already cached, fetch details directly in 1 single call
        String cachedToken = idResolverService != null ? idResolverService.getGooglePropertyToken(property) : null;
        if (cachedToken != null) {
            try {
                fetchPropertyDetailsPrices(baseUrl, property, cachedToken, checkInDate, checkOutDate, apiKey, rates);
                if (!rates.isEmpty()) {
                    return rates;
                }
            } catch (IOException e) {
                log.warn("SerpApi cached token={} lookup failed for {} on {}: {}; falling back to search.",
                        cachedToken, property.getId(), checkInDate, e.getMessage());
            }
        }

        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("engine", "google_hotels")
                .addQueryParameter("q", property.getSearchQuery())
                .addQueryParameter("check_in_date", checkInDate.toString())
                .addQueryParameter("check_out_date", checkOutDate.toString())
                .addQueryParameter("currency", property.getDefaultCurrency())
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("adults", "2")
                .addQueryParameter("api_key", apiKey)
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("SerpApi returned HTTP " + response.code());
            }
            String body = response.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, "search", body);
            }
            JsonNode root = objectMapper.readTree(body);

            if (root.hasNonNull("error")) {
                throw new IllegalStateException("SerpApi error: " + root.get("error").asText());
            }

            String currency = root.path("search_parameters").path("currency").asText(property.getDefaultCurrency());

            // 1. Direct property match: featured_prices & prices arrays
            extractPriceArray(root.path("featured_prices"), checkInDate, currency, rates);
            extractPriceArray(root.path("prices"), checkInDate, currency, rates);

            // 2. If SerpApi returned a list of hotels (`properties`), follow property_token for OTA prices
            if (rates.isEmpty() && root.path("properties").isArray() && root.path("properties").size() > 0) {
                JsonNode matchedProp = pickBestPropertyCandidate(root.path("properties"), property);
                extractPriceArray(matchedProp.path("prices"), checkInDate, currency, rates);

                if (rates.isEmpty() && matchedProp.hasNonNull("property_token")) {
                    String propertyToken = matchedProp.get("property_token").asText();
                    if (idResolverService != null) {
                        idResolverService.saveGooglePropertyToken(property, propertyToken);
                    }
                    fetchPropertyDetailsPrices(baseUrl, property, propertyToken, checkInDate, checkOutDate, apiKey, rates);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("SerpApi request failed: " + e.getMessage(), e);
        }

        return rates;
    }

    private JsonNode pickBestPropertyCandidate(JsonNode properties, HotelProperty target) {
        String targetName = target.getName() != null ? target.getName().trim().toLowerCase() : "";
        if (!targetName.isEmpty()) {
            for (JsonNode prop : properties) {
                String candidateName = prop.path("name").asText("").trim().toLowerCase();
                if (!candidateName.isEmpty()
                        && (candidateName.equals(targetName)
                        || candidateName.contains(targetName)
                        || targetName.contains(candidateName))) {
                    return prop;
                }
            }
        }
        if (target.getLatitude() == null || target.getLongitude() == null) {
            return properties.get(0);
        }
        JsonNode best = properties.get(0);
        double bestDist = Double.MAX_VALUE;
        for (JsonNode prop : properties) {
            JsonNode coords = prop.path("gps_coordinates");
            if (coords.hasNonNull("latitude") && coords.hasNonNull("longitude")) {
                double dLat = Math.toRadians(coords.get("latitude").asDouble() - target.getLatitude());
                double dLon = Math.toRadians(coords.get("longitude").asDouble() - target.getLongitude());
                double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                        + Math.cos(Math.toRadians(target.getLatitude()))
                        * Math.cos(Math.toRadians(coords.get("latitude").asDouble()))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
                double dist = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = prop;
                }
            }
        }
        return best;
    }

    private void fetchPropertyDetailsPrices(HttpUrl baseUrl,
                                            HotelProperty property,
                                            String propertyToken,
                                            LocalDate checkInDate,
                                            LocalDate checkOutDate,
                                            String apiKey,
                                            List<DailyRate> rates) throws IOException {
        HttpUrl detailsUrl = baseUrl.newBuilder()
                .addQueryParameter("engine", "google_hotels")
                .addQueryParameter("q", property.getSearchQuery())
                .addQueryParameter("property_token", propertyToken)
                .addQueryParameter("check_in_date", checkInDate.toString())
                .addQueryParameter("check_out_date", checkOutDate.toString())
                .addQueryParameter("currency", property.getDefaultCurrency())
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("adults", "2")
                .addQueryParameter("api_key", apiKey)
                .build();

        Request detailsReq = new Request.Builder().url(detailsUrl).get().build();
        try (Response detailsResp = httpClient.newCall(detailsReq).execute()) {
            if (!detailsResp.isSuccessful() || detailsResp.body() == null) {
                log.debug("SerpApi property_token lookup returned HTTP {} for {} on {}",
                        detailsResp.code(), property.getId(), checkInDate);
                return;
            }
            String detailsBody = detailsResp.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, "details", detailsBody);
            }
            JsonNode detailsRoot = objectMapper.readTree(detailsBody);
            if (detailsRoot.hasNonNull("error")) {
                log.debug("SerpApi property_token error for {}: {}",
                        property.getId(), detailsRoot.get("error").asText());
                return;
            }
            String currency = detailsRoot.path("search_parameters")
                    .path("currency").asText(property.getDefaultCurrency());
            extractPriceArray(detailsRoot.path("featured_prices"), checkInDate, currency, rates);
            extractPriceArray(detailsRoot.path("prices"), checkInDate, currency, rates);
        }
    }

    private void extractPriceArray(JsonNode priceArray,
                                   LocalDate checkInDate,
                                   String currency,
                                   List<DailyRate> rates) {
        if (!priceArray.isArray()) {
            return;
        }
        for (JsonNode entry : priceArray) {
            boolean isOfficial = entry.path("official").asBoolean(false);
            String source = entry.path("source").asText(null);
            if (source == null || source.trim().isEmpty()) {
                continue;
            }

            JsonNode ratePerNight = entry.path("rate_per_night");
            if (ratePerNight.hasNonNull("extracted_before_taxes_fees")) {
                double price = ratePerNight.get("extracted_before_taxes_fees").asDouble();
                if (price > 0) {
                    DailyRate rate = new DailyRate(
                            checkInDate,
                            source.trim(),
                            DailyRate.RateType.BEFORE_TAX,
                            price,
                            currency,
                            PROVIDER_NAME
                    );
                    rate.setOfficialWebsite(isOfficial);
                    rates.add(rate);
                }
            } else if (ratePerNight.hasNonNull("extracted_lowest")) {
                double price = ratePerNight.get("extracted_lowest").asDouble();
                if (price > 0) {
                    DailyRate rate = new DailyRate(
                            checkInDate,
                            source.trim(),
                            DailyRate.RateType.TOTAL,
                            price,
                            currency,
                            PROVIDER_NAME
                    );
                    rate.setOfficialWebsite(isOfficial);
                    rates.add(rate);
                }
            }
        }
    }
}
