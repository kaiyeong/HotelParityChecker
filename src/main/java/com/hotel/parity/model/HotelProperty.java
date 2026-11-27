package com.hotel.parity.model;

import java.util.HashMap;
import java.util.Map;

/**
 * POJO representing a target hotel property loaded from the 15-column CSV configuration.
 */
public class HotelProperty {

    private static final Map<String, String> COUNTRY_CURRENCY_MAP = new HashMap<String, String>();

    static {
        COUNTRY_CURRENCY_MAP.put("MY", "MYR");
        COUNTRY_CURRENCY_MAP.put("SG", "SGD");
        COUNTRY_CURRENCY_MAP.put("VN", "VND");
        COUNTRY_CURRENCY_MAP.put("PH", "PHP");
        COUNTRY_CURRENCY_MAP.put("KH", "USD");
    }

    private String id;
    private String name;
    private String addr1;
    private String addr2;
    private String city;
    private String province;
    private String postalCode;
    private String country;
    private Double latitude;
    private Double longitude;
    private String category;
    private String hotelBrand;
    private String phone;
    private Double starRating;
    private String alternateHotelId;

    public HotelProperty() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddr1() {
        return addr1;
    }

    public void setAddr1(String addr1) {
        this.addr1 = addr1;
    }

    public String getAddr2() {
        return addr2;
    }

    public void setAddr2(String addr2) {
        this.addr2 = addr2;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getHotelBrand() {
        return hotelBrand;
    }

    public void setHotelBrand(String hotelBrand) {
        this.hotelBrand = hotelBrand;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Double getStarRating() {
        return starRating;
    }

    public void setStarRating(Double starRating) {
        this.starRating = starRating;
    }

    public String getAlternateHotelId() {
        return alternateHotelId;
    }

    public void setAlternateHotelId(String alternateHotelId) {
        this.alternateHotelId = alternateHotelId;
    }

    /**
     * Constructs a search query dynamically from non-empty fields:
     * e.g. name + " " + (city != null ? city : addr1) + " " + country.
     */
    public String getSearchQuery() {
        StringBuilder sb = new StringBuilder();
        if (isNotBlank(name)) {
            sb.append(name.trim());
        }
        String locationPart = isNotBlank(city) ? city.trim() : (isNotBlank(addr1) ? addr1.trim() : null);
        if (locationPart != null) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(locationPart);
        }
        if (isNotBlank(country)) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(country.trim().toUpperCase());
        }
        return sb.toString();
    }

    /**
     * Maps ISO country code (MY, SG, VN, PH, KH) to default local currency.
     */
    public String getDefaultCurrency() {
        if (!isNotBlank(country)) {
            return "USD";
        }
        String mapped = COUNTRY_CURRENCY_MAP.get(country.trim().toUpperCase());
        return mapped != null ? mapped : "USD";
    }

    /**
     * Returns the Google Hotels `gl` country parameter (lowercase ISO 3166-1 alpha-2).
     */
    public String getGoogleGl() {
        if (!isNotBlank(country)) {
            return "us";
        }
        return country.trim().toLowerCase();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "HotelProperty{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", city='" + city + '\'' +
                ", country='" + country + '\'' +
                '}';
    }
}
