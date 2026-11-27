package com.hotel.parity.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents the daily parity comparison between the hotel's direct website rate and OTA rates.
 */
public class ParityComparisonResult {

    private LocalDate date;
    private Double websitePrice;
    private Double lowestOtaPrice;
    private String lowestOtaName;
    private Map<String, Double> allOtaPricesMap;
    private Double priceDifference;
    private boolean breached;
    private String currency;
    private String otaSourceProvider;

    public ParityComparisonResult() {
        this.allOtaPricesMap = new LinkedHashMap<String, Double>();
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Double getWebsitePrice() {
        return websitePrice;
    }

    public void setWebsitePrice(Double websitePrice) {
        this.websitePrice = websitePrice;
    }

    public Double getLowestOtaPrice() {
        return lowestOtaPrice;
    }

    public void setLowestOtaPrice(Double lowestOtaPrice) {
        this.lowestOtaPrice = lowestOtaPrice;
    }

    public String getLowestOtaName() {
        return lowestOtaName;
    }

    public void setLowestOtaName(String lowestOtaName) {
        this.lowestOtaName = lowestOtaName;
    }

    public Map<String, Double> getAllOtaPricesMap() {
        return allOtaPricesMap != null ? allOtaPricesMap : Collections.<String, Double>emptyMap();
    }

    public void setAllOtaPricesMap(Map<String, Double> allOtaPricesMap) {
        this.allOtaPricesMap = allOtaPricesMap != null
                ? new LinkedHashMap<String, Double>(allOtaPricesMap)
                : new LinkedHashMap<String, Double>();
    }

    public Double getPriceDifference() {
        return priceDifference;
    }

    public void setPriceDifference(Double priceDifference) {
        this.priceDifference = priceDifference;
    }

    public boolean isBreached() {
        return breached;
    }

    public void setBreached(boolean breached) {
        this.breached = breached;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getOtaSourceProvider() {
        return otaSourceProvider;
    }

    public void setOtaSourceProvider(String otaSourceProvider) {
        this.otaSourceProvider = otaSourceProvider;
    }
}
