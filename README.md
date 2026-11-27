# Multi-Property Hotel Rate Parity Monitoring System

A Java 1.8 Maven application that monitors daily room rate parity across multiple hotel properties over a rolling **90-day window** (Current Date + 90 days). On each run, it compares each property's direct booking engine rate against major OTAs (Agoda, Klook, Booking.com, Trip.com, etc.) and emails the Revenue Manager with a visual comparison chart and CSV report whenever an OTA undercuts the direct website rate (`LowestOtaPrice < WebsitePrice`).

---

## Key Features

- **Weekly Scheduled Execution + Manual Trigger:** Runs on a configurable Quartz cron schedule (default: every Monday at 08:00 AM) and supports immediate execution via `--run-now`.
- **4-Tier Cascading OTA Fallback Chain:**
  1. **Primary:** SerpApi (Google Hotels API)
  2. **Secondary Fallback:** DataForSEO (Google Hotels Live API)
  3. **Tertiary Fallback:** Makcorps (Hotel Price Comparison API)
  4. **Final Fallback:** Xotelo (Free Hotel Rates API)
- **Per-Run Provider Circuit Breaker:** If an OTA provider fails (e.g. missing credentials, HTTP 401/429/5xx, or network error), it is disabled for the remainder of that run instead of being retried across the remaining days.
- **Provider ID Resolution & Caching:** Resolves property names and geo-coordinates (`latitude`/`longitude`) to Makcorps `hotelid` and Xotelo `hotel_key`, persisting mappings in `config/provider-id-cache.json`.
- **Parity Normalization:** Enforces consistent tax-exclusive (`BEFORE_TAX`) rate comparison and normalizes currencies to each property's local currency.
- **Visual Chart, CSV & Email Alerts:** Generates a 90-day dual-series PNG line chart (`JFreeChart`), a 90-day side-by-side CSV report (`OpenCSV`), and sends an HTML email with inline chart (`cid:parityChart`) and CSV attachment (`JavaMail`), with per-property Revenue Manager email overrides.

---

## Tech Stack

- **Java:** 1.8
- **Build Tool:** Apache Maven
- **HTTP Client:** OkHttp3 (`3.14.9`) with connection pooling and exponential-backoff retry interceptor
- **CSV / JSON:** OpenCSV (`5.7.1`) & Jackson (`2.15.3`)
- **Charting:** JFreeChart (`1.5.4`)
- **Email:** JavaMail (`1.6.2`)
- **Scheduling:** Quartz (`2.3.2`)
- **Logging:** SLF4J + Logback (`1.2.13`)
- **Testing:** JUnit 4 + Mockito

---

## Project Structure

```text
HotelParityChecker/
├── pom.xml
├── config/
│   └── google-hotel-list.csv
└── src/
    ├── main/
    │   ├── java/com/hotel/parity/
    │   │   ├── MainApplication.java
    │   │   ├── config/
    │   │   │   ├── AppConfig.java
    │   │   │   ├── CsvPropertyLoaderService.java
    │   │   │   └── OkHttpClientFactory.java
    │   │   ├── model/
    │   │   │   ├── DailyRate.java
    │   │   │   ├── HotelProperty.java
    │   │   │   └── ParityComparisonResult.java
    │   │   ├── provider/
    │   │   │   ├── CascadingOtaRateService.java
    │   │   │   ├── DataForSeoOtaProvider.java
    │   │   │   ├── DirectHotelWebsiteProvider.java
    │   │   │   ├── MakcorpsOtaProvider.java
    │   │   │   ├── OtaRateProvider.java
    │   │   │   ├── SerpApiOtaProvider.java
    │   │   │   ├── WebsiteRateProvider.java
    │   │   │   └── XoteloOtaProvider.java
    │   │   ├── scheduler/
    │   │   │   └── WeeklyParityJob.java
    │   │   └── service/
    │   │       ├── ChartGeneratorService.java
    │   │       ├── EmailNotificationService.java
    │   │       ├── ParityAnalyzerService.java
    │   │       ├── ProviderIdResolverService.java
    │   │       └── ReportExportService.java
    │   └── resources/
    │       ├── application.properties
    │       └── logback.xml
    └── test/
        └── java/com/hotel/parity/
            ├── config/CsvPropertyLoaderServiceTest.java
            ├── provider/CascadingOtaRateServiceTest.java
            └── service/ParityAnalyzerServiceTest.java
```

---

## Configuration

### 1. `application.properties`
Edit `src/main/resources/application.properties` (or pass an external file via `-Dconfig.file=/path/to/application.properties`):

- **Property CSV & ID Cache:**
  - `properties.csv.path=config/google-hotel-list.csv`
  - `provider.id.cache.path=config/provider-id-cache.json`
- **Schedule & Window:**
  - `weekly.cron.expression=0 0 8 ? * MON`
  - `monitoring.days.window=90`
  - `parity.breach.threshold=1.00`
- **Direct Website Provider:**
  - `direct.website.api.base.url`, `direct.website.api.key`, `direct.website.mock.enabled=true`
- **OTA Provider Credentials:**
  - `serpapi.api.key`
  - `dataforseo.username`, `dataforseo.password`
  - `makcorps.api.key`
  - `xotelo.base.url=https://data.xotelo.com`
- **SMTP & Revenue Manager Recipients:**
  - `smtp.host`, `smtp.port`, `smtp.username`, `smtp.password`, `smtp.auth`, `smtp.starttls`, `smtp.from`
  - `rm.email.default=revenue@company.com`
  - Per-property overrides: `rm.email.<PROPERTY_ID>=rm@hotel.com`
  - `email.dry.run=true` (writes charts and CSVs to `reports/` without sending SMTP emails)

### 2. Input CSV Schema (`config/google-hotel-list.csv`)
Must use the exact 15-column header:

```csv
id,name,addr1,addr2,city,province,postal_code,country,latitude,longitude,category,hotel_brand,phone,star_rating,alternate_hotel_id
```

Optional columns (`addr2`, `city`, `province`, `postal_code`, `phone`, `star_rating`) may be empty or `-`.

---

## Build, Test & Run

### Run Unit Tests
```bash
mvn test
```

### Build Runnable JAR
```bash
mvn clean package
```
Generates `target/hotel-parity-checker-1.0.0.jar`.

### Run Immediately (`--run-now`)
```bash
java -jar target/hotel-parity-checker-1.0.0.jar --run-now
```

With an external `application.properties`:
```bash
java -Dconfig.file=/path/to/application.properties -jar target/hotel-parity-checker-1.0.0.jar --run-now
```

### Pull & Cache Google Hotel PIDs (`--resolve-pids`)
Pulls each hotel's Google `googlePropertyToken` (`pid`) via SerpApi/SearchApi, saves it to `config/provider-id-cache.json`, and prints direct Google Hotels entity URLs for manual verification:
```bash
# Resolve and cache any missing PIDs for all properties in CSV
java -jar target/hotel-parity-checker-1.0.0.jar --resolve-pids

# Force re-pull PIDs even if already cached
java -jar target/hotel-parity-checker-1.0.0.jar --resolve-pids --refresh

# Pull/refresh PID for a single property ID
java -jar target/hotel-parity-checker-1.0.0.jar --resolve-pids --property=CHPP --refresh
```

### Run in Weekly Scheduled Mode (with Admin Web UI)
```bash
java -jar target/hotel-parity-checker-1.0.0.jar
```

### Run Admin Web UI Only (`--admin-only`)
```bash
java -jar target/hotel-parity-checker-1.0.0.jar --admin-only
```
Opens at `http://localhost:8080/HotelPairityCheckerAdmin` (configurable via `admin.server.port`, `admin.server.context`, `admin.auth.username`, `admin.auth.password` in `application.properties`).

### Behind Apache Reverse Proxy (Example)
Enable `mod_proxy` and `mod_proxy_http`, then add to your Apache VirtualHost:
```apache
ProxyPreserveHost On
ProxyPass /HotelPairityCheckerAdmin http://127.0.0.1:8080/HotelPairityCheckerAdmin
ProxyPassReverse /HotelPairityCheckerAdmin http://127.0.0.1:8080/HotelPairityCheckerAdmin
```

### Run via Linux Crontab (Zero-Idle / Low-Memory Option)
Runs `--run-now` on schedule with a capped 256 MB heap (0 MB used between runs):
```cron
# Every Monday at 08:00 AM
0 8 * * 1 cd /opt/hotel-parity && /usr/bin/java -Xms64m -Xmx256m -Duser.timezone=Asia/Kuala_Lumpur -Dconfig.file=/opt/hotel-parity/config/application.properties -jar /opt/hotel-parity/hotel-parity-checker-1.0.0.jar --run-now >> /opt/hotel-parity/logs/cron.log 2>&1
```
Start/stop the Admin Web UI on demand using `run-parity-monitor.sh`:
```bash
./run-parity-monitor.sh admin
./run-parity-monitor.sh stop
```

---

## Outputs

- **Logs:** `logs/hotel-parity.log` (daily rolling logs)
- **Provider ID Cache:** `config/provider-id-cache.json`
- **Dry-Run Artifacts (when `email.dry.run=true`):** `reports/parity-chart-<ID>.png` and `reports/parity-report-<ID>-<DATE>.csv`

---

## Quick Manual Verification (Google PID)

To double-check any cached `googlePropertyToken` (`pid`) in `config/provider-id-cache.json`:

1. Open `https://www.google.com/travel/hotels/entity/<pid>` in your browser — it goes straight to that hotel's Google Hotels page.
2. Or check `overview.title` and `overview.address` in `logs/raw-api/<date>/<PROPERTY_ID>/BrightData_*_details.json`.
