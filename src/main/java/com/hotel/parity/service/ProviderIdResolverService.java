package com.hotel.parity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.HotelProperty;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves internal CSV hotel properties (name + latitude/longitude) to Makcorps hotelid
 * and Xotelo hotel_key, persisting mappings in provider-id-cache.json so lookups happen once.
 */
public class ProviderIdResolverService {

    private static final Logger log = LoggerFactory.getLogger(ProviderIdResolverService.class);

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final File cacheFile;
    private final Map<String, ProviderIdMapping> cache;

    public ProviderIdResolverService(AppConfig config, OkHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.cacheFile = new File(config.getProviderIdCachePath());
        this.cache = new ConcurrentHashMap<String, ProviderIdMapping>();
        loadCache();
    }

    public String resolveMakcorpsHotelId(HotelProperty property) {
        ProviderIdMapping mapping = getOrCreateMapping(property);
        if (mapping.getMakcorpsHotelId() != null && !mapping.getMakcorpsHotelId().isEmpty()) {
            return mapping.getMakcorpsHotelId();
        }
        String resolved = fetchMakcorpsId(property);
        if (resolved != null && !resolved.isEmpty()) {
            mapping.setMakcorpsHotelId(resolved);
            mapping.setUpdatedAt(Instant.now().toString());
            saveCache();
        }
        return resolved;
    }

    public String resolveXoteloHotelKey(HotelProperty property) {
        return resolveXoteloHotelKey(property, false);
    }

    public String resolveXoteloHotelKey(HotelProperty property, boolean forceRefresh) {
        ProviderIdMapping mapping = getOrCreateMapping(property);
        if (!forceRefresh && mapping.getXoteloHotelKey() != null && !mapping.getXoteloHotelKey().isEmpty()) {
            return mapping.getXoteloHotelKey();
        }
        String resolved = fetchXoteloKey(property);
        if (resolved != null && !resolved.isEmpty()) {
            mapping.setXoteloHotelKey(resolved);
            mapping.setUpdatedAt(Instant.now().toString());
            saveCache();
        } else if (forceRefresh) {
            mapping.setXoteloHotelKey(null);
            mapping.setUpdatedAt(Instant.now().toString());
            saveCache();
        }
        return resolved;
    }

    public String getGooglePropertyToken(HotelProperty property) {
        if (property == null || property.getId() == null) {
            return null;
        }
        ProviderIdMapping mapping = cache.get(property.getId());
        if (mapping != null && mapping.getGooglePropertyToken() != null
                && !mapping.getGooglePropertyToken().trim().isEmpty()) {
            return mapping.getGooglePropertyToken().trim();
        }
        return null;
    }

    /**
     * Returns comma-separated Revenue Manager email addresses configured for the property
     * in provider-id-cache.json, or null if none are configured.
     */
    public String getRmEmailsForProperty(String propertyId) {
        if (propertyId == null || propertyId.trim().isEmpty()) {
            return null;
        }
        ProviderIdMapping mapping = cache.get(propertyId.trim());
        if (mapping == null || mapping.getRmEmails() == null || mapping.getRmEmails().isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<String>();
        for (String email : mapping.getRmEmails()) {
            if (email != null) {
                for (String part : email.split("[,;]")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        cleaned.add(trimmed);
                    }
                }
            }
        }
        return cleaned.isEmpty() ? null : String.join(", ", cleaned);
    }

    /**
     * Returns cached Google property_token/pid, or resolves it once via SerpApi/SearchApi
     * (which provide accurate Google Hotels entity matching) and caches it.
     */
    public String resolveGooglePropertyToken(HotelProperty property) {
        return resolveGooglePropertyToken(property, false);
    }

    /**
     * Returns cached Google property_token/pid, or resolves it via SerpApi/SearchApi
     * and saves it to provider-id-cache.json. When forceRefresh is true, bypasses the
     * cached token and performs a fresh lookup.
     */
    public String resolveGooglePropertyToken(HotelProperty property, boolean forceRefresh) {
        if (!forceRefresh) {
            String cached = getGooglePropertyToken(property);
            if (cached != null) {
                return cached;
            }
        }
        synchronized (this) {
            if (!forceRefresh) {
                String cached = getGooglePropertyToken(property);
                if (cached != null) {
                    return cached;
                }
            }
            String resolved = fetchGoogleTokenViaSerpApi(property);
            if (resolved == null || resolved.isEmpty()) {
                resolved = fetchGoogleTokenViaSearchApi(property);
            }
            if (resolved != null && !resolved.isEmpty()) {
                saveGooglePropertyToken(property, resolved);
            }
            return resolved;
        }
    }

    private String fetchGoogleTokenViaSerpApi(HotelProperty property) {
        String apiKey = config.getSerpApiKey();
        if (apiKey == null || apiKey.isEmpty() || httpClient == null) {
            return null;
        }
        HttpUrl baseUrl = HttpUrl.parse(config.getSerpApiBaseUrl());
        if (baseUrl == null) {
            return null;
        }
        java.time.LocalDate checkIn = java.time.LocalDate.now().plusDays(Math.max(1, config.getMonitoringStartOffsetDays()));
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("engine", "google_hotels")
                .addQueryParameter("q", property.getSearchQuery())
                .addQueryParameter("check_in_date", checkIn.toString())
                .addQueryParameter("check_out_date", checkIn.plusDays(1).toString())
                .addQueryParameter("currency", property.getDefaultCurrency())
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("adults", "2")
                .addQueryParameter("api_key", apiKey)
                .build();
        return executeGoogleTokenSearch(url, property, "SerpApi");
    }

    private String fetchGoogleTokenViaSearchApi(HotelProperty property) {
        String apiKey = config.getSearchApiKey();
        if (apiKey == null || apiKey.isEmpty() || httpClient == null) {
            return null;
        }
        HttpUrl baseUrl = HttpUrl.parse(config.getSearchApiBaseUrl());
        if (baseUrl == null) {
            return null;
        }
        java.time.LocalDate checkIn = java.time.LocalDate.now().plusDays(Math.max(1, config.getMonitoringStartOffsetDays()));
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("engine", "google_hotels")
                .addQueryParameter("q", property.getSearchQuery())
                .addQueryParameter("check_in_date", checkIn.toString())
                .addQueryParameter("check_out_date", checkIn.plusDays(1).toString())
                .addQueryParameter("currency", property.getDefaultCurrency())
                .addQueryParameter("gl", property.getGoogleGl())
                .addQueryParameter("adults", "2")
                .addQueryParameter("api_key", apiKey)
                .build();
        return executeGoogleTokenSearch(url, property, "SearchApi");
    }

    private String executeGoogleTokenSearch(HttpUrl url, HotelProperty property, String source) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.debug("{} token lookup returned HTTP {} for {}", source, response.code(), property.getId());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body().string());
            String directToken = root.path("property_token").asText("");
            if (directToken.isEmpty()) {
                directToken = root.path("search_parameters").path("property_token").asText("");
            }
            if (!directToken.isEmpty()) {
                return directToken;
            }
            JsonNode props = root.path("properties");
            if (props.isArray() && props.size() > 0) {
                JsonNode best = pickBestGoogleProperty(props, property);
                if (best != null && best.hasNonNull("property_token")) {
                    return best.get("property_token").asText();
                }
            }
        } catch (IOException e) {
            log.debug("Error resolving Google property_token via {} for {}: {}", source, property.getId(), e.getMessage());
        }
        return null;
    }

    private static final double MAX_NAME_MATCH_DISTANCE_KM = 2.0;
    private static final double MAX_GPS_ONLY_DISTANCE_KM = 0.15;
    private static final double MIN_NAME_SIMILARITY_SCORE = 0.50;

    JsonNode pickBestGoogleProperty(JsonNode properties, HotelProperty target) {
        String targetNorm = normalizeName(target != null ? target.getName() : null);
        Set<String> targetTokens = tokenizeName(targetNorm);
        boolean hasTargetCoords = target != null && target.getLatitude() != null && target.getLongitude() != null;

        JsonNode bestNameMatch = null;
        double bestNameScore = 0.0;
        double bestNameDist = Double.MAX_VALUE;

        JsonNode closestGpsMatch = null;
        double closestGpsDist = Double.MAX_VALUE;

        for (JsonNode prop : properties) {
            Double distKm = null;
            if (hasTargetCoords) {
                JsonNode coords = prop.path("gps_coordinates");
                if (coords.hasNonNull("latitude") && coords.hasNonNull("longitude")) {
                    distKm = haversineKm(target.getLatitude(), target.getLongitude(),
                            coords.get("latitude").asDouble(), coords.get("longitude").asDouble());
                    if (distKm < closestGpsDist) {
                        closestGpsDist = distKm;
                        closestGpsMatch = prop;
                    }
                }
            }

            String candidateNorm = normalizeName(prop.path("name").asText(""));
            if (candidateNorm.isEmpty() || targetNorm.isEmpty()) {
                continue;
            }
            double score = computeNameSimilarity(targetNorm, targetTokens, candidateNorm);
            if (score < MIN_NAME_SIMILARITY_SCORE) {
                continue;
            }
            if (distKm != null && distKm > MAX_NAME_MATCH_DISTANCE_KM) {
                continue;
            }

            double effectiveDist = distKm != null ? distKm : Double.MAX_VALUE;
            boolean betterScore = score > bestNameScore + 0.05;
            boolean similarScoreCloserDist = Math.abs(score - bestNameScore) <= 0.05 && effectiveDist < bestNameDist;
            if (bestNameMatch == null || betterScore || similarScoreCloserDist) {
                bestNameMatch = prop;
                bestNameScore = score;
                bestNameDist = effectiveDist;
            }
        }

        if (bestNameMatch != null) {
            return bestNameMatch;
        }

        if (closestGpsMatch != null && closestGpsDist <= MAX_GPS_ONLY_DISTANCE_KM) {
            log.warn("No strong name match for '{}' ({}); using tight GPS fallback within {} km -> '{}'",
                    target.getName(), target.getId(), String.format(Locale.US, "%.3f", closestGpsDist),
                    closestGpsMatch.path("name").asText(""));
            return closestGpsMatch;
        }

        return null;
    }

    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static Set<String> tokenizeName(String normalized) {
        Set<String> tokens = new HashSet<String>();
        if (normalized == null || normalized.isEmpty()) {
            return tokens;
        }
        for (String part : normalized.split("\\s+")) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private static double computeNameSimilarity(String targetNorm, Set<String> targetTokens, String candidateNorm) {
        if (candidateNorm.equals(targetNorm)) {
            return 1.0;
        }
        if (candidateNorm.length() >= 4 && targetNorm.length() >= 4
                && (candidateNorm.contains(targetNorm) || targetNorm.contains(candidateNorm))) {
            return 0.90;
        }
        Set<String> candTokens = tokenizeName(candidateNorm);
        if (targetTokens.isEmpty() || candTokens.isEmpty()) {
            return 0.0;
        }
        int shared = 0;
        for (String token : targetTokens) {
            if (candTokens.contains(token)) {
                shared++;
            }
        }
        return (2.0 * shared) / (targetTokens.size() + candTokens.size());
    }

    public synchronized void saveGooglePropertyToken(HotelProperty property, String token) {
        if (property == null || property.getId() == null || token == null || token.trim().isEmpty()) {
            return;
        }
        ProviderIdMapping mapping = getOrCreateMapping(property);
        String trimmed = token.trim();
        if (!trimmed.equals(mapping.getGooglePropertyToken())) {
            mapping.setGooglePropertyToken(trimmed);
            mapping.setUpdatedAt(Instant.now().toString());
            saveCache();
            log.info("Cached googlePropertyToken={} for property {} ({})",
                    trimmed, property.getId(), property.getName());
        }
    }

    public synchronized void saveRmEmails(HotelProperty property, List<String> emails) {
        if (property == null || property.getId() == null) {
            return;
        }
        ProviderIdMapping mapping = getOrCreateMapping(property);
        List<String> cleaned = new ArrayList<String>();
        if (emails != null) {
            for (String email : emails) {
                if (email != null) {
                    for (String part : email.split("[,;\\r\\n]")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                            cleaned.add(trimmed);
                        }
                    }
                }
            }
        }
        mapping.setRmEmails(cleaned.isEmpty() ? null : cleaned);
        mapping.setUpdatedAt(Instant.now().toString());
        saveCache();
        log.info("Updated rmEmails={} for property {} ({})",
                cleaned, property.getId(), property.getName());
    }

    public ProviderIdMapping getMapping(String propertyId) {
        if (propertyId == null) {
            return null;
        }
        return cache.get(propertyId.trim());
    }

    private ProviderIdMapping getOrCreateMapping(HotelProperty property) {
        String key = property.getId();
        ProviderIdMapping mapping = cache.get(key);
        if (mapping == null) {
            mapping = new ProviderIdMapping();
            mapping.setPropertyId(key);
            mapping.setPropertyName(property.getName());
            ProviderIdMapping existing = cache.putIfAbsent(key, mapping);
            if (existing != null) {
                mapping = existing;
            }
        }
        return mapping;
    }

    private String fetchMakcorpsId(HotelProperty property) {
        String apiKey = config.getMakcorpsApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.debug("Makcorps API key not configured; skipping Makcorps ID lookup for {}", property.getId());
            return null;
        }
        HttpUrl baseUrl = HttpUrl.parse(config.getMakcorpsBaseUrl() + "/mapping");
        if (baseUrl == null) {
            return null;
        }
        HttpUrl url = baseUrl.newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("name", property.getSearchQuery())
                .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Makcorps mapping lookup failed for {} with HTTP {}", property.getId(), response.code());
                return null;
            }
            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);
            if (root.isArray() && root.size() > 0) {
                JsonNode best = pickBestMakcorpsCandidate(root, property);
                if (best != null && best.hasNonNull("document_id")) {
                    String id = best.get("document_id").asText();
                    log.info("Resolved Makcorps hotelid={} for property {}", id, property.getId());
                    return id;
                }
                if (best != null && best.hasNonNull("hotelid")) {
                    String id = best.get("hotelid").asText();
                    log.info("Resolved Makcorps hotelid={} for property {}", id, property.getId());
                    return id;
                }
            }
        } catch (IOException e) {
            log.warn("Error resolving Makcorps hotelid for {}: {}", property.getId(), e.getMessage());
        }
        return null;
    }

    private JsonNode pickBestMakcorpsCandidate(JsonNode candidates, HotelProperty property) {
        if (property.getLatitude() == null || property.getLongitude() == null) {
            return candidates.get(0);
        }
        JsonNode bestNode = candidates.get(0);
        double bestDist = Double.MAX_VALUE;
        for (JsonNode node : candidates) {
            if (node.hasNonNull("coords") && node.get("coords").isArray() && node.get("coords").size() >= 2) {
                double lat = node.get("coords").get(0).asDouble();
                double lon = node.get("coords").get(1).asDouble();
                double dist = haversineKm(property.getLatitude(), property.getLongitude(), lat, lon);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestNode = node;
                }
            }
        }
        return bestNode;
    }

    public void ensureEntryInCache(HotelProperty property) {
        getOrCreateMapping(property);
    }

    public synchronized void flushCache() {
        saveCache();
    }

    private String fetchXoteloKey(HotelProperty property) {
        String rapidApiKey = config.getXoteloRapidApiKey();
        String rapidApiHost = config.getXoteloRapidApiHost();
        boolean useRapidApi = rapidApiKey != null && !rapidApiKey.isEmpty();

        String searchBase = useRapidApi
                ? "https://" + rapidApiHost + "/api/search"
                : config.getXoteloBaseUrl() + "/api/search";

        HttpUrl baseUrl = HttpUrl.parse(searchBase);
        if (baseUrl == null) {
            return null;
        }

        // Try cleanest query first (hotel name only), then name + city, then full search query
        java.util.List<String> queries = buildXoteloSearchQueries(property);
        for (String query : queries) {
            HttpUrl url = baseUrl.newBuilder()
                    .addQueryParameter("query", query)
                    .build();

            Request.Builder reqBuilder = new Request.Builder().url(url).get();
            if (useRapidApi) {
                reqBuilder.addHeader("X-RapidAPI-Key", rapidApiKey);
                reqBuilder.addHeader("X-RapidAPI-Host", rapidApiHost);
            }

            try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                if (response.code() == 401) {
                    log.warn("Xotelo /api/search returned HTTP 401 for {}. Set xotelo.rapidapi.key in application.properties.",
                            property.getId());
                    return null;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Xotelo search lookup failed for {} (query='{}') with HTTP {}",
                            property.getId(), query, response.code());
                    continue;
                }
                String body = response.body().string();
                JsonNode root = objectMapper.readTree(body);
                JsonNode list = root.path("result").path("list");
                if (list.isArray() && list.size() > 0) {
                    JsonNode best = pickBestXoteloCandidate(list, property);
                    if (best != null && best.hasNonNull("hotel_key")) {
                        String key = best.get("hotel_key").asText();
                        log.info("Resolved Xotelo hotel_key={} for property {} using query='{}'",
                                key, property.getId(), query);
                        return key;
                    }
                }
            } catch (IOException e) {
                log.warn("Error resolving Xotelo hotel_key for {}: {}", property.getId(), e.getMessage());
            }
        }
        return null;
    }

    private java.util.List<String> buildXoteloSearchQueries(HotelProperty property) {
        java.util.List<String> queries = new java.util.ArrayList<String>();
        String name = property.getName() != null ? property.getName().trim() : "";
        String city = property.getCity() != null ? property.getCity().trim() : "";
        String countryName = toFullCountryName(property.getCountry());

        if (!name.isEmpty()) {
            // 1. Prefer name + city (if city not already in name)
            if (!city.isEmpty() && !name.toLowerCase().contains(city.toLowerCase())) {
                queries.add(name + " " + city);
            }
            // 2. Prefer name + full country name (critical when city is empty, e.g. Singapore)
            if (!countryName.isEmpty() && !name.toLowerCase().contains(countryName.toLowerCase())) {
                queries.add(name + " " + countryName);
            }
            // 3. Hotel name alone as fallback
            queries.add(name);
        }
        String fullQuery = property.getSearchQuery();
        if (fullQuery != null && !fullQuery.isEmpty() && !queries.contains(fullQuery)) {
            queries.add(fullQuery);
        }
        return queries;
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

    private JsonNode pickBestXoteloCandidate(JsonNode candidates, HotelProperty property) {
        if (property.getLatitude() == null || property.getLongitude() == null) {
            return candidates.get(0);
        }
        JsonNode bestNode = null;
        double bestDist = Double.MAX_VALUE;
        boolean anyCandidateHadGeo = false;

        for (JsonNode node : candidates) {
            JsonNode geo = node.path("geo");
            if (geo.hasNonNull("latitude") && geo.hasNonNull("longitude")) {
                anyCandidateHadGeo = true;
                double lat = geo.get("latitude").asDouble();
                double lon = geo.get("longitude").asDouble();
                double dist = haversineKm(property.getLatitude(), property.getLongitude(), lat, lon);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestNode = node;
                }
            }
        }

        // Reject candidates more than 50 km away (prevents matching same-named hotels in Bali/USA)
        if (anyCandidateHadGeo) {
            if (bestDist <= 50.0) {
                return bestNode;
            }
            log.debug("Closest Xotelo candidate for {} was {} km away (> 50 km); rejecting.",
                    property.getId(), Math.round(bestDist));
            return null;
        }
        return candidates.get(0);
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }

    private synchronized void loadCache() {
        if (!cacheFile.exists() || !cacheFile.isFile()) {
            return;
        }
        try {
            Map<String, ProviderIdMapping> loaded = objectMapper.readValue(
                    cacheFile,
                    new TypeReference<Map<String, ProviderIdMapping>>() {
                    });
            if (loaded != null) {
                cache.putAll(loaded);
                log.info("Loaded {} cached provider ID mappings from {}", cache.size(), cacheFile.getPath());
            }
        } catch (IOException e) {
            log.warn("Failed to load provider ID cache from {}: {}", cacheFile.getPath(), e.getMessage());
        }
    }

    private synchronized void saveCache() {
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            objectMapper.writeValue(cacheFile, cache);
        } catch (IOException e) {
            log.warn("Failed to persist provider ID cache to {}: {}", cacheFile.getPath(), e.getMessage());
        }
    }

    public static class ProviderIdMapping {
        private String propertyId;
        private String propertyName;
        private String googlePropertyToken;
        private String makcorpsHotelId;
        private String xoteloHotelKey;
        private List<String> rmEmails;
        private String updatedAt;

        public String getPropertyId() {
            return propertyId;
        }

        public void setPropertyId(String propertyId) {
            this.propertyId = propertyId;
        }

        public String getPropertyName() {
            return propertyName;
        }

        public void setPropertyName(String propertyName) {
            this.propertyName = propertyName;
        }

        public String getGooglePropertyToken() {
            return googlePropertyToken;
        }

        public void setGooglePropertyToken(String googlePropertyToken) {
            this.googlePropertyToken = googlePropertyToken;
        }

        public String getMakcorpsHotelId() {
            return makcorpsHotelId;
        }

        public void setMakcorpsHotelId(String makcorpsHotelId) {
            this.makcorpsHotelId = makcorpsHotelId;
        }

        public String getXoteloHotelKey() {
            return xoteloHotelKey;
        }

        public void setXoteloHotelKey(String xoteloHotelKey) {
            this.xoteloHotelKey = xoteloHotelKey;
        }

        public List<String> getRmEmails() {
            return rmEmails;
        }

        public void setRmEmails(List<String> rmEmails) {
            this.rmEmails = rmEmails;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
