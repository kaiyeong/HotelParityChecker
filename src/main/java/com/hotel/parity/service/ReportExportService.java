package com.hotel.parity.service;

import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import com.opencsv.CSVWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the HTML summary email body and generates the 90-day side-by-side CSV report attachment.
 */
public class ReportExportService {

    public byte[] generateCsvReport(HotelProperty property,
                                    List<ParityComparisonResult> results) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             CSVWriter writer = new CSVWriter(osw)) {

            writer.writeNext(new String[]{
                    "PropertyId",
                    "PropertyName",
                    "Date",
                    "Currency",
                    "WebsitePrice",
                    "LowestOtaPrice",
                    "LowestOtaName",
                    "PriceDifference",
                    "IsBreached",
                    "OtaSourceProvider",
                    "AllOtaPrices"
            });

            for (ParityComparisonResult r : results) {
                writer.writeNext(new String[]{
                        safeStr(property.getId()),
                        safeStr(property.getName()),
                        r.getDate().toString(),
                        safeStr(r.getCurrency()),
                        formatNum(r.getWebsitePrice()),
                        formatNum(r.getLowestOtaPrice()),
                        safeStr(r.getLowestOtaName()),
                        formatNum(r.getPriceDifference()),
                        String.valueOf(r.isBreached()),
                        safeStr(r.getOtaSourceProvider()),
                        formatOtaMap(r.getAllOtaPricesMap())
                });
            }
            writer.flush();
        }
        return baos.toByteArray();
    }

    public String buildHtmlEmailBody(HotelProperty property,
                                     List<ParityComparisonResult> results) {
        int daysWindow = (results != null && !results.isEmpty()) ? results.size() : 90;
        return buildHtmlEmailBody(property, results, daysWindow);
    }

    public String buildHtmlEmailBody(HotelProperty property,
                                     List<ParityComparisonResult> results,
                                     int daysWindow) {
        int effectiveDays = daysWindow > 0 ? daysWindow : ((results != null && !results.isEmpty()) ? results.size() : 90);
        List<ParityComparisonResult> breached = new ArrayList<ParityComparisonResult>();
        for (ParityComparisonResult r : results) {
            if (r.isBreached()) {
                breached.add(r);
            }
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<style>")
                .append("@media only screen and (max-width: 600px) {")
                .append("  .email-container { padding: 10px !important; }")
                .append("  .email-title { font-size: 18px !important; }")
                .append("  .parity-table th, .parity-table td { padding: 5px 6px !important; font-size: 11px !important; }")
                .append("}")
                .append("</style></head>");
        html.append("<body style=\"margin: 0; padding: 0; font-family: Arial, sans-serif; color: #1f2937; line-height: 1.5; background-color: #ffffff;\">");
        html.append("<div class=\"email-container\" style=\"max-width: 680px; margin: 0 auto; padding: 16px;\">");
        html.append("<h2 class=\"email-title\" style=\"color: #111827; margin-top: 0;\">Hotel Rate Parity Report: ")
                .append(escapeHtml(property.getName()))
                .append(" (").append(escapeHtml(property.getId())).append(")</h2>");

        if (!breached.isEmpty()) {
            html.append("<div style=\"background-color: #fef2f2; border-left: 4px solid #dc2626; padding: 12px 16px; margin-bottom: 16px;\">");
            html.append("<strong>Action Required:</strong> Found <strong>")
                    .append(breached.size())
                    .append("</strong> date(s) over the next ").append(effectiveDays)
                    .append(" days where an OTA is undercutting your direct website rate or the official website rate is missing. ")
                    .append("Please review and consider adjusting the direct website rate to restore parity.");
            html.append("</div>");
        } else {
            html.append("<div style=\"background-color: #ecfdf5; border-left: 4px solid #10b981; padding: 12px 16px; margin-bottom: 16px;\">");
            html.append("<strong>All Clear:</strong> No rate parity breaches detected across the ")
                    .append(effectiveDays).append("-day window.");
            html.append("</div>");
        }

        html.append("<h3>").append(effectiveDays).append("-Day Rate Comparison Chart</h3>");
        html.append("<p><img src=\"cid:parityChart\" alt=\"").append(effectiveDays)
                .append("-Day Rate Parity Chart\" style=\"width: 100%; max-width: 100%; height: auto; display: block; border: 1px solid #e5e7eb;\"/></p>");

        List<ParityComparisonResult> rowsToShow = breached.isEmpty() ? results : breached;
        String tableHeading = breached.isEmpty()
                ? "Daily Rate Summary (All Checked Dates)"
                : "Breached / Failed Dates Requiring Review (" + breached.size() + " days)";

        html.append("<h3>").append(tableHeading).append("</h3>");
        html.append("<div style=\"overflow-x: auto; -webkit-overflow-scrolling: touch;\">");
        html.append("<table class=\"parity-table\" style=\"border-collapse: collapse; width: 100%; font-size: 13px;\">");
        html.append("<thead><tr style=\"background-color: #1f2937; color: #ffffff; text-align: left;\">")
                .append("<th style=\"padding: 8px; border: 1px solid #d1d5db;\">Date</th>")
                .append("<th style=\"padding: 8px; border: 1px solid #d1d5db;\">Website Rate</th>")
                .append("<th style=\"padding: 8px; border: 1px solid #d1d5db;\">Lowest OTA Rate</th>")
                .append("<th style=\"padding: 8px; border: 1px solid #d1d5db;\">Undercutting OTA</th>")
                .append("<th style=\"padding: 8px; border: 1px solid #d1d5db;\">Gap (Website - OTA)</th>")
                .append("</tr></thead><tbody>");

        for (ParityComparisonResult r : rowsToShow) {
            String rowStyle = r.isBreached()
                    ? "background-color: #fee2e2; color: #991b1b; font-weight: bold;"
                    : "background-color: #ffffff;";
            html.append("<tr style=\"").append(rowStyle).append("\">");
            html.append("<td style=\"padding: 6px 8px; border: 1px solid #d1d5db; white-space: nowrap;\">").append(r.getDate()).append("</td>");
            html.append("<td style=\"padding: 6px 8px; border: 1px solid #d1d5db; white-space: nowrap;\">")
                    .append(formatMoney(r.getCurrency(), r.getWebsitePrice())).append("</td>");
            html.append("<td style=\"padding: 6px 8px; border: 1px solid #d1d5db; white-space: nowrap;\">")
                    .append(formatMoney(r.getCurrency(), r.getLowestOtaPrice())).append("</td>");
            html.append("<td style=\"padding: 6px 8px; border: 1px solid #d1d5db;\">")
                    .append(escapeHtml(safeStr(r.getLowestOtaName()))).append("</td>");
            html.append("<td style=\"padding: 6px 8px; border: 1px solid #d1d5db; white-space: nowrap;\">")
                    .append(formatGapWithPercent(r.getCurrency(), r.getPriceDifference(), r.getWebsitePrice())).append("</td>");
            html.append("</tr>");
        }

        html.append("</tbody></table></div>");
        html.append("<hr style=\"border: none; border-top: 1px solid #e5e7eb; margin: 20px 0 12px 0;\"/>");
        html.append("<p style=\"font-size: 12px; color: #6b7280; margin: 0;\">")
                .append("Price monitored automatically by <strong>QikRes</strong> on ")
                .append(java.time.LocalDate.now())
                .append(".</p></div></body></html>");

        return html.toString();
    }

    private String formatGapWithPercent(String currency, Double diff, Double websitePrice) {
        if (diff == null) {
            return "N/A";
        }
        String money = formatMoney(currency, diff);
        if (websitePrice != null && websitePrice > 0) {
            double pct = (diff / websitePrice) * 100.0;
            String sign = pct > 0 ? "+" : "";
            return String.format(Locale.US, "%s (%s%.1f%%)", money, sign, pct);
        }
        return money;
    }

    private String formatMoney(String currency, Double value) {
        if (value == null) {
            return "N/A";
        }
        return String.format(Locale.US, "%s %.2f", safeStr(currency), value);
    }

    private String formatNum(Double value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatOtaMap(Map<String, Double> map) {
        if (map == null || map.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(String.format(Locale.US, "%.2f", entry.getValue()));
        }
        return sb.toString();
    }

    private String safeStr(String s) {
        return s != null ? s : "";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
