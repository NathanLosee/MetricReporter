package com.MetricReporter;

import java.io.*;
import java.util.*;

import com.mashape.unirest.http.*;
import com.mashape.unirest.request.HttpRequest;

import org.json.*;

public class MetricReader {
    public static void GatherMetrics() throws Exception {
        LoadURLCodes();
        LoadMetricProperties();
        InitializeMetrics();
    }

    /*
    * Load the properties from metrics.properties
    */
    private static void LoadURLCodes() {
        ProgramData.urlCodes = new HashMap<>();
        ProgramData.urlCodes.put("%20", " ");
        ProgramData.urlCodes.put("%22", "\"");
        ProgramData.urlCodes.put("%25", "%");
        ProgramData.urlCodes.put("%28", "(");
        ProgramData.urlCodes.put("%29", ")");
        ProgramData.urlCodes.put("%2F", "/");
        ProgramData.urlCodes.put("%3A", ":");
        ProgramData.urlCodes.put("%7C", "|");
    }

    /*
     * Load the properties from metrics.properties
     */
    public static void LoadMetricProperties() {
        try {
            ProgramData.metricProperties = new Properties();
            InputStream fin = MetricWriter.class.getResourceAsStream(ProgramData.metricPropertiesFile);
            ProgramData.metricProperties.load(fin);
            fin.close();
        } catch(IOException ioE) {
            ioE.printStackTrace();
            System.exit(-1);
        }
    }
    
    /*
     * Create the metrics LinkedHashMap and populate it with the baseline and test values, and other reporting data
     */
    public static void InitializeMetrics() throws Exception {
        String resource = "/res/Configs/" + ProgramData.config + "/Fields.csv";
        InputStream stream = MetricWriter.class.getResourceAsStream(resource);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        // Skip header row
        reader.readLine();

        ProgramData.metrics = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.equals("")) {
                String[] data = line.split(",");

                System.out.println("Gathering metrics for: " + data[0]);
    
                Metric newMetric = new Metric();
                ProgramData.metrics.put(data[0], newMetric);

                if (!data[2].equals("LAPSE")) {
                    newMetric.values = new float[3];
                    newMetric.values[0] = Float.NaN;
                    newMetric.values[1] = Float.NaN;
                    newMetric.values[2] = Float.NaN;
                }
                newMetric.valueThread = new Thread(() -> {
                    SetMetricValues(data);
                    SetMetricThresholds(data);
                });
                newMetric.valueThread.start();
            }
        }
        ProgramData.metrics.forEach((metricName, metric) -> {
            try {
                metric.valueThread.join();
            } catch (Exception e) { }
        });
    }
    
    public static void SetMetricValues(String[] data) {
        Metric metric = ProgramData.metrics.get(data[0]);
        metric.type = data[2];

        DerivedFunctions.Wait(data[4]);
        if (data[1].contains("Derived")) {
            DerivedFunctions.DeriveValues(metric, data);
        } else {
            if (data[2].equals("LAPSE")) {
                metric.values = SetLapseValues(data[0], data[3], data[4]);
            } else {
                metric.values[0] = Integer.parseInt(data[1]);
                metric.values[1] = SetTestValue(data[0], data[2], data[3], data[4]);
                if (Float.isNaN(metric.values[0])) {
                    metric.values[0] = 0;
                }
                if (Float.isNaN(metric.values[1])) {
                    metric.values[1] = 0;
                }
                metric.values[2] = metric.values[1] - metric.values[0];
            }
        }
    }

    /*
     * An HttpRequest is put together that will contain the credentials and metric path to retrieve
     * a test value from AppD REST API which is then executed, and the response is read and parsed
     */
    public static float[] SetLapseValues(String metric, String app, String metricPath) {
        try {
            String username = ProgramData.metricProperties.getProperty("appdUsername");
            String password = ProgramData.metricProperties.getProperty("appdToken");
            password = new String(Base64.getDecoder().decode(password));
            String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            long startEpoch = ProgramData.startDT.toInstant().toEpochMilli();
            long endEpoch = ProgramData.startDT.plusMinutes(ProgramData.duration).toInstant().toEpochMilli();
            HttpRequest request = Unirest
                    .get("https://papajohns-test.saas.appdynamics.com/controller/rest/applications/{APP}/metric-data")
                    .routeParam("APP", app)
                    .queryString("metric-path", ReplaceURlCodes(metricPath))
                    .queryString("time-range-type", "BETWEEN_TIMES")
                    .queryString("start-time", startEpoch)
                    .queryString("end-time", endEpoch)
                    .queryString("rollup", "false").queryString("output", "JSON")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + authCode);
            HttpResponse<JsonNode> response = request.asJson();

            JSONArray values = response.getBody().getArray();
            JSONObject outerObject = values.getJSONObject(0);
            if (outerObject.get("metricName").equals("METRIC DATA NOT FOUND")) {
                System.out.println("No metric data for " + metric);
                return null;
            }
            JSONArray metricValues = outerObject.getJSONArray("metricValues");
            float[] returnValues = new float[metricValues.length()];
            for (int i = 0; i < metricValues.length(); i++) {
                returnValues[i] = metricValues.getJSONObject(i).getFloat("value");
            }
            return returnValues;
        } catch (Exception e) {
        }
        return null;
    }
    
    /*
     * An HttpRequest is put together that will contain the credentials and metric path to retrieve
     * a test value from AppD REST API which is then executed, and the response is read and parsed
     */
    public static float SetTestValue(String metric, String metricType, String app, String metricPath) {
        try {
            String username = ProgramData.metricProperties.getProperty("appdUsername");
            String password = ProgramData.metricProperties.getProperty("appdToken");
            password = new String(Base64.getDecoder().decode(password));
            String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            long startEpoch = ProgramData.startDT.toInstant().toEpochMilli();
            long endEpoch = ProgramData.startDT.plusMinutes(ProgramData.duration).toInstant().toEpochMilli();
            HttpRequest request = Unirest
                    .get("https://papajohns-test.saas.appdynamics.com/controller/rest/applications/{APP}/metric-data")
                    .routeParam("APP", app)
                    .queryString("metric-path", ReplaceURlCodes(metricPath))
                    .queryString("time-range-type", "BETWEEN_TIMES")
                    .queryString("start-time", startEpoch)
                    .queryString("end-time", endEpoch)
                    .queryString("rollup", "true").queryString("output", "JSON")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + authCode);
            HttpResponse<JsonNode> response = request.asJson();

            JSONArray values = response.getBody().getArray();
            JSONObject outerObject = (JSONObject) values.get(0);
            if (outerObject.get("metricName").equals("METRIC DATA NOT FOUND")) {
                System.out.println("No metric data for " + metric);
                return Float.NaN;
            }
            JSONArray metricValues = (JSONArray) outerObject.get("metricValues");
            JSONObject innerObject = (JSONObject) metricValues.get(0);
            switch (metricType) {
                case "SINGLE":
                    return innerObject.getFloat("value");
                case "SUM":
                    return innerObject.getFloat("sum");
            }
        } catch (Exception e) {
        }
        return Float.NaN;
    }
    
    /*
     * Replaces the URL codes in the metric path so the HttpRequest reads them properly
     */
    public static String ReplaceURlCodes(String path) {
        for (String code : ProgramData.urlCodes.keySet()) {
            path = path.replace(code, ProgramData.urlCodes.get(code));
        }
        return path;
    }

    /*
     * Here we set the thresholds for quick analysis of differences between baseline and test values as specified in the CSV:
     *      ERROR_THRESHOLD (metricData[3]) is the threshold at which an Error notification will be saved in the report
     *      WARNING_THRESHOLD (metricData[4])) is the threshold at which a Warning notification will be saved in the report
     */
    public static void SetMetricThresholds(String[] metricData) {
        ProgramData.metrics.get(metricData[0]).thresholds = new float[] {
            Float.parseFloat(metricData[5]),
            Float.parseFloat(metricData[6])
        };
    }
}
