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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tertiary Fallback OTA Rate Provider: Makcorps Hotel Price Comparison API.
 */
public class MakcorpsOtaProvider implements OtaRateProvider {

    private static final Logger log = LoggerFactory.getLogger(MakcorpsOtaProvider.class);
    private static final String PROVIDER_NAME = "Makcorps";

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ProviderIdResolverService idResolverService;
    private final ObjectMapper objectMapper;
    private final RawApiDataService rawApiDataService;

    public MakcorpsOtaProvider(AppConfig config,
                               OkHttpClient httpClient,
                               ProviderIdResolverService idResolverService) {
        this(config, httpClient, idResolverService, null);
    }

    public MakcorpsOtaProvider(AppConfig config,
                               OkHttpClient httpClient,
                               ProviderIdResolverService idResolverService,
                               RawApiDataService rawApiDataService) {
        this.config = config;
        this.httpClient = httpClient;
        this.idResolverService = idResolverService;
        this.objectMapper = new ObjectMapper();
        this.rawApiDataService = rawApiDataService;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public List<DailyRate> fetchOtaRates(HotelProperty property, LocalDate checkInDate) {
        List<DailyRate> rates = new ArrayList<DailyRate>();
        String apiKey = config.getMakcorpsApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("Makcorps API key is not configured");
        }

        String hotelId = idResolverService.resolveMakcorpsHotelId(property);
        if (hotelId == null || hotelId.isEmpty()) {
            log.debug("Could not resolve Makcorps hotelid for {}; skipping {}", property.getId(), PROVIDER_NAME);
            return rates;
        }

        LocalDate checkOutDate = checkInDate.plusDays(1);
        HttpUrl baseUrl = HttpUrl.parse(config.getMakcorpsBaseUrl() + "/hotel");
        if (baseUrl == null) {
            return rates;
        }

        String currency = property.getDefaultCurrency();
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("hotelid", hotelId)
                .addQueryParameter("rooms", "1")
                .addQueryParameter("adults", "2")
                .addQueryParameter("checkin", checkInDate.toString())
                .addQueryParameter("checkout", checkOutDate.toString())
                .addQueryParameter("cur", currency)
                .addQueryParameter("api_key", apiKey)
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Makcorps returned HTTP " + response.code());
            }
            String body = response.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, null, body);
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode comparison = root.path("comparison");
            if (comparison.isArray()) {
                for (JsonNode group : comparison) {
                    if (group.isArray()) {
                        for (JsonNode vendorNode : group) {
                            parseVendorObject(vendorNode, property, checkInDate, currency, rates);
                        }
                    } else if (group.isObject()) {
                        parseVendorObject(group, property, checkInDate, currency, rates);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Makcorps request failed: " + e.getMessage(), e);
        }

        return rates;
    }

    private void parseVendorObject(JsonNode node,
                                   HotelProperty property,
                                   LocalDate checkInDate,
                                   String currency,
                                   List<DailyRate> rates) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (key.startsWith("vendor")) {
                String suffix = key.substring("vendor".length());
                String vendorName = entry.getValue().asText(null);
                JsonNode priceNode = node.get("price" + suffix);
                if (vendorName != null && !vendorName.trim().isEmpty() && priceNode != null) {
                    double price = parseNumericPrice(priceNode.asText(""));
                    if (price > 0) {
                        DailyRate rate = new DailyRate(
                                checkInDate,
                                vendorName.trim(),
                                DailyRate.RateType.BEFORE_TAX,
                                price,
                                currency,
                                PROVIDER_NAME
                        );
                        rate.setOfficialWebsite(DailyRate.isOfficialChannelName(vendorName, property));
                        rates.add(rate);
                    }
                }
            }
        }
    }

    private double parseNumericPrice(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0.0;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
