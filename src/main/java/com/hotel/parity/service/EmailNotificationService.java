package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.HotelProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.DataHandler;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Properties;

/**
 * Sends HTML parity alert emails via SMTP with an inline JFreeChart PNG (cid:parityChart)
 * and a 90-day CSV report attachment.
 */
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final AppConfig config;
    private final ProviderIdResolverService idResolverService;

    public EmailNotificationService(AppConfig config) {
        this(config, null);
    }

    public EmailNotificationService(AppConfig config, ProviderIdResolverService idResolverService) {
        this.config = config;
        this.idResolverService = idResolverService;
    }

    public void sendParityAlert(HotelProperty property,
                                int breachedDaysCount,
                                String htmlBody,
                                byte[] chartPngBytes,
                                byte[] csvBytes) throws MessagingException, IOException {
        RecipientRouting routing = resolveRecipientRouting(property);
        String toRecipients = routing.getTo();
        String ccRecipients = routing.getCc();

        String subject = breachedDaysCount > 0
                ? String.format("[ACTION REQUIRED] Rate Parity Breach Alert: %s (%s) - %d Days Undercut",
                property.getName(), property.getId(), breachedDaysCount)
                : String.format("[Weekly Summary] Rate Parity OK: %s (%s)",
                property.getName(), property.getId());

        String csvFilename = String.format("parity-report-%s-%s.csv", property.getId(), LocalDate.now());

        if (config.isEmailDryRun()) {
            log.info("[EMAIL DRY-RUN] Would send email to '{}' (cc: '{}') with subject '{}', chart={} bytes, csv={} bytes",
                    toRecipients, ccRecipients != null ? ccRecipients : "-", subject,
                    chartPngBytes != null ? chartPngBytes.length : 0,
                    csvBytes != null ? csvBytes.length : 0);
            saveDryRunArtifacts(property.getId(), toRecipients, ccRecipients, subject, htmlBody, chartPngBytes, csvBytes, csvFilename);
            return;
        }

        Session session = createMailSession();
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(config.getSmtpFrom()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toRecipients));
        if (ccRecipients != null && !ccRecipients.isEmpty()) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(ccRecipients));
        }
        message.setSubject(subject, "UTF-8");

        // Related part for HTML + inline cid:parityChart (no CSV attachment)
        MimeMultipart relatedMultipart = new MimeMultipart("related");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
        relatedMultipart.addBodyPart(htmlPart);

        if (chartPngBytes != null && chartPngBytes.length > 0) {
            MimeBodyPart imagePart = new MimeBodyPart();
            imagePart.setDataHandler(new DataHandler(new ByteArrayDataSource(chartPngBytes, "image/png")));
            imagePart.setHeader("Content-ID", "<parityChart>");
            imagePart.setDisposition(MimeBodyPart.INLINE);
            imagePart.setFileName("parity-chart.png");
            relatedMultipart.addBodyPart(imagePart);
        }

        message.setContent(relatedMultipart);
        Transport.send(message);
        log.info("Sent parity email for {} ({}) to {} (cc: {})",
                property.getName(), property.getId(), toRecipients, ccRecipients != null ? ccRecipients : "-");
    }

    private Session createMailSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(config.getSmtpPort()));
        props.put("mail.smtp.auth", String.valueOf(config.isSmtpAuth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.isSmtpStartTls()));

        if (config.isSmtpAuth()) {
            final String username = config.getSmtpUsername();
            final String password = config.getSmtpPassword();
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
        }
        return Session.getInstance(props);
    }

    private void saveDryRunArtifacts(String propertyId,
                                     String toRecipients,
                                     String ccRecipients,
                                     String subject,
                                     String htmlBody,
                                     byte[] chartPngBytes,
                                     byte[] csvBytes,
                                     String csvFilename) throws IOException {
        File dir = new File("reports");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String chartFilename = "parity-chart-" + propertyId + ".png";
        if (chartPngBytes != null && chartPngBytes.length > 0) {
            File chartFile = new File(dir, chartFilename);
            try (FileOutputStream fos = new FileOutputStream(chartFile)) {
                fos.write(chartPngBytes);
            }
        }
        if (csvBytes != null && csvBytes.length > 0) {
            File csvFile = new File(dir, csvFilename);
            try (FileOutputStream fos = new FileOutputStream(csvFile)) {
                fos.write(csvBytes);
            }
        }
        if (htmlBody != null && !htmlBody.isEmpty()) {
            String ccLine = (ccRecipients != null && !ccRecipients.isEmpty())
                    ? "<strong>CC:</strong> " + escapeHtml(ccRecipients) + "<br/>"
                    : "";
            String previewBanner = "<div style=\"background:#f3f4f6;border:1px solid #d1d5db;padding:10px 14px;margin-bottom:16px;font-family:Arial,sans-serif;font-size:13px;color:#374151;\">"
                    + "<strong>Email Preview (Dry Run)</strong><br/>"
                    + "<strong>To:</strong> " + escapeHtml(toRecipients) + "<br/>"
                    + ccLine
                    + "<strong>Subject:</strong> " + escapeHtml(subject)
                    + "</div>";
            String previewHtml = htmlBody
                    .replace("cid:parityChart", chartFilename)
                    .replaceFirst("(?i)<body([^>]*)>", "<body$1>" + previewBanner);
            File htmlFile = new File(dir, String.format("parity-email-%s-%s.html", propertyId, LocalDate.now()));
            try (FileOutputStream fos = new FileOutputStream(htmlFile)) {
                fos.write(previewHtml.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public RecipientRouting resolveRecipientRouting(HotelProperty property) {
        String hotelEmails = (idResolverService != null && property != null)
                ? idResolverService.getRmEmailsForProperty(property.getId())
                : null;
        if ((hotelEmails == null || hotelEmails.isEmpty()) && property != null) {
            hotelEmails = config.getRmEmailOverride(property.getId());
        }
        String defaultEmails = config.getDefaultRmEmails();
        if (hotelEmails != null && !hotelEmails.isEmpty()) {
            return new RecipientRouting(hotelEmails, defaultEmails);
        }
        return new RecipientRouting(defaultEmails, null);
    }

    public static final class RecipientRouting {
        private final String to;
        private final String cc;

        public RecipientRouting(String to, String cc) {
            this.to = to;
            this.cc = cc;
        }

        public String getTo() {
            return to;
        }

        public String getCc() {
            return cc;
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
