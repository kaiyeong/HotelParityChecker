package com.hotel.parity.model;

import java.time.LocalDate;

/**
 * Represents a single nightly rate quote from either the hotel's direct website or an OTA.
 */
public class DailyRate {

    public enum RateType {
        BEFORE_TAX,
        TOTAL
    }

    private LocalDate date;
    private String channelName;
    private RateType rateType;
    private double price;
    private String currency;
    private String sourceProvider;
    private boolean officialWebsite;

    public DailyRate() {
    }

    public DailyRate(LocalDate date,
                     String channelName,
                     RateType rateType,
                     double price,
                     String currency,
                     String sourceProvider) {
        this.date = date;
        this.channelName = channelName;
        this.rateType = rateType;
        this.price = price;
        this.currency = currency;
        this.sourceProvider = sourceProvider;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }

    public RateType getRateType() {
        return rateType;
    }

    public void setRateType(RateType rateType) {
        this.rateType = rateType;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getSourceProvider() {
        return sourceProvider;
    }

    public void setSourceProvider(String sourceProvider) {
        this.sourceProvider = sourceProvider;
    }

    public boolean isOfficialWebsite() {
        return officialWebsite;
    }

    public void setOfficialWebsite(boolean officialWebsite) {
        this.officialWebsite = officialWebsite;
    }

    /**
     * Heuristic check for whether a channel/vendor name from an API represents the hotel's own website.
     */
    public static boolean isOfficialChannelName(String channelName, HotelProperty property) {
        if (channelName == null || channelName.trim().isEmpty()) {
            return false;
        }
        String lower = channelName.trim().toLowerCase();
        if (lower.contains("official site")
                || lower.contains("official website")
                || lower.contains("hotel website")
                || "official".equals(lower)) {
            return true;
        }
        if (property != null && property.getName() != null && !property.getName().trim().isEmpty()) {
            String propLower = property.getName().trim().toLowerCase();
            if (lower.equals(propLower) || lower.contains(propLower)) {
                return true;
            }
            // Also compare punctuation-stripped forms (e.g. "Lloyds Inn" vs "Lloyd's Inn")
            String normChannel = lower.replaceAll("[^a-z0-9]", "");
            String normProp = propLower.replaceAll("[^a-z0-9]", "");
            if (!normProp.isEmpty() && (normChannel.equals(normProp) || normChannel.contains(normProp))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "DailyRate{" +
                "date=" + date +
                ", channelName='" + channelName + '\'' +
                ", rateType=" + rateType +
                ", price=" + price +
                ", currency='" + currency + '\'' +
                ", sourceProvider='" + sourceProvider + '\'' +
                '}';
    }
}
