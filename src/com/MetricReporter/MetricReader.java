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
    private static void LoadMetricProperties() {
        try {
            ProgramData.metricProperties = new Properties();
            InputStream fin = MetricWriter.class.getResourceAsStream(ProgramData.metricPropertiesFile);
            ProgramData.metricProperties.load(fin);
            fin.close();
        } catch (IOException ioE) {
            ioE.printStackTrace();
            System.exit(-1);
        }
    }

    /*
     * Create the metrics LinkedHashMap and populate it with the baseline and test
     * values, and other reporting data
     */
    private static void InitializeMetrics() throws Exception {
        String resource = "/res/Configs/" + ProgramData.config + "/Fields.csv";
        InputStream stream = MetricWriter.class.getResourceAsStream(resource);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        // Skip header row
        reader.readLine();

        final String confluenceBody = ReadConfluenceBody();

        ProgramData.metrics = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.equals("")) {
                String[] data = line.split(",");

                System.out.println("Gathering metrics for: " + data[0]);

                Metric newMetric = new Metric();
                ProgramData.metrics.put(data[0], newMetric);

                newMetric.valueThread = new Thread(() -> {
                    SetMetricValues(data, confluenceBody);
                    SetMetricThresholds(data);
                });
                newMetric.valueThread.start();
            }
        }
        ProgramData.metrics.forEach((metricName, metric) -> {
            try {
                metric.valueThread.join();
            } catch (Exception e) {
            }
        });

        reader.close();
    }

    private static String ReadConfluenceBody() throws Exception {
        String username = ProgramData.metricProperties.getProperty("atlaUsername");
        String password = ProgramData.metricProperties.getProperty("atlaToken");
        String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

        HttpResponse<JsonNode> response = Unirest.get("https://pjolodevkb.atlassian.net/wiki/rest/api/content")
                .queryString("type", "page").queryString("title", ProgramData.baselineName)
                .queryString("expand", "body.storage").header("Content-Type", "application/json")
                .header("Authorization", "Basic " + authCode).asJson();
        JSONArray results = response.getBody().getObject().getJSONArray("results");
        if (results.length() > 0) {
            System.out.println("Found baseline page.");
            return results.getJSONObject(0).getJSONObject("body").getJSONObject("storage").getString("value");
        } else {
            System.out.println("Page does not exist.");
            System.exit(0);
            return null;
        }
    }

    private static void SetMetricValues(String[] data, String confluenceBody) {
        Metric metric = ProgramData.metrics.get(data[0]);
        metric.type = data[2];

        switch (data[2]) {
            case "DERIVED_GC":
                Wait(data[4]);
                metric.values = new float[3];

                metric.values[0] = GCPercentage(data[4], true);
                if (Float.isNaN(metric.values[0]))
                    metric.values[0] = 0;

                metric.values[1] = GCPercentage(data[4], false);
                if (Float.isNaN(metric.values[1]))
                    metric.values[1] = 0;

                metric.values[2] = metric.values[1] - metric.values[0];
                break;

            case "DERIVED_ERROR":
                Wait(data[4]);
                metric.values = new float[3];

                metric.values[0] = ErrorPercentage(data[4], true);
                if (Float.isNaN(metric.values[0]))
                    metric.values[0] = 0;

                metric.values[1] = ErrorPercentage(data[4], false);
                if (Float.isNaN(metric.values[1]))
                    metric.values[1] = 0;

                metric.values[2] = metric.values[1] - metric.values[0];
                break;

            case "SINGLE":
                metric.values = new float[3];

                if (confluenceBody != null && confluenceBody.indexOf(data[0]) != -1) {
                    int indexOfMetric = confluenceBody.indexOf(data[0]);
                    String valueString = confluenceBody
                            .substring(indexOfMetric, confluenceBody.indexOf(';', indexOfMetric)).split(":")[1];
                    metric.values[0] = (int) Float.parseFloat(valueString);
                } else {
                    metric.values[0] = 0;
                }

                metric.values[1] = SetTestValue(data[0], data[2], data[3], data[4]);
                if (Float.isNaN(metric.values[1]))
                    metric.values[1] = 0;

                metric.values[2] = metric.values[1] - metric.values[0];
                break;

            case "LAPSE":
                metric.values = SetLapseValues(data[0], data[3], data[4]);
                break;
        }
    }

    /*
     * Function for having derived value threads wait for source values to be
     * evaluated
     */
    public static void Wait(String derivedMetricsString) {
        try {
            String[] derivedMetrics = derivedMetricsString.split(":");
            for (String metric : derivedMetrics) {
                while (!ProgramData.metrics.containsKey(metric)) {
                }
                while (ProgramData.metrics.get(metric).valueThread == null) {
                }
                ProgramData.metrics.get(metric).valueThread.join();
            }
        } catch (Exception e) {
        }
    }

    /*
     * Function for percentage values derived from gcTime per minute REQUIRES: One
     * metric - amount of time in GC per minute
     */
    private static float GCPercentage(String metric, boolean isBase) {
        if (isBase) {
            return (ProgramData.metrics.get(metric).values[0] / 60000) * 100;
        } else {
            return (ProgramData.metrics.get(metric).values[1] / 60000) * 100;
        }
    }

    /*
     * Function for percentage values derived from ratio of errors to calls
     * REQUIRES: Two metrics - number of calls made, number of errors made
     */
    private static float ErrorPercentage(String metric, boolean isBase) {
        String[] sourceMetrics = metric.split(":");
        Metric callsMetric = ProgramData.metrics.get(sourceMetrics[0]);
        Metric errorsMetric = ProgramData.metrics.get(sourceMetrics[1]);

        float errorsMetricValue, callsMetricValue;
        if (isBase) {
            errorsMetricValue = errorsMetric.values[0];
            callsMetricValue = callsMetric.values[0];
        } else {
            errorsMetricValue = errorsMetric.values[1];
            callsMetricValue = callsMetric.values[1];
        }

        return (errorsMetricValue / callsMetricValue) * 100;
    }

    /*
     * An HttpRequest is put together that will contain the credentials and metric
     * path to retrieve a test value from AppD REST API which is then executed, and
     * the response is read and parsed
     */
    private static float SetTestValue(String metric, String metricType, String app, String metricPath) {
        try {
            String username = ProgramData.metricProperties.getProperty("appdUsername");
            String password = ProgramData.metricProperties.getProperty("appdToken");
            password = new String(Base64.getDecoder().decode(password));
            String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            long startEpoch = ProgramData.startDT.toInstant().toEpochMilli();
            long endEpoch = ProgramData.startDT.plusMinutes(ProgramData.duration).toInstant().toEpochMilli();
            HttpRequest request = Unirest
                    .get("https://papajohns-test.saas.appdynamics.com/controller/rest/applications/{APP}/metric-data")
                    .routeParam("APP", app).queryString("metric-path", ReplaceURLCodes(metricPath))
                    .queryString("time-range-type", "BETWEEN_TIMES").queryString("start-time", startEpoch)
                    .queryString("end-time", endEpoch).queryString("rollup", "true").queryString("output", "JSON")
                    .header("Content-Type", "application/json").header("Authorization", "Basic " + authCode);
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
     * An HttpRequest is put together that will contain the credentials and metric
     * path to retrieve a test value from AppD REST API which is then executed, and
     * the response is read and parsed
     */
    private static float[] SetLapseValues(String metric, String app, String metricPath) {
        try {
            String username = ProgramData.metricProperties.getProperty("appdUsername");
            String password = ProgramData.metricProperties.getProperty("appdToken");
            password = new String(Base64.getDecoder().decode(password));
            String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            long startEpoch = ProgramData.startDT.toInstant().toEpochMilli();
            long endEpoch = ProgramData.startDT.plusMinutes(ProgramData.duration).toInstant().toEpochMilli();
            HttpRequest request = Unirest
                    .get("https://papajohns-test.saas.appdynamics.com/controller/rest/applications/{APP}/metric-data")
                    .routeParam("APP", app).queryString("metric-path", ReplaceURLCodes(metricPath))
                    .queryString("time-range-type", "BETWEEN_TIMES").queryString("start-time", startEpoch)
                    .queryString("end-time", endEpoch).queryString("rollup", "false").queryString("output", "JSON")
                    .header("Content-Type", "application/json").header("Authorization", "Basic " + authCode);
            HttpResponse<JsonNode> response = request.asJson();

            JSONArray values = response.getBody().getArray();
            JSONObject outerObject = values.getJSONObject(0);
            if (outerObject.get("metricName").equals("METRIC DATA NOT FOUND")) {
                System.out.println("No metric data for " + metric);
                return null;
            }
            JSONArray metricValues = outerObject.getJSONArray("metricValues");
            float[] returnValues = new float[(int) ProgramData.duration];
            for (int i = 0; i < returnValues.length; i++) {
                returnValues[i] = 0.0f;
            }
            long currentTime = startEpoch;
            int returnIndex = 0;
            for (int i = 0; i < metricValues.length(); i++) {
                while (currentTime + (returnIndex * 60000) != metricValues.getJSONObject(i)
                        .getLong("startTimeInMillis"))
                    returnIndex++;
                returnValues[returnIndex] = metricValues.getJSONObject(i).getFloat("value");
            }
            return returnValues;
        } catch (Exception e) {
        }
        return null;
    }

    /*
     * Replaces the URL codes in the metric path so the HttpRequest reads them
     * properly
     */
    private static String ReplaceURLCodes(String path) {
        for (String code : ProgramData.urlCodes.keySet()) {
            path = path.replace(code, ProgramData.urlCodes.get(code));
        }
        return path;
    }

    /*
     * Here we set the thresholds for quick analysis of differences between baseline
     * and test values as specified in the CSV: ERROR_THRESHOLD (metricData[3]) is
     * the threshold at which an Error notification will be saved in the report
     * WARNING_THRESHOLD (metricData[4])) is the threshold at which a Warning
     * notification will be saved in the report
     */
    private static void SetMetricThresholds(String[] metricData) {
        ProgramData.metrics.get(metricData[0]).thresholds = new float[] { Float.parseFloat(metricData[4]),
                Float.parseFloat(metricData[5]), Float.parseFloat(metricData[6]), Float.parseFloat(metricData[7]) };
    }
}
