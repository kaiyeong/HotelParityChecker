package com.hotel.parity.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.service.ProviderIdResolverService;
import com.hotel.parity.service.RawApiDataService;
import okhttp3.Credentials;
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
 * Secondary Fallback OTA Rate Provider: DataForSEO Google Hotels Live API.
 */
public class DataForSeoOtaProvider implements OtaRateProvider {

    private static final Logger log = LoggerFactory.getLogger(DataForSeoOtaProvider.class);
    private static final String PROVIDER_NAME = "DataForSEO";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AppConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RawApiDataService rawApiDataService;
    private final ProviderIdResolverService idResolverService;

    public DataForSeoOtaProvider(AppConfig config, OkHttpClient httpClient) {
        this(config, httpClient, null, null);
    }

    public DataForSeoOtaProvider(AppConfig config, OkHttpClient httpClient, RawApiDataService rawApiDataService) {
        this(config, httpClient, rawApiDataService, null);
    }

    public DataForSeoOtaProvider(AppConfig config,
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
        String username = config.getDataForSeoUsername();
        String password = config.getDataForSeoPassword();
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalStateException("DataForSEO credentials are not configured");
        }

        String pid = idResolverService != null ? idResolverService.getGooglePropertyToken(property) : null;
        boolean useHotelInfo = pid != null && !pid.trim().isEmpty();

        LocalDate checkOutDate = checkInDate.plusDays(1);
        ArrayNode payload = objectMapper.createArrayNode();
        ObjectNode task = objectMapper.createObjectNode();
        if (useHotelInfo) {
            task.put("hotel_identifier", pid.trim());
        } else {
            task.put("keyword", property.getSearchQuery());
            task.put("depth", 5);
        }
        task.put("language_name", "English");
        if (property.getLatitude() != null && property.getLongitude() != null) {
            task.put("location_coordinate", property.getLatitude() + "," + property.getLongitude() + ",15z");
        }
        task.put("check_in", checkInDate.toString());
        task.put("check_out", checkOutDate.toString());
        task.put("currency", property.getDefaultCurrency());
        task.put("adults", 2);
        String queueMode = config.getDataForSeoQueueMode() != null
                ? config.getDataForSeoQueueMode().trim().toLowerCase()
                : "live";
        boolean useTaskQueue = "standard".equals(queueMode) || "priority".equals(queueMode);
        if (useTaskQueue) {
            int priority = "priority".equals(queueMode) ? 2 : 1;
            task.put("priority", priority);
        }
        payload.add(task);

        String basePath = useHotelInfo
                ? "/business_data/google/hotel_info"
                : "/business_data/google/hotel_searches";
        String postUrl = config.getDataForSeoBaseUrl() + basePath + (useTaskQueue ? "/task_post" : "/live" + (useHotelInfo ? "/advanced" : ""));
        RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, payload.toString());
        Request request = new Request.Builder()
                .url(postUrl)
                .addHeader("Authorization", Credentials.basic(username, password))
                .post(body)
                .build();

        try {
            JsonNode firstTask;
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("DataForSEO returned HTTP " + response.code());
                }
                String respBody = response.body().string();
                if (!useTaskQueue && rawApiDataService != null) {
                    rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, null, respBody);
                }
                JsonNode root = objectMapper.readTree(respBody);
                JsonNode tasks = root.path("tasks");
                if (!tasks.isArray() || tasks.size() == 0) {
                    return rates;
                }
                firstTask = tasks.get(0);
            }

            int statusCode = firstTask.path("status_code").asInt(0);
            if (useTaskQueue) {
                if (statusCode != 20000 && statusCode != 20100) {
                    throw new IllegalStateException("DataForSEO task_post status " + statusCode
                            + ": " + firstTask.path("status_message").asText());
                }
                String taskId = firstTask.path("id").asText("");
                if (taskId.isEmpty()) {
                    throw new IllegalStateException("DataForSEO task_post returned no task id");
                }
                firstTask = pollTaskResult(basePath, taskId, username, password, property, checkInDate);
                if (firstTask == null) {
                    return rates;
                }
            } else if (statusCode != 20000) {
                throw new IllegalStateException("DataForSEO task status " + statusCode
                        + ": " + firstTask.path("status_message").asText());
            }

            JsonNode results = firstTask.path("result");
            if (!results.isArray() || results.size() == 0) {
                return rates;
            }

            JsonNode firstResult = results.get(0);
            JsonNode pricesItems;
            if (useHotelInfo) {
                pricesItems = firstResult.path("prices").path("items");
            } else {
                JsonNode items = firstResult.path("items");
                if (!items.isArray() || items.size() == 0) {
                    return rates;
                }
                pricesItems = items.get(0).path("prices").path("items");
            }
            if (pricesItems.isArray()) {
                for (JsonNode priceNode : pricesItems) {
                    String otaName = priceNode.path("title").asText(priceNode.path("domain").asText(null));
                    if (otaName == null || otaName.trim().isEmpty()) {
                        continue;
                    }
                    boolean isOfficial = priceNode.path("is_official").asBoolean(false)
                            || "official".equalsIgnoreCase(priceNode.path("type").asText(""))
                            || DailyRate.isOfficialChannelName(otaName, property);
                    String currency = priceNode.path("currency").asText(property.getDefaultCurrency());
                    if (priceNode.hasNonNull("price_without_tax")) {
                        double price = priceNode.get("price_without_tax").asDouble();
                        if (price > 0) {
                            DailyRate rate = new DailyRate(
                                    checkInDate, otaName.trim(), DailyRate.RateType.BEFORE_TAX, price, currency, PROVIDER_NAME);
                            rate.setOfficialWebsite(isOfficial);
                            rates.add(rate);
                        }
                    } else if (priceNode.hasNonNull("price")) {
                        double price = priceNode.get("price").asDouble();
                        if (price > 0) {
                            DailyRate rate = new DailyRate(
                                    checkInDate, otaName.trim(), DailyRate.RateType.TOTAL, price, currency, PROVIDER_NAME);
                            rate.setOfficialWebsite(isOfficial);
                            rates.add(rate);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("DataForSEO request failed: " + e.getMessage(), e);
        }

        return rates;
    }

    private JsonNode pollTaskResult(String basePath,
                                    String taskId,
                                    String username,
                                    String password,
                                    HotelProperty property,
                                    LocalDate checkInDate) throws IOException {
        String getUrl = config.getDataForSeoBaseUrl() + basePath + "/task_get/advanced/" + taskId;
        long pollIntervalMs = Math.max(500L, config.getDataForSeoQueuePollIntervalMs());
        long deadlineMs = System.currentTimeMillis() + (Math.max(10, config.getDataForSeoQueueTimeoutSeconds()) * 1000L);

        while (System.currentTimeMillis() <= deadlineMs) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while polling DataForSEO task " + taskId, ie);
            }

            Request getRequest = new Request.Builder()
                    .url(getUrl)
                    .addHeader("Authorization", Credentials.basic(username, password))
                    .get()
                    .build();

            try (Response response = httpClient.newCall(getRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IllegalStateException("DataForSEO task_get returned HTTP " + response.code());
                }
                String respBody = response.body().string();
                JsonNode root = objectMapper.readTree(respBody);
                JsonNode tasks = root.path("tasks");
                if (!tasks.isArray() || tasks.size() == 0) {
                    continue;
                }
                JsonNode taskNode = tasks.get(0);
                int statusCode = taskNode.path("status_code").asInt(0);
                if (statusCode == 20000) {
                    if (rawApiDataService != null) {
                        rawApiDataService.saveRawResponse(PROVIDER_NAME, property.getId(), checkInDate, null, respBody);
                    }
                    return taskNode;
                }
                if (statusCode == 40601 || statusCode == 40602) {
                    log.debug("DataForSEO task {} still queued (status {}): {}",
                            taskId, statusCode, taskNode.path("status_message").asText());
                    continue;
                }
                throw new IllegalStateException("DataForSEO task_get status " + statusCode
                        + ": " + taskNode.path("status_message").asText());
            }
        }
        throw new IllegalStateException("DataForSEO task " + taskId + " timed out waiting for queue completion");
    }
}
