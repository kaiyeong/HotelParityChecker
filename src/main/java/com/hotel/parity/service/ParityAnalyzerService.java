package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.DailyRate;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aligns direct website rates against OTA rates by date, normalizes currencies,
 * enforces tax-exclusive (BEFORE_TAX) comparison, and flags parity breaches.
 */
public class ParityAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(ParityAnalyzerService.class);

    private final AppConfig config;
    private final ExchangeRateService exchangeRateService;

    public ParityAnalyzerService(AppConfig config) {
        this(config, null);
    }

    public ParityAnalyzerService(AppConfig config, ExchangeRateService exchangeRateService) {
        this.config = config;
        this.exchangeRateService = exchangeRateService;
    }

    public List<ParityComparisonResult> analyzeParity(HotelProperty property,
                                                      List<DailyRate> directRates,
                                                      Map<LocalDate, List<DailyRate>> otaRatesByDate,
                                                      LocalDate startDate,
                                                      int days) {
        String targetCurrency = property.getDefaultCurrency();
        double breachThreshold = config.getParityBreachThreshold();

        Map<LocalDate, DailyRate> directByDate = new HashMap<LocalDate, DailyRate>();
        if (directRates != null) {
            for (DailyRate rate : directRates) {
                if (rate != null && rate.getDate() != null && rate.getRateType() == DailyRate.RateType.BEFORE_TAX) {
                    directByDate.put(rate.getDate(), rate);
                }
            }
        }

        List<ParityComparisonResult> results = new ArrayList<ParityComparisonResult>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = startDate.plusDays(i);
            ParityComparisonResult result = new ParityComparisonResult();
            result.setDate(date);
            result.setCurrency(targetCurrency);

            DailyRate directRate = directByDate.get(date);
            Double websitePrice = null;
            List<DailyRate> otaRates = otaRatesByDate != null ? otaRatesByDate.get(date) : null;
            DailyRate officialApiRate = null;
            List<DailyRate> pureOtaRates = new ArrayList<DailyRate>();

            if (otaRates != null) {
                for (DailyRate rate : otaRates) {
                    if (rate == null || rate.getPrice() <= 0) {
                        continue;
                    }
                    if (rate.isOfficialWebsite()) {
                        if (officialApiRate == null
                                || (officialApiRate.getRateType() != DailyRate.RateType.BEFORE_TAX
                                && rate.getRateType() == DailyRate.RateType.BEFORE_TAX)) {
                            officialApiRate = rate;
                        }
                    } else {
                        pureOtaRates.add(rate);
                    }
                }
            }

            // Priority for Website Price:
            // 1) Real DirectBookingEngine rate
            // 2) Official Website rate from SerpApi/DataForSEO/Makcorps/Xotelo
            // 3) Mock fallback (DirectWebsiteMock)
            boolean isMockDirect = directRate != null && "DirectWebsiteMock".equals(directRate.getSourceProvider());
            if (directRate != null && !isMockDirect) {
                websitePrice = round2(convertCurrency(directRate.getPrice(), directRate.getCurrency(), targetCurrency));
            } else if (officialApiRate != null) {
                websitePrice = round2(convertCurrency(officialApiRate.getPrice(), officialApiRate.getCurrency(), targetCurrency));
            } else if (directRate != null) {
                websitePrice = round2(convertCurrency(directRate.getPrice(), directRate.getCurrency(), targetCurrency));
            }
            result.setWebsitePrice(websitePrice);

            Map<String, Double> otaPricesMap = new LinkedHashMap<String, Double>();
            Double lowestOtaPrice = null;
            String lowestOtaName = null;
            String otaSourceProvider = null;

            if (!pureOtaRates.isEmpty()) {
                boolean hasBeforeTax = false;
                for (DailyRate otaRate : pureOtaRates) {
                    if (otaRate.getRateType() == DailyRate.RateType.BEFORE_TAX) {
                        hasBeforeTax = true;
                        break;
                    }
                }

                for (DailyRate otaRate : pureOtaRates) {
                    // Prefer BEFORE_TAX when available; fall back to TOTAL if no BEFORE_TAX exists for this date
                    if (hasBeforeTax && otaRate.getRateType() != DailyRate.RateType.BEFORE_TAX) {
                        log.debug("Skipping non-BEFORE_TAX OTA rate from {} on {} ({})",
                                otaRate.getChannelName(), date, otaRate.getRateType());
                        continue;
                    }
                    double normalizedPrice = round2(
                            convertCurrency(otaRate.getPrice(), otaRate.getCurrency(), targetCurrency));

                    String channel = otaRate.getChannelName();
                    Double existing = otaPricesMap.get(channel);
                    if (existing == null || normalizedPrice < existing) {
                        otaPricesMap.put(channel, normalizedPrice);
                    }

                    if (lowestOtaPrice == null || normalizedPrice < lowestOtaPrice) {
                        lowestOtaPrice = normalizedPrice;
                        lowestOtaName = channel;
                        otaSourceProvider = otaRate.getSourceProvider();
                    }
                }
            }

            result.setAllOtaPricesMap(otaPricesMap);
            result.setLowestOtaPrice(lowestOtaPrice);
            result.setLowestOtaName(lowestOtaName);
            result.setOtaSourceProvider(otaSourceProvider);

            if (websitePrice != null && lowestOtaPrice != null) {
                double diff = round2(websitePrice - lowestOtaPrice);
                result.setPriceDifference(diff);
                result.setBreached(diff > breachThreshold);
            } else if (websitePrice == null && lowestOtaPrice != null) {
                // Missing Official Website rate when OTAs have prices is treated as parity failure
                result.setBreached(true);
            } else {
                result.setBreached(false);
            }

            results.add(result);
        }

        return results;
    }

    public double convertCurrency(double amount, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null
                || fromCurrency.trim().equalsIgnoreCase(toCurrency.trim())) {
            return amount;
        }
        double fromPerUsd = exchangeRateService != null
                ? exchangeRateService.getCurrencyPerUsd(fromCurrency)
                : config.getCurrencyPerUsd(fromCurrency);
        double toPerUsd = exchangeRateService != null
                ? exchangeRateService.getCurrencyPerUsd(toCurrency)
                : config.getCurrencyPerUsd(toCurrency);
        if (fromPerUsd <= 0) {
            return amount;
        }
        double amountInUsd = amount / fromPerUsd;
        return amountInUsd * toPerUsd;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
