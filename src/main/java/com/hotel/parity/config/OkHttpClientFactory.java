package com.hotel.parity.config;

import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Builds a shared OkHttpClient configured with connection pooling, timeouts, and retry interceptor.
 */
public final class OkHttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(OkHttpClientFactory.class);

    private OkHttpClientFactory() {
    }

    public static OkHttpClient create(AppConfig config) {
        final int maxRetries = Math.max(0, config.getHttpMaxRetries());

        return new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .connectTimeout(config.getHttpConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getHttpReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getHttpWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .addInterceptor(new RetryInterceptor(maxRetries))
                .build();
    }

    /**
     * Retries transient network errors, HTTP 429, and HTTP 5xx responses with exponential backoff.
     */
    private static final class RetryInterceptor implements Interceptor {
        private final int maxRetries;

        private RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            IOException lastException = null;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                if (attempt > 0) {
                    long backoffMs = (long) (500L * Math.pow(2, attempt - 1));
                    log.debug("Retrying request {} (attempt {}/{}) after {} ms",
                            request.url().host(), attempt, maxRetries, backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during HTTP retry backoff", ie);
                    }
                }

                try {
                    Response response = chain.proceed(request);
                    int code = response.code();
                    if (code != 429 && code < 500) {
                        return response;
                    }
                    if (attempt < maxRetries) {
                        log.warn("HTTP {} from {} on attempt {}/{}; will retry.",
                                code, request.url().host(), attempt + 1, maxRetries + 1);
                        response.close();
                    } else {
                        return response;
                    }
                } catch (IOException ex) {
                    lastException = ex;
                    if (attempt >= maxRetries) {
                        throw ex;
                    }
                    log.warn("IOException calling {} on attempt {}/{}: {}",
                            request.url().host(), attempt + 1, maxRetries + 1, ex.getMessage());
                }
            }

            if (lastException != null) {
                throw lastException;
            }
            throw new IOException("Request failed after " + maxRetries + " retries: " + request.url());
        }
    }
}
