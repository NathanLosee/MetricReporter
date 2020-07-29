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
        float[] thresholds;
        String header;
        String simpleRow;

        public Metric() {
            baseValue = Float.NaN;
            testValue = Float.NaN;
        }
    }

    // We use a LinkedHashMap for the metrics to preserve ordering, using the metric names as the keys
    public static LinkedHashMap<String, Metric> metrics;

    public static Properties metricProperties = new Properties();
    public static String metricPropertiesFile = "metrics.properties";

    // Metric details are now managed in CSV files for easier editing
    public static String metricCSV;

    public static LocalDateTime startDT, endDT;
    public static String timeParamString;

    public static void main(String[] args) throws Exception {
        LoadMetricProperties();

        Scanner inScanner = new Scanner(System.in);
        SetCSVFile(inScanner);
        SetTime(inScanner);
        inScanner.close();

        InitializeMetrics();

        PrintResults(false);
        PrintResults(true);
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
    public static void InitializeMetrics() throws Exception {
        BufferedReader csvReader;
        // Need to determine if running from a jar and pull the CSV accordingly
        String resourcePath = MetricReporter.class.getResource("MetricReporter.class").toString();
        if (resourcePath.startsWith("jar:")) {
            csvReader = new BufferedReader(new InputStreamReader(MetricReporter.class.getResourceAsStream(metricProperties.getProperty("csv_" + metricCSV))));
        } else {
            csvReader = new BufferedReader(new FileReader(metricProperties.getProperty("csv_" + metricCSV)));
        }
        // Skip header row
        csvReader.readLine();

        metrics = new LinkedHashMap<>();
        String metricRow;
        while ((metricRow = csvReader.readLine()) != null) {

            // This if allows the use of empty lines in the CSV for grouping related data
            if (!metricRow.equals("")) {
                String[] metricData = metricRow.split(",");

                System.out.println("Gathering metrics for: " + metricData[0]);
    
                Metric newMetric = new Metric();
                metrics.put(metricData[0], newMetric);
    
                if (metricData[0].contains("simple_")) {
                    SetSimpleMetric(metricData);
                } else {
                    SetMetricValues(metricData);
                    SetMetricThresholds(metricData);
                    SetMetricHeader(metricData);
                }
            }

        }

        csvReader.close();
    }
    /*
     * Some metrics only require some simple text to be reported, they are specified in the CSV as follows:
     *      METRIC (metricData[0]) will have the prefix 'simple_' to indentify it as a simple metric
     *      The remaining columns are simple text for inputing into the report
     */
    public static void SetSimpleMetric(String[] metricData) {
        metrics.get(metricData[0]).simpleRow = String.join(",", Arrays.copyOfRange(metricData, 1, metricData.length));
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
        switch (metricData[1]) {
        case "Derived_GC":
            metrics.get(metricData[0]).baseValue = Derived_GC(metricData[2], true);
            metrics.get(metricData[0]).testValue = Derived_GC(metricData[2], false);
            break;
        case "Derived_Error":
            metrics.get(metricData[0]).baseValue = Derived_Error(metricData[2], true);
            metrics.get(metricData[0]).testValue = Derived_Error(metricData[2], false);
            break;
        case "Derived_Error2":
            metrics.get(metricData[0]).baseValue = Derived_Error2(metricData[2], true);
            metrics.get(metricData[0]).testValue = Derived_Error2(metricData[2], false);
            break;
        default:
            metrics.get(metricData[0]).baseValue = Integer.parseInt(metricData[1]);
            metrics.get(metricData[0]).testValue = GetTestValue(metricData[0], metricData[2] + timeParamString);
            break;
        }
    }
    /*
     * A command string is put together that will contain the credentials and metric path to retrieve
     * a test value from AppD REST API which is then executed, and the response is read and parsed
     */
    public static float GetTestValue(String metric, String metricPath) throws Exception {
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
     * Here we set the header column for the metric as specified in the CSV:
     *      ROW_HEADER (metricData[5]) is the header to identify the metric row in the report
     */
    public static void SetMetricHeader(String[] metricData) {
        metrics.get(metricData[0]).header = metricData[5];
    }

    /*
     * Prints the metric data out to the console/CSV report file
     */
    public static void PrintResults(boolean printToFile) throws Exception {
        // If we are printing to the CSV report file, set System.out to the file
        if (printToFile) {
            System.setOut(new PrintStream(new File("stats.csv")));
        } // Else we are using the console

        // Table header row
        System.out.print("Test Duration,");
        System.out.print(
            startDT.getDayOfMonth() + "-" +
            startDT.getMonth().name().substring(0, 3) + "-" +
            startDT.getYear() + " " +
            startDT.toString().substring(11,16) + " - " +
            endDT.toString().substring(11,16)
        );
        System.out.print(", Diff Values,");
        System.out.println("Stats (metrics) Summary");

        // Print out each of the metric rows
        metrics.forEach((metricName, metric) -> {
            if (metricName.contains("simple_")) {
                // No calculations needed, just printing
                System.out.println(metric.simpleRow);
            } else {
                PrintMetricHeader(metric);
                PrintMetricTestValue(metric);

                float diff = Float.NaN;
                if (!Float.isNaN(metric.testValue) && !Float.isNaN(metric.baseValue)) {
                    diff = metric.testValue - metric.baseValue;
                }
                PrintMetricDiffValue(metricName, diff);
                PrintMetricDiffAnalysis(metricName, metric, diff);
            }
        });
    }
    /*
     * Prints the metric's header
     */
    public static void PrintMetricHeader(Metric metric) {
        System.out.print(metric.header + ",");
    }
    /*
     * Prints the metric's test value
     */
    public static void PrintMetricTestValue(Metric metric) {
        System.out.printf("%.2f,", metric.testValue);
    }
    /*
     * Prints the difference between the metric's baseline value and test value
     */
    public static void PrintMetricDiffValue(String metricName, float diff) {
        if (Float.isNaN(diff)) {
            System.out.print(metricName + " is N/A to calculate,");
        } else {
            System.out.printf("%.2f,", diff);
        }
    }
    /*
     * Prints the analysis on the metric's difference
     */
    public static void PrintMetricDiffAnalysis(String metricName, Metric metric, float diff) {
        if (Float.isNaN(diff)) {
            System.out.println("Cannot calculate diff");
        } else {
            // If the threshholds are positive, then we compare whether the diff is high
            if (metric.thresholds[0] > 0) {
                if(diff >= metric.thresholds[0]) {
                    System.out.println(metricName + " significantly increased - Alert");
                } else if (diff >= metric.thresholds[1]) {
                    System.out.println(metricName + " increased - CHECK");
                } else if (diff < 0.0) {
                    System.out.println(metricName + " decreased - good");
                } else {
                    System.out.println("comparable");
                }
            // If the threshholds are negative, then we compare whether the diff is low
            } else {
                if(diff <= metric.thresholds[0]) {
                    System.out.println(metricName + " significantly decreased - Alert");
                } else if (diff <= metric.thresholds[1]) {
                    System.out.println(metricName + " decreased - CHECK");
                } else if (diff > 0.0) {
                    System.out.println(metricName + " increased - good");
                } else {
                    System.out.println("comparable");
                }
            }
        }
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
     * Function for percentage values derived from ratio of errors to calls, ignores NaN errors
     * REQUIRES: Two metrics - number of calls made, number of errors made
     */
    public static float Derived_Error2(String derivedMetricsString, boolean isBase) {
        String[] derivedMetrics = derivedMetricsString.split(":");
        String callsMetric = derivedMetrics[0];
        String errorsMetric = derivedMetrics[1];

        float errorsMetricValue, callsMetricValue;
        if (isBase) {
            errorsMetricValue = metrics.get(errorsMetric).baseValue;
            if (Float.isNaN(errorsMetricValue)) {
                errorsMetricValue = 0;
                metrics.get(errorsMetric).baseValue = 0;
            }
            callsMetricValue = metrics.get(callsMetric).baseValue;
        } else {
            errorsMetricValue = metrics.get(errorsMetric).testValue;
            if (Float.isNaN(errorsMetricValue)) {
                errorsMetricValue = 0;
                metrics.get(errorsMetric).testValue = 0;
            }
            callsMetricValue = metrics.get(callsMetric).testValue;
        }

        return (errorsMetricValue / callsMetricValue) * 100;
    }
}