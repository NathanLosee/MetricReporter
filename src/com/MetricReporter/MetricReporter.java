package com.MetricReporter;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.mashape.unirest.http.*;
import com.mashape.unirest.request.HttpRequest;

import org.json.*;

/*
The purpose of this program is to get the metrics from AppD by hitting the REST APIs.Saves about 1 hour per test report preparation.
Per day basis, ~ 2 hours is saved.
The code was first pushed to github on  24-July-2020.
*/
public class MetricReporter {
    // We use a LinkedHashMap for the metrics to preserve ordering, using the metric names as the keys
    public static LinkedHashMap<String, Metric> metrics;

    private static Map<String, String> urlCodes;

    private static Properties metricProperties = new Properties();
    private static String metricPropertiesFile = "/res/metrics.properties";

    private static BufferedReader reader_Fields;
    private static BufferedReader reader_Format;

    private static LocalDateTime startDT, endDT;
    private static Long startEpoch, endEpoch;
    private static String resultsString;

    public static void main(String[] args) throws Exception {
        LoadURLCodes();
        LoadMetricProperties();
        
        String username = metricProperties.getProperty("atlaUsername");
        String password = metricProperties.getProperty("atlaToken");
        String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

        HttpRequest request = Unirest.get("https://pjolodevkb.atlassian.net/wiki/rest/api/content/1519321135")
                .queryString("expand", "body")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + authCode);
        HttpResponse<JsonNode> response = request.asJson();
        System.out.println(response.getBody());

        JSONObject body = new JSONObject();

        /*
        Scanner inScanner = new Scanner(System.in);
        SetConfig(inScanner);
        SetTime(inScanner);
        inScanner.close();
        
        InitializeMetrics();
        ReadReportFormat();
        
        FormatResults();
        PrintResults();
        */
    }

    /*
     * Load the properties from metrics.properties
     */
    public static void LoadURLCodes() {
        urlCodes = new HashMap<>();
        urlCodes.put("%20", " ");
        urlCodes.put("%22", "\"");
        urlCodes.put("%25", "%");
        urlCodes.put("%28", "(");
        urlCodes.put("%29", ")");
        urlCodes.put("%2F", "/");
        urlCodes.put("%3A", ":");
        urlCodes.put("%7C", "|");
    }

    /*
     * Load the properties from metrics.properties
     */
    public static void LoadMetricProperties() {
        try {
            metricProperties = new Properties();
            InputStream fin = MetricReporter.class.getResourceAsStream(metricPropertiesFile);
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
    public static void SetConfig(Scanner inScanner) {
        System.out.println("Specify which application this report is for, use Online or Focus =");
        String config = inScanner.nextLine();
        if (MetricReporter.class.getResource("/res/Configs/" + config) != null) {
            InputStream stream_Fields = MetricReporter.class.getResourceAsStream("/res/Configs/" + config + "/Fields.csv");
            reader_Fields = new BufferedReader(new InputStreamReader(stream_Fields));
            InputStream stream_Format = MetricReporter.class.getResourceAsStream("/res/Configs/" + config + "/Format.csv");
            reader_Format = new BufferedReader(new InputStreamReader(stream_Format));
        }
    }

    /*
     * Set the start and end of the test date and time
     */
    public static void SetTime(Scanner inScanner) {
        System.out.println("Enter the start Datetime in MM/dd/yyyy HH:mm format (24-hour) =");
        String startTime = inScanner.nextLine();
        System.out.println("Enter the duration in HH:mm format (24-hour) =");
        String[] duration = inScanner.nextLine().split(":");

        DateTimeFormatter fullFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
        startDT = LocalDateTime.parse(startTime, fullFormatter);
        endDT = startDT.plusHours(Long.parseLong(duration[0])).plusMinutes(Long.parseLong(duration[1]));

        startEpoch = startDT.atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
        endEpoch = endDT.atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
    }
    
    /*
     * Create the metrics LinkedHashMap and populate it with the baseline and test values, and other reporting data
     */
    public static void InitializeMetrics() throws Exception {
        // Skip header row
        reader_Fields.readLine();

        metrics = new LinkedHashMap<>();
        String line;
        while ((line = reader_Fields.readLine()) != null) {
            if (!line.equals("")) {
                String[] data = line.split(",");

                System.out.println("Gathering metrics for: " + data[0]);
    
                Metric newMetric = new Metric();
                metrics.put(data[0], newMetric);

                newMetric.valueThread = new Thread(() -> {
                    SetMetricValues(data);
                    SetMetricThresholds(data);
                });
                newMetric.valueThread.start();
            }
        }
        metrics.forEach((metricName, metric) -> {
            try {
                metric.valueThread.join();
            } catch (Exception e) { }
        });
    }
    
    public static void SetMetricValues(String[] data) {
        Metric metric = metrics.get(data[0]);

        DerivedFunctions.Wait(data[4]);
        if (data[1].contains("Derived")) {
            DerivedFunctions.DeriveValues(metric, data);
        } else {
            metric.baseValue = Integer.parseInt(data[1]);
            metric.testValue = SetTestValue(data[0], data[2], data[3], data[4]);
        }

        if (Float.isNaN(metric.baseValue)) {
            metric.baseValue = 0;
        }
        if (Float.isNaN(metric.testValue)) {
            metric.testValue = 0;
        }
        metric.diffValue = metric.testValue - metric.baseValue;
    }
    /*
     * A command string is put together that will contain the credentials and metric path to retrieve
     * a test value from AppD REST API which is then executed, and the response is read and parsed
     */
    public static float SetTestValue(String metric, String metricType, String app, String metricPath) {
        try {
            String username = metricProperties.getProperty("appdUsername");
            String password = new String(Base64.getDecoder().decode(metricProperties.getProperty("appdToken")));
            String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            HttpRequest request = Unirest.get("https://papajohns-test.saas.appdynamics.com/controller/rest/applications/{APP}/metric-data")
                    .routeParam("APP", app)
                    .queryString("metric-path", ReplaceURlCodes(metricPath))
                    .queryString("time-range-type", "BETWEEN_TIMES")
                    .queryString("start-time", startEpoch)
                    .queryString("end-time", endEpoch)
                    .queryString("rollup", (metricType.contains("GRAPH") ? "false" : "true"))
                    .queryString("output", "JSON")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + authCode);
            HttpResponse<JsonNode> response = request.asJson();
            
            JSONArray values = response.getBody().getArray();
            JSONObject outerObject = (JSONObject)values.get(0);
            if (outerObject.get("metricName").equals("METRIC DATA NOT FOUND")) {
                System.out.println("No metric data for " + metric);
                return Float.NaN;
            }
            JSONArray metricValues = (JSONArray)outerObject.get("metricValues");
            JSONObject innerObject = (JSONObject)metricValues.get(0);
            switch (metricType) {
                case "SINGLE":
                    return Integer.parseInt(innerObject.get("value").toString());
                case "SUM":
                    return Integer.parseInt(innerObject.get("sum").toString());
            }
        } catch (Exception e) { }
        return Float.NaN;
    }
    /*
     * 
     */
    public static String ReplaceURlCodes(String path) {
        for (String code : urlCodes.keySet()) {
            path = path.replace(code, urlCodes.get(code));
        }
        return path;
    }
    /*
     * Here we set the thresholds for quick analysis of differences between baseline and test values as specified in the CSV:
     *      ERROR_THRESHOLD (metricData[3]) is the threshold at which an Error notification will be saved in the report
     *      WARNING_THRESHOLD (metricData[4])) is the threshold at which a Warning notification will be saved in the report
     */
    public static void SetMetricThresholds(String[] metricData) {
        metrics.get(metricData[0]).thresholds = new float[] {
            Float.parseFloat(metricData[5]),
            Float.parseFloat(metricData[6])
        };
    }

    /* 
     * Read the report format set in the CSV file
     */
    public static void ReadReportFormat() throws Exception {
        StringBuilder resultsBuilder = new StringBuilder();
        String line;
        while ((line = reader_Format.readLine()) != null) {
            resultsBuilder.append(line + "\n");
        }
        resultsString = resultsBuilder.toString();
    }

    /*
     * Prints the metric data out to the console/CSV report file
     */
    public static void FormatResults() throws Exception {
        ReplaceDate();

        // Replace placeholders for each of the metrics
        metrics.forEach((metricName, metric) -> {
            ReplaceMetricTestValue(metricName, metric);
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
            resultsString = resultsString.replace("{date}", dateString);
    }
    /*
     * Replaces the next metric placeholder in the report with the metric's test value
     */
    public static void ReplaceMetricTestValue(String metricName, Metric metric) {
        String valueString = String.format("%.2f", metric.testValue);
        resultsString = resultsString.replace("{"+metricName+"_test}", valueString);
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
        resultsString = resultsString.replace("{"+metricName+"_diff}", valueString);
    }
    /*
     * Replaces the next metric placeholder in the report with the metric's diff analysis
     */
    public static void ReplaceMetricDiffAnalysis(String metricName, Metric metric) {
        String valueString;
        if (Float.isNaN(metric.diffValue)) {
            valueString = "N/A - NaN diff";
        } else {
            // If the threshholds are positive, then we compare whether the diff is high
            if (metric.thresholds[0] > 0) {
                if(metric.diffValue >= metric.thresholds[0]) {
                    valueString = "ALERT";
                } else if (metric.diffValue >= metric.thresholds[1]) {
                    valueString = "WARNING";
                } else if (metric.diffValue < 0.0) {
                    valueString = "Improved";
                } else {
                    valueString = "comparable";
                }
            // If the threshholds are negative, then we compare whether the diff is low
            } else {
                if(metric.diffValue <= metric.thresholds[0]) {
                    valueString = "ALERT";
                } else if (metric.diffValue <= metric.thresholds[1]) {
                    valueString = "WARNING";
                } else if (metric.diffValue > 0.0) {
                    valueString = "Improved";
                } else {
                    valueString = "comparable";
                }
            }
        }
        resultsString = resultsString.replace("{"+metricName+"_anls}", valueString);
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
}