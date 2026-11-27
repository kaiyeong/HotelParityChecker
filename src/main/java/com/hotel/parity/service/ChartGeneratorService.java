package com.hotel.parity.service;

import com.hotel.parity.model.HotelProperty;
import com.hotel.parity.model.ParityComparisonResult;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.DateTickUnit;
import org.jfree.chart.axis.DateTickUnitType;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Day;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.List;

/**
 * Generates a 90-day dual-series PNG line chart (Website Rate vs. Lowest OTA Rate) using JFreeChart.
 */
public class ChartGeneratorService {

    private static final int DEFAULT_WIDTH = 1000;
    private static final int DEFAULT_HEIGHT = 480;

    static {
        System.setProperty("java.awt.headless", "true");
    }

    public byte[] generateParityChartPng(HotelProperty property,
                                         List<ParityComparisonResult> results) throws IOException {
        int daysWindow = (results != null && !results.isEmpty()) ? results.size() : 90;
        return generateParityChartPng(property, results, daysWindow);
    }

    public byte[] generateParityChartPng(HotelProperty property,
                                         List<ParityComparisonResult> results,
                                         int daysWindow) throws IOException {
        int effectiveDays = daysWindow > 0 ? daysWindow : ((results != null && !results.isEmpty()) ? results.size() : 90);
        TimeSeries websiteSeries = new TimeSeries("Direct Website Rate");
        TimeSeries lowestOtaSeries = new TimeSeries("Lowest OTA Rate");

        for (ParityComparisonResult result : results) {
            LocalDate date = result.getDate();
            Day jfreeDay = new Day(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
            if (result.getWebsitePrice() != null) {
                websiteSeries.addOrUpdate(jfreeDay, result.getWebsitePrice());
            }
            if (result.getLowestOtaPrice() != null) {
                lowestOtaSeries.addOrUpdate(jfreeDay, result.getLowestOtaPrice());
            }
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(websiteSeries);
        dataset.addSeries(lowestOtaSeries);

        String title = property.getName() + " (" + property.getId() + ") - " + effectiveDays + "-Day Rate Parity";
        String yAxisLabel = "Rate (" + property.getDefaultCurrency() + ", Before Tax)";

        JFreeChart chart = ChartFactory.createTimeSeriesChart(
                title,
                "Check-In Date",
                yAxisLabel,
                dataset,
                true,
                false,
                false
        );

        chart.setBackgroundPaint(Color.WHITE);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(new Color(248, 250, 252));
        plot.setDomainGridlinePaint(new Color(220, 224, 230));
        plot.setRangeGridlinePaint(new Color(220, 224, 230));

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, effectiveDays <= 15);
        renderer.setSeriesPaint(0, new Color(37, 99, 235));   // Blue for Direct Website
        renderer.setSeriesStroke(0, new BasicStroke(2.2f));
        renderer.setSeriesPaint(1, new Color(220, 38, 38));   // Red for Lowest OTA
        renderer.setSeriesStroke(1, new BasicStroke(2.2f));
        plot.setRenderer(renderer);

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        int tickDays = effectiveDays <= 15 ? 1 : Math.max(2, (int) Math.ceil(effectiveDays / 15.0));
        domainAxis.setTickUnit(new DateTickUnit(DateTickUnitType.DAY, tickDays));
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MMM dd"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ChartUtils.writeChartAsPNG(baos, chart, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        return baos.toByteArray();
    }
}
