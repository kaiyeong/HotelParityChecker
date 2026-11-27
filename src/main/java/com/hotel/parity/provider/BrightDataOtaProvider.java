package com.hotel.parity.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.service.ProviderIdResolverService;
import com.hotel.parity.service.RawApiDataService;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Primary OTA Rate Provider: Bright Data Google Hotels SERP API (brd_json=1).
 */
public class BrightDataOtaProvider implements OtaRateProvider {

    private static final Logger log = LoggerFactory.getLogger(BrightDataOtaProvider.class);
    private static final String PROVIDER_NAME = "BrightData";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RawApiDataService rawApiDataService;
    private final ProviderIdResolverService idResolverService;

    public BrightDataOtaProvider(AppConfig config, OkHttpClient httpClient) {
        this(config, httpClient, null, null);
    }

    public BrightDataOtaProvider(AppConfig config, OkHttpClient httpClient, RawApiDataService rawApiDataService) {
        this(config, httpClient, rawApiDataService, null);
    }

    public BrightDataOtaProvider(AppConfig config,
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
        String apiKey = config.getBrightDataApiKey();
        String zone = config.getBrightDataZone();
        if (apiKey == null || apiKey.isEmpty() || zone == null || zone.isEmpty()) {
            throw new IllegalStateException("Bright Data API key or zone is not configured");
        }

        LocalDate checkOutDate = checkInDate.plusDays(1);

        // 1. If Google pid is cached (or resolved once via SerpApi/SearchApi), fetch entity prices in 1 single call
        String cachedPid = idResolverService != null ? idResolverService.resolveGooglePropertyToken(property) : null;
        if (cachedPid != null) {
            try {
                fetchEntityPrices(cachedPid, property, checkInDate, checkOutDate, apiKey, zone, rates);
                if (!rates.isEmpty()) {
                    return rates;
                }
            } catch (IOException e) {
                log.warn("Bright Data cached pid={} lookup failed for {} on {}: {}; falling back to search.",
                        cachedPid, property.getId(), checkInDate, e.getMessage());
            }
        }

        String hotelDates = checkInDate.toString() + "," + checkOutDate.toString();
        HttpUrl googleHotelsUrl = HttpUrl.parse("https://www.google.com/travel/hotels").newBuilder()
                .addQueryParameter("q", buildCleanSearchQuery(property))
                .addQueryParameter("hl", "en")
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("curr", property.getDefaultCurrency())
                .addQueryParameter("hotel_dates", hotelDates)
                .addQueryParameter("brd_json", "1")
                .build();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("zone", zone);
        payload.put("url", googleHotelsUrl.toString());
        payload.put("format", "raw");

        Request request = new Request.Builder()
                .url(config.getBrightDataApiUrl())
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSON_MEDIA_TYPE, payload.toString()))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("Bright Data returned HTTP " + response.code());
            }
            String body = response.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, null, body);
            }

            String trimmedBody = body.trim();
            if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
                log.warn("Bright Data returned non-JSON response for {} on {}: {}",
                        property.getId(), checkInDate, trimmedBody.length() > 160 ? trimmedBody.substring(0, 160) : trimmedBody);
                return rates;
            }

            JsonNode root = objectMapper.readTree(trimmedBody);
            if (root.hasNonNull("error")) {
                throw new IllegalStateException("Bright Data error: " + root.get("error").asText());
            }

            if (isMismatchedDate(root, checkInDate, property.getId())) {
                return rates;
            }

            String currency = root.path("overview").path("currency").asText(
                    root.path("currency").asText(property.getDefaultCurrency()));
            extractFromContainer(root, property, checkInDate, currency, rates);

            if (rates.isEmpty()) {
                JsonNode hotelList = root.path("hotels").isArray() ? root.path("hotels")
                        : (root.path("properties").isArray() ? root.path("properties") : root.path("organic"));
                if (hotelList.isArray() && hotelList.size() > 0) {
                    JsonNode matched = pickBestCandidate(hotelList, property);
                    if (matched != null) {
                        extractFromContainer(matched, property, checkInDate, currency, rates);

                        if (rates.isEmpty() && matched.hasNonNull("pid")) {
                            String pid = matched.get("pid").asText();
                            if (idResolverService != null) {
                                idResolverService.saveGooglePropertyToken(property, pid);
                            }
                            fetchEntityPrices(pid, property, checkInDate, checkOutDate, apiKey, zone, rates);
                        }
                    }
                }
            }

            if (rates.isEmpty()) {
                fetchFromGoogleSearchKnowledge(property, checkInDate, checkOutDate, apiKey, zone, rates);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Bright Data request failed: " + e.getMessage(), e);
        }

        return rates;
    }

    private boolean isMismatchedDate(JsonNode root, LocalDate expectedCheckIn, String propertyId) {
        String actualStart = root.path("overview").path("requested").path("start_date").asText("");
        if (!actualStart.isEmpty() && !expectedCheckIn.toString().equals(actualStart)) {
            log.warn("Bright Data returned rates for {} instead of requested {} for {}; ignoring mismatched date.",
                    actualStart, expectedCheckIn, propertyId);
            return true;
        }
        return false;
    }

    private void fetchEntityPrices(String pid,
                                   HotelProperty property,
                                   LocalDate checkInDate,
                                   LocalDate checkOutDate,
                                   String apiKey,
                                   String zone,
                                   List<DailyRate> rates) throws IOException {
        String hotelDates = checkInDate.toString() + "," + checkOutDate.toString();
        HttpUrl entityUrl = HttpUrl.parse("https://www.google.com/travel/hotels/entity/" + pid + "/prices")
                .newBuilder()
                .addQueryParameter("hl", "en")
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("curr", property.getDefaultCurrency())
                .addQueryParameter("hotel_dates", hotelDates)
                .addQueryParameter("brd_json", "1")
                .build();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("zone", zone);
        payload.put("url", entityUrl.toString());
        payload.put("format", "raw");

        Request request = new Request.Builder()
                .url(config.getBrightDataApiUrl())
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSON_MEDIA_TYPE, payload.toString()))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.debug("Bright Data entity lookup returned HTTP {} for {} on {}",
                        response.code(), property.getId(), checkInDate);
                return;
            }
            String body = response.body().string();
            if (rawApiDataService != null) {
                rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, "details", body);
            }
            String trimmedBody = body.trim();
            if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
                log.warn("Bright Data entity lookup returned non-JSON response for {} on {}: {}",
                        property.getId(), checkInDate, trimmedBody.length() > 160 ? trimmedBody.substring(0, 160) : trimmedBody);
                return;
            }
            JsonNode root = objectMapper.readTree(trimmedBody);
            if (root.hasNonNull("error")) {
                log.debug("Bright Data entity lookup error for {}: {}",
                        property.getId(), root.get("error").asText());
                return;
            }
            if (isMismatchedDate(root, checkInDate, property.getId())) {
                return;
            }
            String currency = root.path("overview").path("currency").asText(
                    root.path("currency").asText(property.getDefaultCurrency()));
            extractFromContainer(root, property, checkInDate, currency, rates);
        }
    }

    /**
     * Fallback to google.com/search?q=...&hotel_dates=...&brd_json=1 when google.com/travel/hotels
     * does not list the property (e.g. vacation rentals or boutique villas in the Knowledge Panel).
     */
    private void fetchFromGoogleSearchKnowledge(HotelProperty property,
                                                LocalDate checkInDate,
                                                LocalDate checkOutDate,
                                                String apiKey,
                                                String zone,
                                                List<DailyRate> rates) throws IOException {
        String hotelDates = checkInDate.toString() + "," + checkOutDate.toString();
        HttpUrl searchUrl = HttpUrl.parse("https://www.google.com/search").newBuilder()
                .addQueryParameter("q", buildCleanSearchQuery(property))
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("hl", "en")
                .addQueryParameter("curr", property.getDefaultCurrency())
                .addQueryParameter("hotel_dates", hotelDates)
                .addQueryParameter("brd_json", "1")
                .build();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("zone", zone);
        payload.put("url", searchUrl.toString());
        payload.put("format", "raw");

        Request request = new Request.Builder()
                .url(config.getBrightDataApiUrl())
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSON_MEDIA_TYPE, payload.toString()))
                .build();

        String body = executeBrightDataWithEmptyRetry(request);
        if (body == null || body.trim().isEmpty()) {
            return;
        }
        if (rawApiDataService != null) {
            rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, "gsearch", body);
        }
        String trimmedBody = body.trim();
        if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
            log.warn("Bright Data gsearch returned non-JSON response for {} on {}: {}",
                    property.getId(), checkInDate, trimmedBody.length() > 160 ? trimmedBody.substring(0, 160) : trimmedBody);
            return;
        }
        JsonNode root = objectMapper.readTree(trimmedBody);
            JsonNode knowledge = root.path("knowledge");
            if (!knowledge.isObject()) {
                return;
            }
            String knowledgeName = knowledge.path("name").asText("");
            JsonNode items = knowledge.path("hotel_rates").path("items");
            boolean hasMatchingOffer = false;
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (DailyRate.isOfficialChannelName(item.path("title").asText(""), property)) {
                        hasMatchingOffer = true;
                        break;
                    }
                }
            }
            if (!hasMatchingOffer && !isNameMatch(knowledgeName, property)) {
                log.debug("Bright Data google.com/search knowledge name '{}' did not match '{}'; skipping.",
                        knowledgeName, property.getName());
                return;
            }
            if (!items.isArray()) {
                return;
            }
            for (JsonNode item : items) {
                String source = item.path("title").asText(null);
                String rawPrice = item.path("price").asText("");
                double price = parsePrice(item.path("price"));
                if (source == null || source.trim().isEmpty() || price <= 0) {
                    continue;
                }
                String itemCurrency = detectCurrencyFromPrice(rawPrice, property.getDefaultCurrency());
                boolean isOfficial = item.path("official").asBoolean(false)
                        || item.path("is_official").asBoolean(false)
                        || DailyRate.isOfficialChannelName(source, property);
                DailyRate rate = new DailyRate(
                        checkInDate, source.trim(), DailyRate.RateType.TOTAL, price, itemCurrency, PROVIDER_NAME);
                rate.setOfficialWebsite(isOfficial);
                rates.add(rate);
            }
    }

    private String executeBrightDataWithEmptyRetry(Request request) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                String body = response.body().string();
                if (!body.trim().isEmpty()) {
                    return body;
                }
                log.debug("Bright Data returned empty HTTP 200 body (attempt {}/2); retrying.", attempt + 1);
            }
        }
        return null;
    }

    private boolean isNameMatch(String candidate, HotelProperty target) {
        if (candidate == null || target == null || target.getName() == null) {
            return false;
        }
        String c = candidate.trim().toLowerCase();
        String t = target.getName().trim().toLowerCase();
        if (c.isEmpty() || t.isEmpty()) {
            return false;
        }
        if (c.equals(t) || c.contains(t) || t.contains(c)) {
            return true;
        }
        java.util.List<String> sigWords = extractDistinguishingWords(target);
        if (sigWords.isEmpty()) {
            return false;
        }
        int overlap = 0;
        for (String w : sigWords) {
            if (c.contains(w)) {
                overlap++;
            }
        }
        return overlap >= Math.max(1, (sigWords.size() + 1) / 2);
    }

    private java.util.List<String> extractDistinguishingWords(HotelProperty target) {
        java.util.Set<String> locationStopWords = new java.util.HashSet<String>(java.util.Arrays.asList(
                "hotel", "hotels", "resort", "resorts", "inn", "suites", "suite", "service",
                "apartment", "apartments", "villa", "villas", "the", "and", "by", "at", "park"
        ));
        if (target.getCity() != null) {
            for (String cw : target.getCity().toLowerCase().split("[^a-z0-9]+")) {
                if (!cw.isEmpty()) {
                    locationStopWords.add(cw);
                }
            }
        }
        String countryName = toFullCountryName(target.getCountry()).toLowerCase();
        for (String cw : countryName.split("[^a-z0-9]+")) {
            if (!cw.isEmpty()) {
                locationStopWords.add(cw);
            }
        }
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (String w : target.getName().toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() >= 2 && !locationStopWords.contains(w)) {
                result.add(w);
            }
        }
        return result;
    }

    private String detectCurrencyFromPrice(String rawPrice, String fallbackCurrency) {
        if (rawPrice == null || rawPrice.isEmpty()) {
            return fallbackCurrency;
        }
        if (rawPrice.contains("€") || rawPrice.contains("EUR")) return "EUR";
        if (rawPrice.contains("£") || rawPrice.contains("GBP")) return "GBP";
        if (rawPrice.contains("₱") || rawPrice.contains("PHP")) return "PHP";
        if (rawPrice.contains("RM") || rawPrice.contains("MYR")) return "MYR";
        if (rawPrice.contains("S$") || rawPrice.contains("SGD")) return "SGD";
        if (rawPrice.contains("₫") || rawPrice.contains("VND")) return "VND";
        if (rawPrice.contains("₹") || rawPrice.contains("INR")) return "INR";
        if (rawPrice.contains("฿") || rawPrice.contains("THB")) return "THB";
        if (rawPrice.contains("₩") || rawPrice.contains("KRW")) return "KRW";
        if (rawPrice.contains("¥") || rawPrice.contains("JPY")) return "JPY";
        if (rawPrice.contains("MX$")) return "MXN";
        if (rawPrice.contains("CA$")) return "CAD";
        if (rawPrice.contains("A$")) return "AUD";
        if (rawPrice.contains("HK$")) return "HKD";
        if (rawPrice.contains("NT$")) return "TWD";
        if (rawPrice.contains("NZ$")) return "NZD";
        if (rawPrice.contains("R$")) return "BRL";
        java.util.regex.Matcher isoMatcher = java.util.regex.Pattern.compile("\\b([A-Z]{3})\\b").matcher(rawPrice);
        if (isoMatcher.find()) {
            return isoMatcher.group(1);
        }
        if (rawPrice.contains("US$") || rawPrice.contains("$")) return "USD";
        return fallbackCurrency;
    }

    private void extractFromContainer(JsonNode container,
                                      HotelProperty property,
                                      LocalDate checkInDate,
                                      String currency,
                                      List<DailyRate> rates) {
        if (container == null || container.isMissingNode()) {
            return;
        }
        extractOfferArray(container.path("featured_prices"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("prices"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("featured_offers"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("all_offers"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("offers"), property, checkInDate, currency, rates);
        extractOfferArray(container.path("booking_options"), property, checkInDate, currency, rates);
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
            String source = entry.path("source").asText(
                    entry.path("name").asText(
                            entry.path("title").asText(entry.path("provider").asText(null))));
            if (source == null || source.trim().isEmpty()) {
                continue;
            }

            boolean isOfficial = entry.path("official").asBoolean(false)
                    || entry.path("is_official").asBoolean(false)
                    || DailyRate.isOfficialChannelName(source, property);

            String entryCurrency = entry.path("price").path("currency").asText(
                    entry.path("cost").path("currency").asText(currency));

            double beforeTax = firstPositive(
                    parsePrice(entry.path("price").path("value")),
                    parsePrice(entry.path("cost").path("value")),
                    entry.path("price_before_taxes").path("extracted_value").asDouble(0.0),
                    entry.path("extracted_price_before_taxes").asDouble(0.0),
                    entry.path("price_per_night").path("extracted_price_before_taxes").asDouble(0.0),
                    entry.path("rate_per_night").path("extracted_before_taxes_fees").asDouble(0.0)
            );
            double lowestRoomBeforeTax = findLowestRoomBeforeTax(entry.path("rooms"));
            if (lowestRoomBeforeTax > 0 && (beforeTax <= 0 || lowestRoomBeforeTax < beforeTax)) {
                beforeTax = lowestRoomBeforeTax;
            }
            if (beforeTax > 0) {
                DailyRate rate = new DailyRate(
                        checkInDate, source.trim(), DailyRate.RateType.BEFORE_TAX, beforeTax, entryCurrency, PROVIDER_NAME);
                rate.setOfficialWebsite(isOfficial);
                rates.add(rate);
                continue;
            }

            double totalPrice = firstPositive(
                    parsePrice(entry.path("price_with_tax").path("value")),
                    parsePrice(entry.path("price_per_night").path("extracted_price")),
                    parsePrice(entry.path("extracted_price")),
                    parsePrice(entry.path("price_per_night")),
                    parsePrice(entry.path("price")),
                    parsePrice(entry.path("rate_per_night").path("extracted_lowest"))
            );
            double lowestRoomTotal = findLowestRoomTotal(entry.path("rooms"));
            if (lowestRoomTotal > 0 && (totalPrice <= 0 || lowestRoomTotal < totalPrice)) {
                totalPrice = lowestRoomTotal;
            }
            if (totalPrice > 0) {
                DailyRate rate = new DailyRate(
                        checkInDate, source.trim(), DailyRate.RateType.TOTAL, totalPrice, entryCurrency, PROVIDER_NAME);
                rate.setOfficialWebsite(isOfficial);
                rates.add(rate);
            }
        }
    }

    private double findLowestRoomBeforeTax(JsonNode roomsNode) {
        if (!roomsNode.isArray()) {
            return 0.0;
        }
        double min = 0.0;
        for (JsonNode room : roomsNode) {
            double val = firstPositive(
                    parsePrice(room.path("price").path("value")),
                    parsePrice(room.path("cost").path("value"))
            );
            if (val > 0 && (min <= 0 || val < min)) {
                min = val;
            }
        }
        return min;
    }

    private double findLowestRoomTotal(JsonNode roomsNode) {
        if (!roomsNode.isArray()) {
            return 0.0;
        }
        double min = 0.0;
        for (JsonNode room : roomsNode) {
            double val = parsePrice(room.path("price_with_tax").path("value"));
            if (val > 0 && (min <= 0 || val < min)) {
                min = val;
            }
        }
        return min;
    }

    private double parsePrice(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0.0;
        }
        if (node.isNumber()) {
            return node.asDouble(0.0);
        }
        if (node.isTextual()) {
            String digits = node.asText("").replaceAll("[^0-9.]", "");
            if (!digits.isEmpty()) {
                try {
                    return Double.parseDouble(digits);
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }
        }
        return 0.0;
    }

    private double firstPositive(double... values) {
        for (double v : values) {
            if (v > 0) {
                return v;
            }
        }
        return 0.0;
    }

    private JsonNode pickBestCandidate(JsonNode properties, HotelProperty target) {
        String targetName = target.getName() != null ? target.getName().trim().toLowerCase() : "";
        if (!targetName.isEmpty()) {
            for (JsonNode prop : properties) {
                String candidateName = prop.path("name").asText(prop.path("title").asText("")).trim().toLowerCase();
                if (!candidateName.isEmpty()
                        && (candidateName.equals(targetName)
                        || candidateName.contains(targetName)
                        || targetName.contains(candidateName))
                        && isWithinGeoTolerance(prop, target, 15.0)) {
                    return prop;
                }
            }
            // Match on distinguishing hotel-name words (excluding city/country/generic words)
            java.util.List<String> sigWords = extractDistinguishingWords(target);
            JsonNode bestWordMatch = null;
            int bestOverlap = 0;
            if (!sigWords.isEmpty()) {
                int minRequired = Math.max(1, (sigWords.size() + 1) / 2);
                for (JsonNode prop : properties) {
                    if (!isWithinGeoTolerance(prop, target, 15.0)) {
                        continue;
                    }
                    String candidateName = prop.path("name").asText(prop.path("title").asText("")).trim().toLowerCase();
                    int overlap = 0;
                    for (String w : sigWords) {
                        if (candidateName.contains(w)) {
                            overlap++;
                        }
                    }
                    if (overlap > bestOverlap && overlap >= minRequired) {
                        bestOverlap = overlap;
                        bestWordMatch = prop;
                    }
                }
            }
            if (bestWordMatch != null) {
                return bestWordMatch;
            }
            log.debug("No Bright Data candidate matched hotel name '{}' within 15km in {} results; skipping.",
                    target.getName(), properties.size());
            return null;
        }
        return properties.get(0);
    }

    private boolean isWithinGeoTolerance(JsonNode prop, HotelProperty target, double maxKm) {
        if (target.getLatitude() == null || target.getLongitude() == null) {
            return true;
        }
        Double lat = null;
        Double lon = null;
        JsonNode coords = prop.path("gps_coordinates");
        if (coords.hasNonNull("latitude") && coords.hasNonNull("longitude")) {
            lat = coords.get("latitude").asDouble();
            lon = coords.get("longitude").asDouble();
        } else if (prop.path("coordinates").isArray() && prop.path("coordinates").size() >= 2) {
            lat = prop.path("coordinates").get(0).asDouble();
            lon = prop.path("coordinates").get(1).asDouble();
        }
        if (lat == null || lon == null) {
            return true;
        }
        double dLat = Math.toRadians(lat - target.getLatitude());
        double dLon = Math.toRadians(lon - target.getLongitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(target.getLatitude()))
                * Math.cos(Math.toRadians(lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double distKm = 6371.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return distKm <= maxKm;
    }

    private String buildCleanSearchQuery(HotelProperty property) {
        StringBuilder sb = new StringBuilder();
        String name = property.getName() != null ? property.getName().trim() : "";
        String city = property.getCity() != null ? property.getCity().trim() : "";
        String countryName = toFullCountryName(property.getCountry());

        if (!name.isEmpty()) {
            sb.append(name);
        }
        if (!city.isEmpty() && !name.toLowerCase().contains(city.toLowerCase())) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(city);
        }
        if (!countryName.isEmpty()
                && !name.toLowerCase().contains(countryName.toLowerCase())
                && !city.toLowerCase().contains(countryName.toLowerCase())) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(countryName);
        }
        return sb.length() > 0 ? sb.toString() : property.getSearchQuery();
    }

    private String toFullCountryName(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "";
        }
        String upper = code.trim().toUpperCase();
        if ("SG".equals(upper)) return "Singapore";
        if ("MY".equals(upper)) return "Malaysia";
        if ("VN".equals(upper)) return "Vietnam";
        if ("PH".equals(upper)) return "Philippines";
        if ("KH".equals(upper)) return "Cambodia";
        return upper;
    }
}
