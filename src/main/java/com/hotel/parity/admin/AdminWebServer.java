package com.hotel.parity.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.parity.config.AppConfig;
import com.hotel.parity.config.CsvPropertyLoaderService;
import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.service.ProviderIdResolverService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Lightweight native JDK HttpServer providing an internal Admin UI for
 * managing Google Hotel PIDs (auto-extracted from pasted Google Travel URLs)
 * and per-property parity alert email recipients in provider-id-cache.json.
 */
public class AdminWebServer {

    private static final Logger log = LoggerFactory.getLogger(AdminWebServer.class);
    private static final String GOOGLE_ENTITY_PREFIX = "https://www.google.com/travel/hotels/entity/";

    private final AppConfig config;
    private final CsvPropertyLoaderService csvLoader;
    private final ProviderIdResolverService idResolver;
    private final ObjectMapper objectMapper;
    private HttpServer server;

    public AdminWebServer(AppConfig config) {
        this(config, new CsvPropertyLoaderService(), new ProviderIdResolverService(config, null));
    }

    public AdminWebServer(AppConfig config,
                          CsvPropertyLoaderService csvLoader,
                          ProviderIdResolverService idResolver) {
        this.config = config;
        this.csvLoader = csvLoader;
        this.idResolver = idResolver;
        this.objectMapper = new ObjectMapper();
    }

    public void start() throws IOException {
        String host = config.getAdminServerHost();
        int port = config.getAdminServerPort();
        String context = config.getAdminServerContext();

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext(context, new AdminHandler(context));
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        log.info("Admin Web UI started at http://{}:{}{}", host, port, context);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            log.info("Admin Web UI stopped.");
        }
    }

    private final class AdminHandler implements HttpHandler {
        private final String baseContext;

        private AdminHandler(String baseContext) {
            this.baseContext = baseContext;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"HotelParityChecker Admin\"");
                sendText(exchange, 401, "Unauthorized");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String subPath = path.startsWith(baseContext) ? path.substring(baseContext.length()) : path;
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method) && (subPath.isEmpty() || "/".equals(subPath))) {
                sendHtml(exchange, 200, buildAdminHtml());
                return;
            }
            if ("GET".equalsIgnoreCase(method) && "/api/hotels".equals(subPath)) {
                handleGetHotels(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && "/api/hotel/save".equals(subPath)) {
                handleSaveHotel(exchange);
                return;
            }
            sendText(exchange, 404, "Not Found");
        }
    }

    private void handleGetHotels(HttpExchange exchange) throws IOException {
        List<HotelProperty> properties = loadCsvProperties();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (HotelProperty prop : properties) {
            ProviderIdResolverService.ProviderIdMapping mapping = idResolver.getMapping(prop.getId());
            String pid = mapping != null ? mapping.getGooglePropertyToken() : null;
            List<String> rmEmails = mapping != null && mapping.getRmEmails() != null
                    ? mapping.getRmEmails()
                    : Collections.<String>emptyList();

            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", prop.getId());
            item.put("name", prop.getName());
            item.put("city", prop.getCity() != null ? prop.getCity() : "");
            item.put("country", prop.getCountry() != null ? prop.getCountry() : "");
            item.put("googlePropertyToken", pid);
            item.put("googleVerifyUrl", (pid != null && !pid.isEmpty()) ? GOOGLE_ENTITY_PREFIX + pid : null);
            item.put("rmEmails", rmEmails);
            item.put("updatedAt", mapping != null ? mapping.getUpdatedAt() : null);
            rows.add(item);
        }

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("defaultRmEmails", config.getDefaultRmEmails());
        resp.put("hotels", rows);
        sendJson(exchange, 200, resp);
    }

    private void handleSaveHotel(HttpExchange exchange) throws IOException {
        String body = readBody(exchange.getRequestBody());
        JsonNode json = objectMapper.readTree(body);
        String propertyId = json.path("propertyId").asText("").trim();
        String pidOrUrl = json.path("pidOrUrl").asText("").trim();
        String rmEmailsRaw = json.path("rmEmails").asText("").trim();

        HotelProperty target = findProperty(propertyId);
        if (target == null) {
            sendJsonError(exchange, 404, "Property '" + propertyId + "' not found in CSV.");
            return;
        }

        if (!pidOrUrl.isEmpty()) {
            String extractedPid = GoogleTravelUrlParser.extractPid(pidOrUrl);
            if (extractedPid == null || extractedPid.isEmpty()) {
                sendJsonError(exchange, 400,
                        "Could not extract a Google Hotel PID from that input. "
                                + "On Google Travel, click into the specific hotel first (so the URL has /entity/... or a hotel qs= token), "
                                + "or paste the Ch... PID directly.");
                return;
            }
            idResolver.saveGooglePropertyToken(target, extractedPid);
        }

        List<String> emailList = new ArrayList<String>();
        if (!rmEmailsRaw.isEmpty()) {
            emailList.add(rmEmailsRaw);
        }
        idResolver.saveRmEmails(target, emailList);

        ProviderIdResolverService.ProviderIdMapping updated = idResolver.getMapping(target.getId());
        String finalPid = updated != null ? updated.getGooglePropertyToken() : null;
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("ok", true);
        resp.put("propertyId", target.getId());
        resp.put("googlePropertyToken", finalPid);
        resp.put("googleVerifyUrl", (finalPid != null && !finalPid.isEmpty()) ? GOOGLE_ENTITY_PREFIX + finalPid : null);
        resp.put("rmEmails", updated != null && updated.getRmEmails() != null ? updated.getRmEmails() : Collections.emptyList());
        sendJson(exchange, 200, resp);
    }

    private HotelProperty findProperty(String propertyId) {
        if (propertyId == null || propertyId.isEmpty()) {
            return null;
        }
        for (HotelProperty prop : loadCsvProperties()) {
            if (prop.getId() != null && propertyId.equalsIgnoreCase(prop.getId().trim())) {
                return prop;
            }
        }
        return null;
    }

    private List<HotelProperty> loadCsvProperties() {
        try {
            return csvLoader.loadProperties(config.getPropertiesCsvPath());
        } catch (Exception e) {
            log.error("Failed to load CSV properties for Admin UI: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String expectedPassword = config.getAdminAuthPassword();
        if (expectedPassword == null || expectedPassword.isEmpty()) {
            return true;
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authHeader.substring("Basic ".length()).trim()),
                    StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) {
                return false;
            }
            String user = decoded.substring(0, sep);
            String pass = decoded.substring(sep + 1);
            return config.getAdminAuthUsername().equals(user) && expectedPassword.equals(pass);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String readBody(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = is.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<String, Object>();
        err.put("ok", false);
        err.put("error", message);
        sendJson(exchange, status, err);
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String buildAdminHtml() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>Hotel Parity Checker - Admin</title>\n"
                + "<style>\n"
                + "  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f3f4f6; color: #111827; margin: 0; padding: 20px; }\n"
                + "  .container { max-width: 1200px; margin: 0 auto; }\n"
                + "  .header { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 18px; }\n"
                + "  .header h1 { margin: 0; font-size: 22px; }\n"
                + "  .meta { font-size: 13px; color: #4b5563; background: #fff; padding: 10px 14px; border-radius: 8px; border: 1px solid #e5e7eb; margin-bottom: 16px; }\n"
                + "  .search { padding: 8px 12px; border: 1px solid #d1d5db; border-radius: 6px; width: 260px; font-size: 14px; }\n"
                + "  .card { background: #fff; border-radius: 10px; border: 1px solid #e5e7eb; overflow-x: auto; box-shadow: 0 1px 2px rgba(0,0,0,0.04); }\n"
                + "  table { width: 100%; border-collapse: collapse; font-size: 13px; }\n"
                + "  th { background: #1f2937; color: #fff; text-align: left; padding: 10px 12px; white-space: nowrap; }\n"
                + "  td { padding: 10px 12px; border-top: 1px solid #e5e7eb; vertical-align: top; }\n"
                + "  input[type=text] { width: 100%; box-sizing: border-box; padding: 7px 9px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }\n"
                + "  .btn { background: #2563eb; color: #fff; border: none; padding: 8px 14px; border-radius: 6px; cursor: pointer; font-weight: 600; font-size: 13px; white-space: nowrap; }\n"
                + "  .btn:hover { background: #1d4ed8; }\n"
                + "  .btn:disabled { background: #93c5fd; cursor: not-allowed; }\n"
                + "  .verify-link { display: inline-block; margin-top: 4px; font-size: 12px; color: #2563eb; text-decoration: none; font-weight: 600; }\n"
                + "  .pid-code { font-family: monospace; font-size: 11px; color: #374151; word-break: break-all; }\n"
                + "  .msg { margin-top: 5px; font-size: 12px; }\n"
                + "  .msg.ok { color: #059669; font-weight: 600; }\n"
                + "  .msg.err { color: #dc2626; font-weight: 600; }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div class=\"container\">\n"
                + "  <div class=\"header\">\n"
                + "    <h1>Hotel Parity Checker — Admin Configuration</h1>\n"
                + "    <input id=\"searchBox\" class=\"search\" type=\"text\" placeholder=\"Filter by ID or hotel name...\" />\n"
                + "  </div>\n"
                + "  <div class=\"meta\" id=\"metaBox\">Loading configuration...</div>\n"
                + "  <div class=\"card\">\n"
                + "    <table>\n"
                + "      <thead>\n"
                + "        <tr>\n"
                + "          <th>Property</th>\n"
                + "          <th>Current PID &amp; Verify</th>\n"
                + "          <th>Paste Google Travel URL or PID (auto-extracts PID)</th>\n"
                + "          <th>Parity Alert Email(s) (comma-separated)</th>\n"
                + "          <th>Action</th>\n"
                + "        </tr>\n"
                + "      </thead>\n"
                + "      <tbody id=\"hotelRows\"></tbody>\n"
                + "    </table>\n"
                + "  </div>\n"
                + "</div>\n"
                + "<script>\n"
                + "const BASE = window.location.pathname.replace(/\\/$/, '');\n"
                + "let ALL_HOTELS = [];\n"
                + "let DEFAULT_EMAILS = '';\n"
                + "async function loadHotels() {\n"
                + "  const res = await fetch(BASE + '/api/hotels');\n"
                + "  const data = await res.json();\n"
                + "  ALL_HOTELS = data.hotels || [];\n"
                + "  DEFAULT_EMAILS = data.defaultRmEmails || '';\n"
                + "  document.getElementById('metaBox').innerHTML = '<strong>Default / CC Emails (rm.email.default):</strong> ' + escapeHtml(DEFAULT_EMAILS) + ' &nbsp;|&nbsp; <em>Hotels with custom emails send To those emails and CC the default emails.</em>';\n"
                + "  renderRows();\n"
                + "}\n"
                + "function escapeHtml(s) { return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;'); }\n"
                + "function renderRows() {\n"
                + "  const q = document.getElementById('searchBox').value.trim().toLowerCase();\n"
                + "  const tbody = document.getElementById('hotelRows');\n"
                + "  const filtered = ALL_HOTELS.filter(h => !q || h.id.toLowerCase().includes(q) || h.name.toLowerCase().includes(q));\n"
                + "  tbody.innerHTML = filtered.map(h => {\n"
                + "    const pidHtml = h.googlePropertyToken\n"
                + "      ? '<div class=\"pid-code\">' + escapeHtml(h.googlePropertyToken) + '</div><a class=\"verify-link\" href=\"' + escapeHtml(h.googleVerifyUrl) + '\" target=\"_blank\" rel=\"noopener noreferrer\">Verify on Google Hotels &#8599;</a>'\n"
                + "      : '<span style=\"color:#dc2626;font-weight:600\">Missing PID</span>';\n"
                + "    const emailsVal = (h.rmEmails || []).join(', ');\n"
                + "    return `<tr id=\"row-${escapeHtml(h.id)}\">\n"
                + "      <td><strong>${escapeHtml(h.id)}</strong><br/>${escapeHtml(h.name)}<br/><span style=\"color:#6b7280;font-size:12px\">${escapeHtml(h.city)} ${escapeHtml(h.country)}</span></td>\n"
                + "      <td style=\"min-width:190px\">${pidHtml}</td>\n"
                + "      <td style=\"min-width:260px\"><input type=\"text\" id=\"pid-${escapeHtml(h.id)}\" placeholder=\"Paste Google Travel URL or Ch... PID\" /></td>\n"
                + "      <td style=\"min-width:240px\"><input type=\"text\" id=\"email-${escapeHtml(h.id)}\" value=\"${escapeHtml(emailsVal)}\" placeholder=\"Default: ${escapeHtml(DEFAULT_EMAILS)}\" /></td>\n"
                + "      <td><button class=\"btn\" onclick=\"saveHotel('${escapeHtml(h.id)}', this)\">Save</button><div class=\"msg\" id=\"msg-${escapeHtml(h.id)}\"></div></td>\n"
                + "    </tr>`;\n"
                + "  }).join('');\n"
                + "}\n"
                + "async function saveHotel(id, btn) {\n"
                + "  const pidInput = document.getElementById('pid-' + id);\n"
                + "  const emailInput = document.getElementById('email-' + id);\n"
                + "  const msgEl = document.getElementById('msg-' + id);\n"
                + "  btn.disabled = true; msgEl.className = 'msg'; msgEl.textContent = 'Saving...';\n"
                + "  try {\n"
                + "    const res = await fetch(BASE + '/api/hotel/save', {\n"
                + "      method: 'POST', headers: {'Content-Type': 'application/json'},\n"
                + "      body: JSON.stringify({ propertyId: id, pidOrUrl: pidInput.value, rmEmails: emailInput.value })\n"
                + "    });\n"
                + "    const data = await res.json();\n"
                + "    if (!res.ok || !data.ok) { msgEl.className = 'msg err'; msgEl.textContent = data.error || 'Save failed'; return; }\n"
                + "    const idx = ALL_HOTELS.findIndex(x => x.id === id);\n"
                + "    if (idx >= 0) { ALL_HOTELS[idx].googlePropertyToken = data.googlePropertyToken; ALL_HOTELS[idx].googleVerifyUrl = data.googleVerifyUrl; ALL_HOTELS[idx].rmEmails = data.rmEmails; }\n"
                + "    renderRows();\n"
                + "    const newMsg = document.getElementById('msg-' + id);\n"
                + "    if (newMsg) { newMsg.className = 'msg ok'; newMsg.textContent = 'Saved!'; }\n"
                + "  } catch (e) { msgEl.className = 'msg err'; msgEl.textContent = e.message; }\n"
                + "  finally { btn.disabled = false; }\n"
                + "}\n"
                + "document.getElementById('searchBox').addEventListener('input', renderRows);\n"
                + "loadHotels();\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>";
    }
}
