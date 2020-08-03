//package com.remy;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import java.io.InputStream;

/*
The purpose of this program is to get the metrics from AppD by hitting the REST APIs.Saves about 1 hour per test report preparation.
Per day basis, ~ 2 hours is saved.
The code was first pushed to github on  24-July-2020.
*/
public class MetricReporter {
    // 
    public static class Metric {
        float baseValue;
        float testValue;
        float diffValue;
        float[] thresholds;

        public Metric() {
            baseValue = Float.NaN;
            testValue = Float.NaN;
            diffValue = Float.NaN;
        }
    }

    // We use a LinkedHashMap for the metrics to preserve ordering, using the metric names as the keys
    public static LinkedHashMap<String, Metric> metrics;

    public static Properties metricProperties = new Properties();
    public static String metricPropertiesFile = "metrics.properties";

    // Metric details are now managed in CSV files for easier editing
    public static String metricCSV;
    public static StringBuilder resultsString;

    public static LocalDateTime startDT, endDT;
    public static String timeParamString;

    public static void main(String[] args) throws Exception {
        LoadMetricProperties();

        Scanner inScanner = new Scanner(System.in);
        SetCSVFile(inScanner);
        SetTime(inScanner);
        inScanner.close();

        BufferedReader csvReader;
        // Need to determine if running from a jar and grab the CSV accordingly
        String resourcePath = MetricReporter.class.getResource("MetricReporter.class").toString();
        String csvPath = metricProperties.getProperty("csv_" + metricCSV);
        if (resourcePath.startsWith("jar:")) {
            csvReader = new BufferedReader(new InputStreamReader(MetricReporter.class.getResourceAsStream(csvPath)));
        } else {
            csvReader = new BufferedReader(new FileReader(csvPath));
        }
        InitializeMetrics(csvReader);
        ReadReportFormat(csvReader);
        csvReader.close();

        FormatResults();
        PrintResults();
    }

    /*
     * Load the properties from metrics.properties
     */
    public static void LoadMetricProperties() {
        try {
            InputStream fin = null;
            metricProperties = new Properties();
            fin = MetricReporter.class.getResourceAsStream(metricPropertiesFile);
            metricProperties.load(fin);
            fin.close();
        } catch(IOException ioE) {
            ioE.printStackTrace();
            System.exit(-1);
        }
    }
    
    /* 
     * Set whether the report is being generated for Online or Focus
     */
    public static void SetCSVFile(Scanner inScanner) {
        System.out.println("Specify which application this report is for, use Online or Focus =");
        metricCSV = inScanner.nextLine();
    }

    /*
     * Set the start and end of the test date and time
     */
    public static void SetTime(Scanner inScanner) {
        System.out.println("Enter the start Datetime in MM/dd/yyyy HH:mm format (24-hour) =");
        String startTimeMetric = inScanner.nextLine();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
        startDT = LocalDateTime.parse(startTimeMetric, formatter);
        endDT = startDT.plusHours(1);

        long startEpoch = startDT.atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        long endEpoch = endDT.atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        timeParamString = "&time-range-type=BETWEEN_TIMES&start-time=" + startEpoch + "&end-time=" + endEpoch;
    }
    
    /*
     * Create the metrics LinkedHashMap and populate it with the baseline and test values, and other reporting data
     */
    public static void InitializeMetrics(BufferedReader csvReader) throws Exception {
        // Skip header row
        csvReader.readLine();

        metrics = new LinkedHashMap<>();
        String metricRow;
        while (!(metricRow = csvReader.readLine()).equals("=====FORMAT=====")) {

            // This if allows the use of empty lines in the CSV for grouping related data
            if (!metricRow.equals("")) {
                String[] metricData = metricRow.split(",");

                System.out.println("Gathering metrics for: " + metricData[0]);
    
                Metric newMetric = new Metric();
                metrics.put(metricData[0], newMetric);
    
                SetMetricValues(metricData);
                SetMetricThresholds(metricData);
            }

        }
    }
    /*  
     * Some values are derived from other metrics rather than being set or retrieved explicitly
     * The function and values being used for these derived metrics are specified in the CSV as follows:
     *     BASE_VALUE (metricData[1]) is the function used
     *     METRIC_PATH (metricData[2]) is the colon-separated list of other metrics used
     * 
     * Otherwise the values are set or retrieved explicitly
     * Metric values that are explicitly set or retrieved are handled in the CSV as follows:
     *     BASE_VALUE (metricData[1]) is the baseline value
     *     METRIC_PATH (metricData[2]) is the URI path to retrieve the value via AppD REST API
     */
    public static void SetMetricValues(String[] metricData) throws Exception {
        Metric metric = metrics.get(metricData[0]);

        switch (metricData[1]) {
        case "Derived_GC":
            metric.baseValue = Derived_GC(metricData[2], true);
            metric.testValue = Derived_GC(metricData[2], false);
            break;
        case "Derived_Error":
            metric.baseValue = Derived_Error(metricData[2], true);
            metric.testValue = Derived_Error(metricData[2], false);
            break;
        case "Derived_Error2":
            metric.baseValue = Derived_Error2(metricData[2], true);
            metric.testValue = Derived_Error2(metricData[2], false);
            break;
        default:
            metric.baseValue = Integer.parseInt(metricData[1]);
            metric.testValue = SetTestValue(metricData[0], metricData[2] + timeParamString);
            break;
        }

        if (!Float.isNaN(metric.baseValue) && !Float.isNaN(metric.testValue)) {
            metric.diffValue = metric.testValue - metric.baseValue;
        }
    }
    /*
     * A command string is put together that will contain the credentials and metric path to retrieve
     * a test value from AppD REST API which is then executed, and the response is read and parsed
     */
    public static float SetTestValue(String metric, String metricPath) throws Exception {
        String commandBase = metricProperties.getProperty("commandBase");
        String token = new String(Base64.getDecoder().decode(metricProperties.getProperty("token")));
        String command = commandBase + token + " \""  + metricPath + "\"";

        Process process = Runtime.getRuntime().exec(command);
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder response = new StringBuilder();
        String lineCurl;
        String valueString = "";

        while ((lineCurl = reader.readLine()) != null) {
            response.append(lineCurl);
        }
        
        try {
            if (metric.contains("Major")) {
                valueString = response.substring(response.indexOf(" <sum>") + 6, response.indexOf("</sum>"));
            } else {
                valueString = response.substring(response.indexOf(" <value>") + 8, response.indexOf("</value>"));
            }
        } catch (Exception e) {

        } finally {
            process.destroy();
            reader.close();
        }

        if (valueString.equals("")) {
            return Float.NaN;
        }
        return Integer.parseInt(valueString);
    }
    /*
     * Here we set the thresholds for quick analysis of differences between baseline and test values as specified in the CSV:
     *      ERROR_THRESHOLD (metricData[3]) is the threshold at which an Error notification will be saved in the report
     *      WARNING_THRESHOLD (metricData[4])) is the threshold at which a Warning notification will be saved in the report
     */
    public static void SetMetricThresholds(String[] metricData) {
        metrics.get(metricData[0]).thresholds = new float[] {
            Float.parseFloat(metricData[3]),
            Float.parseFloat(metricData[4])
        };
    }

    /* 
     * Read the report format set in the CSV file
     */
    public static void ReadReportFormat(BufferedReader csvReader) throws Exception {
        resultsString = new StringBuilder();
        String formatLine;
        while ((formatLine = csvReader.readLine()) != null) {
            resultsString.append(formatLine + "\n");
        }
    }

    /*
     * Prints the metric data out to the console/CSV report file
     */
    public static void FormatResults() throws Exception {
        ReplaceDate();

        // Replace placeholders for each of the metrics
        metrics.forEach((metricName, metric) -> {
            ReplaceMetricTestValue(metric);
            ReplaceMetricDiffValue(metricName, metric);
            ReplaceMetricDiffAnalysis(metricName, metric);
        });
    }
    /*
     * Replaces the date placeholder in the report with the test data
     */
    public static void ReplaceDate() {
        String dateString = startDT.getDayOfMonth() + "-" +
            startDT.getMonth().name().substring(0, 3) + "-" +
            startDT.getYear() + " " +
            startDT.toString().substring(11,16) + " - " +
            endDT.toString().substring(11,16);
        
        int dateIndex = resultsString.indexOf("{date}");
        resultsString.replace(dateIndex, dateIndex + "{date}".length(), dateString);
    }
    /*
     * Replaces the next metric placeholder in the report with the metric's test value
     */
    public static void ReplaceMetricTestValue(Metric metric) {
        String valueString = String.format("%.2f", metric.testValue);

        int metricIndex = resultsString.indexOf("{metric}");
        resultsString.replace(metricIndex, metricIndex + "{metric}".length(), valueString);
    }
    /*
     * Replaces the next metric placeholder in the report with the metric's diff value
     */
    public static void ReplaceMetricDiffValue(String metricName, Metric metric) {
        String valueString;
        if (Float.isNaN(metric.diffValue)) {
            valueString = "NaN";
        } else {
            valueString = String.format("%.2f", metric.diffValue);
        }
        
        int metricIndex = resultsString.indexOf("{metric}");
        resultsString.replace(metricIndex, metricIndex + "{metric}".length(), valueString);
    }
    /*
     * Replaces the next metric placeholder in the report with the metric's diff analysis
     */
    public static void ReplaceMetricDiffAnalysis(String metricName, Metric metric) {
        String valueString;
        if (Float.isNaN(metric.diffValue)) {
            valueString = "Cannot calculate diff";
        } else {
            // If the threshholds are positive, then we compare whether the diff is high
            if (metric.thresholds[0] > 0) {
                if(metric.diffValue >= metric.thresholds[0]) {
                    valueString = "ALERT - significantly increased";
                } else if (metric.diffValue >= metric.thresholds[1]) {
                    valueString = "CHECK - increased";
                } else if (metric.diffValue < 0.0) {
                    valueString = "Good - decreased";
                } else {
                    valueString = "comparable";
                }
            // If the threshholds are negative, then we compare whether the diff is low
            } else {
                if(metric.diffValue <= metric.thresholds[0]) {
                    valueString = "ALERT - significantly decreased";
                } else if (metric.diffValue <= metric.thresholds[1]) {
                    valueString = "CHECK - decreased";
                } else if (metric.diffValue > 0.0) {
                    valueString = "Good - increased";
                } else {
                    valueString = "comparable";
                }
            }
        }
        
        int metricIndex = resultsString.indexOf("{metric}");
        resultsString.replace(metricIndex, metricIndex + "{metric}".length(), valueString);
    }

    /*
     * Prints results to console and file
     */
    public static void PrintResults() throws Exception {
        // Print to console
        System.out.println(resultsString);
        // Print to file
        System.setOut(new PrintStream(new File("stats.csv")));
        System.out.println(resultsString);
    }

    /*
     * Function for percentage values derived from gcTime per minute
     * REQUIRES: One metric - amount of time in GC per minute
     */
    public static float Derived_GC(String gcTimeMetric, boolean isBase) {
        if (isBase) {
            return (metrics.get(gcTimeMetric).baseValue / 60000) * 100;
        } else {
            return (metrics.get(gcTimeMetric).testValue / 60000) * 100;
        }
    }

    /*
     * Function for percentage values derived from ratio of errors to calls
     * REQUIRES: Two metrics - number of calls made, number of errors made
     */
    public static float Derived_Error(String derivedMetricsString, boolean isBase) {
        String[] derivedMetrics = derivedMetricsString.split(":");
        String callsMetric = derivedMetrics[0];
        String errorsMetric = derivedMetrics[1];

        float errorsMetricValue, callsMetricValue;
        if (isBase) {
            errorsMetricValue = metrics.get(errorsMetric).baseValue;
            callsMetricValue = metrics.get(callsMetric).baseValue;
        } else {
            errorsMetricValue = metrics.get(errorsMetric).testValue;
            callsMetricValue = metrics.get(callsMetric).testValue;
        }

        return (errorsMetricValue / callsMetricValue) * 100;
    }
    /*
     * Function for percentage values derived from ratio of errors to calls
     * If errors value is NaN, set it to 0 and recalculate the diff
     * REQUIRES: Two metrics - number of calls made, number of errors made
     */
    public static float Derived_Error2(String derivedMetricsString, boolean isBase) {
        String[] derivedMetrics = derivedMetricsString.split(":");
        Metric callsMetric = metrics.get(derivedMetrics[0]);
        Metric errorsMetric = metrics.get(derivedMetrics[1]);

        float errorsMetricValue, callsMetricValue;
        if (isBase) {
            callsMetricValue = callsMetric.baseValue;
            errorsMetricValue = errorsMetric.baseValue;
            if (Float.isNaN(errorsMetricValue)) {
                errorsMetricValue = 0;
                errorsMetric.baseValue = 0;
            }
        } else {
            callsMetricValue = callsMetric.testValue;
            errorsMetricValue = errorsMetric.testValue;
            if (Float.isNaN(errorsMetricValue)) {
                errorsMetricValue = 0;
                errorsMetric.testValue = 0;
                if (!Float.isNaN(callsMetricValue)) {
                    errorsMetric.diffValue = errorsMetric.testValue - errorsMetric.baseValue;
                }
            }
        }

        return (errorsMetricValue / callsMetricValue) * 100;
    }
}