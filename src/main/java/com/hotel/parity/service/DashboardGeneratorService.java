package com.hotel.parity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a self-contained interactive HTML dashboard with property filtering,
 * OTA vs. Direct Website rate comparison chart, and visual parity breach indicators.
 */
public class DashboardGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DashboardGeneratorService.class);

    private static final String GOOGLE_HOTEL_ENTITY_URL_PREFIX = "https://www.google.com/travel/hotels/entity/";

    private final ObjectMapper objectMapper;

    public DashboardGeneratorService() {
        this.objectMapper = new ObjectMapper();
    }

    public File generateDashboard(String outputPath,
                                  Map<HotelProperty, List<ParityComparisonResult>> resultsByProperty) throws IOException {
        return generateDashboard(outputPath, resultsByProperty, null);
    }

    public File generateDashboard(String outputPath,
                                  Map<HotelProperty, List<ParityComparisonResult>> resultsByProperty,
                                  ProviderIdResolverService idResolver) throws IOException {
        File outFile = new File(outputPath);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        String html = buildDashboardHtml(resultsByProperty, idResolver);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(html.getBytes(StandardCharsets.UTF_8));
        }
        log.info("Generated interactive parity dashboard at {}", outFile.getAbsolutePath());
        return outFile;
    }

    public String buildDashboardHtml(Map<HotelProperty, List<ParityComparisonResult>> resultsByProperty) throws IOException {
        return buildDashboardHtml(resultsByProperty, null);
    }

    public String buildDashboardHtml(Map<HotelProperty, List<ParityComparisonResult>> resultsByProperty,
                                     ProviderIdResolverService idResolver) throws IOException {
        List<Map<String, Object>> payload = new ArrayList<Map<String, Object>>();

        if (resultsByProperty != null) {
            for (Map.Entry<HotelProperty, List<ParityComparisonResult>> entry : resultsByProperty.entrySet()) {
                HotelProperty prop = entry.getKey();
                List<ParityComparisonResult> comparisons = entry.getValue();

                Map<String, Object> propMap = new LinkedHashMap<String, Object>();
                propMap.put("id", prop.getId());
                propMap.put("name", prop.getName());
                propMap.put("city", prop.getCity() != null ? prop.getCity() : "");
                propMap.put("country", prop.getCountry() != null ? prop.getCountry() : "");
                propMap.put("currency", prop.getDefaultCurrency());
                String googlePid = idResolver != null ? idResolver.getGooglePropertyToken(prop) : null;
                propMap.put("googlePropertyToken", googlePid);
                propMap.put("googleVerifyUrl", googlePid != null ? GOOGLE_HOTEL_ENTITY_URL_PREFIX + googlePid : null);

                int breachedCount = 0;
                int withOtaCount = 0;
                double maxGap = 0.0;
                double maxGapPct = 0.0;
                String worstOta = "";

                List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
                if (comparisons != null) {
                    for (ParityComparisonResult r : comparisons) {
                        boolean missingWebsiteRate = r.getWebsitePrice() == null && r.getLowestOtaPrice() != null;
                        boolean isBreachedOrFailed = r.isBreached() || missingWebsiteRate;
                        if (isBreachedOrFailed) {
                            breachedCount++;
                        }
                        if (r.getLowestOtaPrice() != null) {
                            withOtaCount++;
                        }
                        Double gapPct = null;
                        if (r.getPriceDifference() != null && r.getWebsitePrice() != null && r.getWebsitePrice() > 0) {
                            gapPct = Math.round((r.getPriceDifference() / r.getWebsitePrice()) * 1000.0) / 10.0;
                        }
                        if (r.getPriceDifference() != null && r.getPriceDifference() > maxGap) {
                            maxGap = r.getPriceDifference();
                            maxGapPct = gapPct != null ? gapPct : 0.0;
                            worstOta = r.getLowestOtaName() != null ? r.getLowestOtaName() : "";
                        }

                        Map<String, Object> row = new LinkedHashMap<String, Object>();
                        row.put("date", r.getDate().toString());
                        row.put("websitePrice", r.getWebsitePrice());
                        row.put("lowestOtaPrice", r.getLowestOtaPrice());
                        row.put("lowestOtaName", r.getLowestOtaName());
                        row.put("priceDifference", r.getPriceDifference());
                        row.put("gapPct", gapPct);
                        row.put("breached", isBreachedOrFailed);
                        row.put("missingWebsiteRate", missingWebsiteRate);
                        row.put("websiteHigher", r.getWebsitePrice() != null
                                && r.getLowestOtaPrice() != null
                                && r.getWebsitePrice() > r.getLowestOtaPrice());
                        row.put("otaSourceProvider", r.getOtaSourceProvider());
                        row.put("allOtaPrices", r.getAllOtaPricesMap());
                        rows.add(row);
                    }
                }

                propMap.put("totalDays", rows.size());
                propMap.put("withOtaCount", withOtaCount);
                propMap.put("breachedCount", breachedCount);
                propMap.put("maxGap", Math.round(maxGap * 100.0) / 100.0);
                propMap.put("maxGapPct", maxGapPct);
                propMap.put("worstOta", worstOta);
                propMap.put("rows", rows);
                payload.add(propMap);
            }
        }

        String jsonPayload = objectMapper.writeValueAsString(payload)
                .replace("</", "<\\/");
        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return buildTemplate(jsonPayload, generatedAt);
    }

    private String buildTemplate(String jsonPayload, String generatedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Hotel Rate Parity Dashboard</title>\n<style>\n");
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; background: #f3f4f6; color: #111827; }\n");
        sb.append(".header { background: #0f172a; color: #fff; padding: 20px 32px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }\n");
        sb.append(".header h1 { margin: 0; font-size: 20px; }\n");
        sb.append(".header .meta { font-size: 13px; color: #94a3b8; }\n");
        sb.append(".container { max-width: 1280px; margin: 24px auto; padding: 0 20px; }\n");
        sb.append(".controls { background: #fff; padding: 16px 20px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); display: flex; flex-wrap: wrap; gap: 16px; align-items: center; margin-bottom: 20px; }\n");
        sb.append(".control-group { display: flex; flex-direction: column; gap: 4px; }\n");
        sb.append(".control-group label { font-size: 12px; font-weight: 600; color: #4b5563; text-transform: uppercase; }\n");
        sb.append("select, input { padding: 8px 12px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; min-width: 240px; }\n");
        sb.append(".kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 16px; margin-bottom: 20px; }\n");
        sb.append(".kpi { background: #fff; padding: 16px 20px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }\n");
        sb.append(".kpi .label { font-size: 12px; color: #6b7280; text-transform: uppercase; font-weight: 600; }\n");
        sb.append(".kpi .value { font-size: 26px; font-weight: 700; margin-top: 6px; }\n");
        sb.append(".kpi.danger .value { color: #dc2626; }\n");
        sb.append(".kpi.ok .value { color: #059669; }\n");
        sb.append(".card { background: #fff; padding: 20px; border-radius: 10px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin-bottom: 20px; }\n");
        sb.append(".card h2 { margin: 0 0 14px 0; font-size: 16px; color: #1f2937; }\n");
        sb.append("canvas { width: 100%; height: 280px; display: block; }\n");
        sb.append("table { width: 100%; border-collapse: collapse; font-size: 13px; }\n");
        sb.append("th { background: #f8fafc; text-align: left; padding: 10px 12px; border-bottom: 2px solid #e2e8f0; color: #475569; font-weight: 600; }\n");
        sb.append("td { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; vertical-align: middle; }\n");
        sb.append("tr.breach-row { background: #fef2f2; }\n");
        sb.append("tr.clickable:hover { background: #f1f5f9; cursor: pointer; }\n");
        sb.append(".badge { display: inline-block; padding: 3px 9px; border-radius: 9999px; font-size: 11px; font-weight: 700; }\n");
        sb.append(".badge-breach { background: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5; }\n");
        sb.append(".badge-ok { background: #d1fae5; color: #065f46; border: 1px solid #6ee7b7; }\n");
        sb.append(".badge-nodata { background: #f3f4f6; color: #6b7280; border: 1px solid #d1d5db; }\n");
        sb.append(".ota-list { font-size: 12px; color: #4b5563; }\n");
        sb.append(".legend { display: flex; gap: 20px; font-size: 13px; margin-bottom: 8px; }\n");
        sb.append(".dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; }\n");
        sb.append(".back-btn { padding: 8px 14px; background: #1e293b; color: #fff; border: none; border-radius: 6px; font-size: 13px; font-weight: 600; cursor: pointer; display: none; }\n");
        sb.append(".back-btn:hover { background: #334155; }\n");
        sb.append(".pull-pill { background: #1e293b; color: #e2e8f0; padding: 6px 12px; border-radius: 9999px; font-size: 12px; border: 1px solid #334155; }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<div class=\"header\"><div><h1>Hotel Rate Parity Dashboard</h1>");
        sb.append("<div class=\"meta\">Direct Website vs. OTA Comparison</div></div>");
        sb.append("<div class=\"pull-pill\" id=\"pullTimeIndicator\">&#128337; Data Pulled: ").append(generatedAt).append("</div></div>\n");

        sb.append("<div class=\"container\">\n");
        sb.append("<div class=\"controls\">\n");
        sb.append("  <button id=\"backBtn\" class=\"back-btn\" onclick=\"goBackToListing()\">&larr; Back to All Properties</button>\n");
        sb.append("  <div class=\"control-group\"><label for=\"propertySelect\">Property Filter</label><select id=\"propertySelect\"></select></div>\n");
        sb.append("  <div class=\"control-group\"><label for=\"statusFilter\">Status Indicator Filter</label>");
        sb.append("    <select id=\"statusFilter\"><option value=\"ALL\">All Dates</option><option value=\"BREACH\">Website Higher (Breach Only)</option><option value=\"OK\">Parity OK</option></select></div>\n");
        sb.append("</div>\n");

        sb.append("<div class=\"kpis\" id=\"kpiGrid\"></div>\n");
        sb.append("<div class=\"card\" id=\"chartCard\"><h2>Rate Trend (Direct Website vs. Lowest OTA)</h2>");
        sb.append("<div class=\"legend\"><span><span class=\"dot\" style=\"background:#2563eb\"></span>Direct Website</span>");
        sb.append("<span><span class=\"dot\" style=\"background:#dc2626\"></span>Lowest OTA</span></div>");
        sb.append("<canvas id=\"rateChart\" width=\"1160\" height=\"280\"></canvas></div>\n");
        sb.append("<div class=\"card\"><h2 id=\"tableTitle\">Comparison Details</h2><div style=\"overflow-x:auto;\"><table id=\"dataTable\"></table></div></div>\n");
        sb.append("</div>\n");

        sb.append("<script>\nconst DATA = ").append(jsonPayload).append(";\n");
        sb.append("const propSelect = document.getElementById('propertySelect');\n");
        sb.append("const statusFilter = document.getElementById('statusFilter');\n");
        sb.append("const backBtn = document.getElementById('backBtn');\n");
        sb.append("const kpiGrid = document.getElementById('kpiGrid');\n");
        sb.append("const chartCard = document.getElementById('chartCard');\n");
        sb.append("const tableTitle = document.getElementById('tableTitle');\n");
        sb.append("const dataTable = document.getElementById('dataTable');\n\n");

        sb.append("function init() {\n");
        sb.append("  const allOpt = document.createElement('option');\n");
        sb.append("  allOpt.value = '__ALL__';\n");
        sb.append("  allOpt.textContent = 'All Properties (' + DATA.length + ' Hotels)';\n");
        sb.append("  propSelect.appendChild(allOpt);\n");
        sb.append("  DATA.forEach(p => {\n");
        sb.append("    const opt = document.createElement('option');\n");
        sb.append("    opt.value = p.id;\n");
        sb.append("    const flag = p.breachedCount > 0 ? ' [' + p.breachedCount + ' BREACH]' : '';\n");
        sb.append("    opt.textContent = p.id + ' - ' + p.name + flag;\n");
        sb.append("    propSelect.appendChild(opt);\n");
        sb.append("  });\n");
        sb.append("  propSelect.addEventListener('change', () => navigateTo(propSelect.value));\n");
        sb.append("  statusFilter.addEventListener('change', render);\n");
        sb.append("  window.addEventListener('hashchange', syncFromHash);\n");
        sb.append("  syncFromHash();\n");
        sb.append("}\n\n");

        sb.append("function syncFromHash() {\n");
        sb.append("  const hash = window.location.hash || '';\n");
        sb.append("  if (hash.startsWith('#property=')) {\n");
        sb.append("    const id = decodeURIComponent(hash.slice('#property='.length));\n");
        sb.append("    if (DATA.some(p => p.id === id)) { propSelect.value = id; render(); return; }\n");
        sb.append("  }\n");
        sb.append("  propSelect.value = '__ALL__';\n");
        sb.append("  render();\n");
        sb.append("}\n\n");

        sb.append("function navigateTo(id) {\n");
        sb.append("  const targetHash = (id && id !== '__ALL__') ? ('#property=' + encodeURIComponent(id)) : '#';\n");
        sb.append("  if (window.location.hash !== targetHash) {\n");
        sb.append("    window.location.hash = targetHash;\n");
        sb.append("  } else {\n");
        sb.append("    render();\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("function goBackToListing() {\n");
        sb.append("  if (window.history.length > 1 && window.location.hash.startsWith('#property=')) {\n");
        sb.append("    window.history.back();\n");
        sb.append("  } else {\n");
        sb.append("    navigateTo('__ALL__');\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("function fmt(cur, val) { return val == null ? 'N/A' : cur + ' ' + Number(val).toFixed(2); }\n\n");

        sb.append("function render() {\n");
        sb.append("  const selected = propSelect.value;\n");
        sb.append("  const stFilter = statusFilter.value;\n");
        sb.append("  if (selected === '__ALL__') {\n");
        sb.append("    renderPortfolioSummary(stFilter);\n");
        sb.append("  } else {\n");
        sb.append("    const prop = DATA.find(p => p.id === selected);\n");
        sb.append("    if (prop) renderPropertyDetail(prop, stFilter);\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("function renderPortfolioSummary(stFilter) {\n");
        sb.append("  backBtn.style.display = 'none';\n");
        sb.append("  chartCard.style.display = 'none';\n");
        sb.append("  let totalBreachedProps = 0, totalBreachedDays = 0, totalCheckedDays = 0;\n");
        sb.append("  DATA.forEach(p => { if (p.breachedCount > 0) totalBreachedProps++; totalBreachedDays += p.breachedCount; totalCheckedDays += p.totalDays; });\n");
        sb.append("  kpiGrid.innerHTML = `\n");
        sb.append("    <div class=\"kpi\"><div class=\"label\">Properties Monitored</div><div class=\"value\">${DATA.length}</div></div>\n");
        sb.append("    <div class=\"kpi ${totalBreachedProps > 0 ? 'danger' : 'ok'}\"><div class=\"label\">Properties with Breach</div><div class=\"value\">${totalBreachedProps}</div></div>\n");
        sb.append("    <div class=\"kpi ${totalBreachedDays > 0 ? 'danger' : 'ok'}\"><div class=\"label\">Total Breached Days</div><div class=\"value\">${totalBreachedDays}</div></div>\n");
        sb.append("    <div class=\"kpi\"><div class=\"label\">Total Property-Days</div><div class=\"value\">${totalCheckedDays}</div></div>`;\n");
        sb.append("  tableTitle.textContent = 'Portfolio Summary (Click a property to inspect daily OTA rates)';\n");
        sb.append("  const rows = DATA.filter(p => stFilter === 'ALL' || (stFilter === 'BREACH' && p.breachedCount > 0) || (stFilter === 'OK' && p.breachedCount === 0));\n");
        sb.append("  let html = '<thead><tr><th>ID</th><th>Property</th><th>Currency</th><th>Days Checked</th><th>OTA Coverage</th><th>Breached Days</th><th>Max Gap (Value / %)</th><th>Indicator</th></tr></thead><tbody>';\n");
        sb.append("  rows.forEach(p => {\n");
        sb.append("    const badge = p.breachedCount > 0 ? '<span class=\"badge badge-breach\">&#9650; WEBSITE HIGHER</span>' : (p.withOtaCount === 0 ? '<span class=\"badge badge-nodata\">NO OTA DATA</span>' : '<span class=\"badge badge-ok\">&#9679; PARITY OK</span>');\n");
        sb.append("    const maxGapStr = p.maxGap > 0 ? `${p.currency} ${p.maxGap.toFixed(2)} (+${Number(p.maxGapPct || 0).toFixed(1)}%) - ${p.worstOta}` : '-';\n");
        sb.append("    html += `<tr class=\"clickable ${p.breachedCount > 0 ? 'breach-row' : ''}\" onclick=\"selectProp('${p.id}')\"><td><strong>${p.id}</strong></td><td>${p.name}</td><td>${p.currency}</td><td>${p.totalDays}</td><td>${p.withOtaCount}/${p.totalDays}</td><td><strong>${p.breachedCount}</strong></td><td>${maxGapStr}</td><td>${badge}</td></tr>`;\n");
        sb.append("  });\n");
        sb.append("  html += '</tbody>';\n");
        sb.append("  dataTable.innerHTML = html;\n");
        sb.append("}\n\n");

        sb.append("function selectProp(id) { navigateTo(id); }\n\n");

        sb.append("function renderPropertyDetail(p, stFilter) {\n");
        sb.append("  backBtn.style.display = 'inline-block';\n");
        sb.append("  chartCard.style.display = 'block';\n");
        sb.append("  const parityPct = p.withOtaCount > 0 ? (((p.withOtaCount - p.breachedCount) / p.withOtaCount) * 100).toFixed(1) + '%' : 'N/A';\n");
        sb.append("  const verifyHtml = p.googleVerifyUrl ? `<div style=\"margin-top:6px;font-size:12px\"><a href=\"${p.googleVerifyUrl}\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"color:#2563eb;text-decoration:none;font-weight:600\">Verify Google Hotel PID &#8599;</a></div>` : '<div style=\"margin-top:6px;font-size:12px;color:#6b7280\">PID: Not cached</div>';\n");
        sb.append("  kpiGrid.innerHTML = `\n");
        sb.append("    <div class=\"kpi\"><div class=\"label\">Property</div><div class=\"value\" style=\"font-size:18px\">${p.name}</div>${verifyHtml}</div>\n");
        sb.append("    <div class=\"kpi ${p.breachedCount > 0 ? 'danger' : 'ok'}\"><div class=\"label\">Breached / Failed Days</div><div class=\"value\">${p.breachedCount} / ${p.totalDays}</div></div>\n");
        sb.append("    <div class=\"kpi\"><div class=\"label\">Parity Rate</div><div class=\"value\">${parityPct}</div></div>\n");
        sb.append("    <div class=\"kpi ${p.maxGap > 0 ? 'danger' : ''}\"><div class=\"label\">Max Gap (Value / %)</div><div class=\"value\" style=\"font-size:22px\">${p.maxGap > 0 ? p.currency + ' ' + p.maxGap.toFixed(2) + ' (+' + Number(p.maxGapPct || 0).toFixed(1) + '%)' : '-'}</div></div>`;\n");
        sb.append("  drawChart(p);\n");
        sb.append("  tableTitle.textContent = p.name + ' (' + p.id + ') - Daily Website vs. OTA Comparison';\n");
        sb.append("  const filtered = p.rows.filter(r => stFilter === 'ALL' || (stFilter === 'BREACH' && (r.breached || r.websiteHigher || r.missingWebsiteRate)) || (stFilter === 'OK' && !r.breached && !r.websiteHigher && !r.missingWebsiteRate && r.lowestOtaPrice != null));\n");
        sb.append("  let html = '<thead><tr><th>Date</th><th>Website Rate</th><th>Lowest OTA Rate</th><th>Lowest OTA</th><th>Difference (Value / %)</th><th>Indicator</th><th>All OTAs</th><th>Source</th></tr></thead><tbody>';\n");
        sb.append("  filtered.forEach(r => {\n");
        sb.append("    let badge = '<span class=\"badge badge-nodata\">NO OTA DATA</span>';\n");
        sb.append("    if (r.lowestOtaPrice != null) {\n");
        sb.append("      if (r.missingWebsiteRate) badge = '<span class=\"badge badge-breach\">&#9650; NO OFFICIAL RATE</span>';\n");
        sb.append("      else if (r.breached || r.websiteHigher) badge = '<span class=\"badge badge-breach\">&#9650; WEBSITE HIGHER</span>';\n");
        sb.append("      else badge = '<span class=\"badge badge-ok\">&#9679; PARITY OK</span>';\n");
        sb.append("    }\n");
        sb.append("    const otaEntries = r.allOtaPrices ? Object.entries(r.allOtaPrices).map(([k,v]) => k + ': ' + Number(v).toFixed(2)).join(' | ') : '';\n");
        sb.append("    const pctStr = r.gapPct != null ? ' (' + (r.gapPct > 0 ? '+' : '') + Number(r.gapPct).toFixed(1) + '%)' : '';\n");
        sb.append("    const diffStr = r.priceDifference != null ? (r.priceDifference > 0 ? '+' : '') + p.currency + ' ' + Number(r.priceDifference).toFixed(2) + pctStr : '-';\n");
        sb.append("    html += `<tr class=\"${(r.breached || r.websiteHigher || r.missingWebsiteRate) ? 'breach-row' : ''}\"><td>${r.date}</td><td><strong>${fmt(p.currency, r.websitePrice)}</strong></td><td>${fmt(p.currency, r.lowestOtaPrice)}</td><td>${r.lowestOtaName || '-'}</td><td>${diffStr}</td><td>${badge}</td><td class=\"ota-list\">${otaEntries || '-'}</td><td>${r.otaSourceProvider || '-'}</td></tr>`;\n");
        sb.append("  });\n");
        sb.append("  html += '</tbody>';\n");
        sb.append("  dataTable.innerHTML = html;\n");
        sb.append("}\n\n");

        sb.append("function drawChart(p) {\n");
        sb.append("  const canvas = document.getElementById('rateChart');\n");
        sb.append("  const ctx = canvas.getContext('2d');\n");
        sb.append("  const w = canvas.width, h = canvas.height;\n");
        sb.append("  ctx.clearRect(0, 0, w, h);\n");
        sb.append("  const vals = [];\n");
        sb.append("  p.rows.forEach(r => { if (r.websitePrice != null) vals.push(r.websitePrice); if (r.lowestOtaPrice != null) vals.push(r.lowestOtaPrice); });\n");
        sb.append("  if (vals.length === 0) return;\n");
        sb.append("  const minV = Math.min(...vals) * 0.95, maxV = Math.max(...vals) * 1.05 || 1;\n");
        sb.append("  const padL = 65, padR = 20, padT = 20, padB = 35;\n");
        sb.append("  const cw = w - padL - padR, ch = h - padT - padB;\n");
        sb.append("  ctx.strokeStyle = '#e5e7eb'; ctx.fillStyle = '#6b7280'; ctx.font = '11px sans-serif';\n");
        sb.append("  for (let i = 0; i <= 4; i++) {\n");
        sb.append("    const y = padT + (ch * i / 4);\n");
        sb.append("    const v = maxV - ((maxV - minV) * i / 4);\n");
        sb.append("    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke();\n");
        sb.append("    ctx.fillText(v.toFixed(0), 8, y + 4);\n");
        sb.append("  }\n");
        sb.append("  const xAt = idx => padL + (p.rows.length > 1 ? (cw * idx / (p.rows.length - 1)) : cw / 2);\n");
        sb.append("  const yAt = v => padT + ch - ((v - minV) / (maxV - minV || 1)) * ch;\n");
        sb.append("  function plotLine(key, color) {\n");
        sb.append("    ctx.strokeStyle = color; ctx.lineWidth = 2.5; ctx.beginPath(); let started = false;\n");
        sb.append("    p.rows.forEach((r, i) => {\n");
        sb.append("      if (r[key] == null) { started = false; return; }\n");
        sb.append("      const x = xAt(i), y = yAt(r[key]);\n");
        sb.append("      if (!started) { ctx.moveTo(x, y); started = true; } else { ctx.lineTo(x, y); }\n");
        sb.append("    }); ctx.stroke();\n");
        sb.append("  }\n");
        sb.append("  plotLine('websitePrice', '#2563eb');\n");
        sb.append("  plotLine('lowestOtaPrice', '#dc2626');\n");
        sb.append("  ctx.fillStyle = '#6b7280';\n");
        sb.append("  const step = Math.max(1, Math.floor(p.rows.length / 8));\n");
        sb.append("  p.rows.forEach((r, i) => { if (i % step === 0 || i === p.rows.length - 1) ctx.fillText(r.date.slice(5), xAt(i) - 16, h - 10); });\n");
        sb.append("}\n\n");

        sb.append("init();\n</script>\n</body>\n</html>");
        return sb.toString();
    }
}
