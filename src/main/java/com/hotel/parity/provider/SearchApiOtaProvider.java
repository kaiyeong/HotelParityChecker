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
 * Primary OTA Rate Provider: SearchApi.io Google Hotels API (google_hotels + google_hotels_property).
 */
public class SearchApiOtaProvider implements OtaRateProvider {

    private static final Logger log = LoggerFactory.getLogger(SearchApiOtaProvider.class);
    private static final String PROVIDER_NAME = "SearchApi";

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RawApiDataService rawApiDataService;
    private final ProviderIdResolverService idResolverService;

    public SearchApiOtaProvider(AppConfig config, OkHttpClient httpClient) {
        this(config, httpClient, null, null);
    }

    public SearchApiOtaProvider(AppConfig config, OkHttpClient httpClient, RawApiDataService rawApiDataService) {
        this(config, httpClient, rawApiDataService, null);
    }

    public SearchApiOtaProvider(AppConfig config,
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
        String apiKey = config.getSearchApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("SearchApi API key is not configured");
        }

        LocalDate checkOutDate = checkInDate.plusDays(1);
        HttpUrl baseUrl = HttpUrl.parse(config.getSearchApiBaseUrl());
        if (baseUrl == null) {
            return rates;
        }

        // 1. If Google property_token is already cached, fetch details directly in 1 single call
        String cachedToken = idResolverService != null ? idResolverService.getGooglePropertyToken(property) : null;
        if (cachedToken != null) {
            try {
                fetchPropertyDetails(baseUrl, property, cachedToken, checkInDate, checkOutDate, apiKey, rates);
                if (!rates.isEmpty()) {
                    return rates;
                }
            } catch (IOException e) {
                log.warn("SearchApi cached token={} lookup failed for {} on {}: {}; falling back to search.",
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
                throw new IllegalStateException("SearchApi returned HTTP " + response.code());
            }
            String body = response.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, "search", body);
            }
            JsonNode root = objectMapper.readTree(body);
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("SearchApi error: " + root.get("error").asText());
            }

            String currency = root.path("search_parameters").path("currency").asText(property.getDefaultCurrency());

            extractOffers(root, property, checkInDate, currency, rates);

            if (rates.isEmpty() && root.path("properties").isArray() && root.path("properties").size() > 0) {
                JsonNode matchedProp = pickBestPropertyCandidate(root.path("properties"), property);
                extractOffers(matchedProp, property, checkInDate, currency, rates);

                if (rates.isEmpty() && matchedProp.hasNonNull("property_token")) {
                    String propertyToken = matchedProp.get("property_token").asText();
                    if (idResolverService != null) {
                        idResolverService.saveGooglePropertyToken(property, propertyToken);
                    }
                    fetchPropertyDetails(baseUrl, property, propertyToken, checkInDate, checkOutDate, apiKey, rates);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("SearchApi request failed: " + e.getMessage(), e);
        }

        return rates;
    }

    private void fetchPropertyDetails(HttpUrl baseUrl,
                                      HotelProperty property,
                                      String propertyToken,
                                      LocalDate checkInDate,
                                      LocalDate checkOutDate,
                                      String apiKey,
                                      List<DailyRate> rates) throws IOException {
        HttpUrl detailsUrl = baseUrl.newBuilder()
                .addQueryParameter("engine", "google_hotels_property")
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
                log.debug("SearchApi google_hotels_property returned HTTP {} for {} on {}",
                        detailsResp.code(), property.getId(), checkInDate);
                return;
            }
            String detailsBody = detailsResp.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, "details", detailsBody);
            }
            JsonNode detailsRoot = objectMapper.readTree(detailsBody);
            if (detailsRoot.hasNonNull("error")) {
                log.debug("SearchApi google_hotels_property error for {}: {}",
                        property.getId(), detailsRoot.get("error").asText());
                return;
            }
            JsonNode propertyNode = detailsRoot.path("property");
            String currency = propertyNode.path("currency").asText(
                    detailsRoot.path("search_parameters").path("currency").asText(property.getDefaultCurrency()));
            if (propertyNode.isObject()) {
                extractOffers(propertyNode, property, checkInDate, currency, rates);
            }
            if (rates.isEmpty()) {
                extractOffers(detailsRoot, property, checkInDate, currency, rates);
            }
        }
    }

    private void extractOffers(JsonNode container,
                               HotelProperty property,
                               LocalDate checkInDate,
                               String currency,
                               List<DailyRate> rates) {
        extractOfferArray(container.path("featured_offers"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("all_offers"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("offers"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("featured_prices"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("prices"), property, checkInDate, currency, rates);
    }

    private void extractOfferArray(JsonNode arrayNode,
                                   HotelProperty property,
                                   LocalDate checkInDate,
                                   String currency,
                                   List<DailyRate> rates) {
        if (!arrayNode.isArray()) {
            return;
        }
        for (JsonNode entry : arrayNode) {
            String source = entry.path("source").asText(entry.path("name").asText(null));
            if (source == null || source.trim().isEmpty()) {
                continue;
            }
            boolean isOfficial = entry.path("official").asBoolean(false)
                    || entry.path("is_official").asBoolean(false)
                    || DailyRate.isOfficialChannelName(source, property);

            double beforeTax = firstPositive(
                    entry.path("price_before_taxes").path("extracted_value").asDouble(0.0),
                    entry.path("extracted_price_before_taxes").asDouble(0.0),
                    entry.path("price_per_night").path("extracted_price_before_taxes").asDouble(0.0),
                    entry.path("rate_per_night").path("extracted_before_taxes_fees").asDouble(0.0)
            );
            if (beforeTax > 0) {
                DailyRate rate = new DailyRate(
                        checkInDate, source.trim(), DailyRate.RateType.BEFORE_TAX, beforeTax, currency, PROVIDER_NAME);
                rate.setOfficialWebsite(isOfficial);
                rates.add(rate);
                continue;
            }

            double totalPrice = firstPositive(
                    entry.path("price_per_night").path("extracted_price").asDouble(0.0),
                    entry.path("extracted_price").asDouble(0.0),
                    entry.path("price").path("extracted_value").asDouble(0.0),
                    entry.path("total_price").path("extracted_price").asDouble(0.0),
                    entry.path("rate_per_night").path("extracted_lowest").asDouble(0.0)
            );
            if (totalPrice > 0) {
                DailyRate rate = new DailyRate(
                        checkInDate, source.trim(), DailyRate.RateType.TOTAL, totalPrice, currency, PROVIDER_NAME);
                rate.setOfficialWebsite(isOfficial);
                rates.add(rate);
            }
        }
    }

    private double firstPositive(double... values) {
        for (double v : values) {
            if (v > 0) {
                return v;
            }
        }
        return 0.0;
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
}
