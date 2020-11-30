package com.MetricReporter;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import com.mashape.unirest.http.*;

import org.json.*;

/*
The purpose of this program is to get the metrics from AppD by hitting the REST APIs.Saves about 1 hour per test report preparation.
Per day basis, ~ 2 hours is saved.
The code was first pushed to github on  24-July-2020.
*/
public class MetricWriter {
    private static String exportString;

    public static void ReportMetrics() throws Exception {
        ReadReportFormat();
        FormatResults();
        PrintResults();
    }

    /* 
     * Read the report format set in the CSV/HTML file
     */
    public static void ReadReportFormat() throws Exception {
        String resource;
        if (ProgramData.exportType.equals("CSV")) {
            resource = "/res/Configs/" + ProgramData.config + "/Format.csv";
        } else {
            resource = "/res/Configs/" + ProgramData.config + "/Format.html";
        }
        InputStream stream = MetricWriter.class.getResourceAsStream(resource);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

        StringBuilder resultsBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            resultsBuilder.append(line + "\n");
        }
        exportString = resultsBuilder.toString();

        reader.close();
    }

    /*
     * Prints the metric data out to the console/CSV report file
     */
    public static void FormatResults() throws Exception {
        ReplaceTitle();
        ReplaceDate();
        ReplaceTimeLapse();

        // Replace placeholders for each of the metrics
        ProgramData.metrics.forEach((metricName, metric) -> {
            if (metric.type.equals("LAPSE")) {
                try {
                    ReplaceMetricLapse(metricName, metric);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                ReplaceMetricTestValue(metricName, metric);
                ReplaceMetricDiffValue(metricName, metric);
                ReplaceMetricDiffAnalysis(metricName, metric);
            }
        });
    }

    /*
     * Replaces the title placeholders in the report with the title
     */
    public static void ReplaceTitle() {
        exportString = exportString.replaceAll("\\{title\\}", ProgramData.exportName);
    }

    /*
     * Replaces the date placeholder in the report with the test date
     */
    public static void ReplaceDate() {
        String dateString = ProgramData.startDT.getDayOfMonth() + "-"
                + ProgramData.startDT.getMonth().name().substring(0, 3) + "-" + ProgramData.startDT.getYear() + " "
                + ProgramData.startDT.toString().substring(11, 16) + " - "
                + ProgramData.startDT.plusMinutes(ProgramData.duration).toString().substring(11, 16);
        exportString = exportString.replace("{date}", dateString);
    }
    
    /*
     * Replaces the timelapse placeholder in the report with the test minutes
     */
    public static void ReplaceTimeLapse() throws Exception {
        while (exportString.contains("{timelapse}")) {
            Pattern pattern = Pattern.compile("(?m)^.+?\\{timelapse\\}.+?$");
            Matcher matcher = pattern.matcher(exportString);
            matcher.find();
            String group = matcher.group();
            String[] timelapse = new String[(int) ProgramData.duration];
            for (int i = 0; i < ProgramData.duration; i++) {
                String time = ProgramData.startDT.plusMinutes(i).toString().substring(11, 16);
                timelapse[i] = group.replace("{timelapse}", time);
            }
            String replacement = String.join("\n", timelapse);
            exportString = exportString.replaceFirst(pattern.pattern(), replacement);
        }
    }
    

    /*
     * Replaces the lapse placeholder in the report with the data for the metric
     */
    public static void ReplaceMetricLapse(String metricName, Metric metric) throws Exception {
        Pattern pattern = Pattern.compile("(?m)^.+?\\{" + metricName + "\\}.+?$");
        Matcher matcher = pattern.matcher(exportString);
        matcher.find();
        String group = matcher.group();
        String[] metriclapse = new String[metric.values.length];
        for (int i = 0; i < metric.values.length; i++) {
            metriclapse[i] = group.replace("{" + metricName + "}", "" + metric.values[i]);
        }
        String replacement = String.join("\n", metriclapse);
        exportString = exportString.replaceFirst(pattern.pattern(), replacement);
    }

    /*
     * Replaces the next metric placeholder in the report with the metric's test value
     */
    public static void ReplaceMetricTestValue(String metricName, Metric metric) {
        String valueString = String.format("%.2f", metric.values[1]);
        exportString = exportString.replace("{" + metricName + "_test}", valueString);
    }
    
    /*
     * Replaces the next metric placeholder in the report with the metric's diff value
     */
    public static void ReplaceMetricDiffValue(String metricName, Metric metric) {
        String valueString;
        if (Float.isNaN(metric.values[2])) {
            valueString = "NaN";
        } else {
            valueString = String.format("%.2f", metric.values[2]);
        }
        exportString = exportString.replace("{" + metricName + "_diff}", valueString);
    }
    
    /*
     * Replaces the next metric placeholder in the report with the metric's diff analysis
     */
    public static void ReplaceMetricDiffAnalysis(String metricName, Metric metric) {
        String valueString;
        if (Float.isNaN(metric.values[2])) {
            valueString = "---";
        } else {
            // If the threshholds are positive, then we compare whether the diff is high
            if (metric.thresholds[0] > 0) {
                if(metric.values[2] >= metric.thresholds[0]) {
                    valueString = "#!!#";
                } else if (metric.values[2] >= metric.thresholds[1]) {
                    valueString = "~!!~";
                } else if (metric.values[2] < 0.0) {
                    valueString = "~$~";
                } else {
                    valueString = "~~~";
                }
            // If the threshholds are negative, then we compare whether the diff is low
            } else {
                if(metric.values[2] <= metric.thresholds[0]) {
                    valueString = "#!!#";
                } else if (metric.values[2] <= metric.thresholds[1]) {
                    valueString = "~!!~";
                } else if (metric.values[2] > 0.0) {
                    valueString = "~$~";
                } else {
                    valueString = "~~~";
                }
            }
        }
        exportString = exportString.replace("{"+metricName+"_anls}", valueString);
    }

    /*
     * Prints results to console and file/Confluence
     */
    public static void PrintResults() throws Exception {
        if (ProgramData.exportType.equals("CSV")) {
            // Print to file
            File exportFile = new File(ProgramData.exportName + ".csv");
            if (exportFile.exists()) {
                System.out.println("File already exists.");
                if (ProgramData.overwrite) {
                    System.out.println("Overwriting.");
                    System.setOut(new PrintStream(new File(ProgramData.exportName + ".csv")));
                    System.out.println(exportString);
                }
            } else {
                System.out.println("File does not exist, creating.");
                System.setOut(new PrintStream(new File(ProgramData.exportName + ".csv")));
                System.out.println(exportString);
            }
        } else {
            String username = ProgramData.metricProperties.getProperty("atlaUsername");
            String password = ProgramData.metricProperties.getProperty("atlaToken");
            String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());

            // Send to Confluence
            HttpResponse<JsonNode> response =  Unirest.get("https://pjolodevkb.atlassian.net/wiki/rest/api/content")
                .queryString("type", "page")
                .queryString("title", ProgramData.exportName)
                .queryString("expand", "version")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + authCode)
                .asJson();
            JSONArray results = response.getBody().getObject().getJSONArray("results");
            if (results.length() > 0) {
                System.out.println("Page already exists.");
                if (ProgramData.overwrite) {
                    System.out.println("Overwriting page.");
                    String existingID = results.getJSONObject(0).getString("id");
                    int versionNum = results.getJSONObject(0).getJSONObject("version").getInt("number");
                    ReplaceConfluencePage(authCode, existingID, versionNum);
                }
            }
            else {
                System.out.println("Page does not exist, creating.");
                CreateNewConfluencePage(authCode);
            }
            
        }
    }

    private static void ReplaceConfluencePage(String authCode, String existingID, int versionNum) throws Exception {
        JSONObject pageContent = new JSONObject();
        pageContent.put("type", "page");
        pageContent.put("title", ProgramData.exportName);
        JSONObject version = new JSONObject();
        version.put("number", versionNum + 1);
        pageContent.put("version", version);
        JSONObject body = new JSONObject();
        JSONObject storage = new JSONObject();
        storage.put("value", exportString);
        storage.put("representation", "storage");
        body.put("storage", storage);
        pageContent.put("body", body);

        HttpResponse<String> response = Unirest.put("https://pjolodevkb.atlassian.net/wiki/rest/api/content/{contentID}")
                .routeParam("contentID", existingID)
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + authCode)
                .body(pageContent)
                .asString();
    }

    private static void CreateNewConfluencePage(String authCode) throws Exception {
        String contentID;
        if (ProgramData.config.equals("Focus")) {
            contentID = ProgramData.metricProperties.getProperty("confContentID_Focus");
        } else {
            contentID = ProgramData.metricProperties.getProperty("confContentID_Online");
        }

        JSONObject pageContent = new JSONObject();
        pageContent.put("type", "page");
        pageContent.put("title", ProgramData.exportName);
        JSONArray ancestors = new JSONArray();
        JSONObject ancestor = new JSONObject();
        ancestor.put("id", contentID);
        ancestors.put(ancestor);
        pageContent.put("ancestors", ancestors);
        JSONObject space = new JSONObject();
        space.put("key", "PER");
        pageContent.put("space", space);
        JSONObject body = new JSONObject();
        JSONObject storage = new JSONObject();
        storage.put("value", exportString);
        storage.put("representation", "storage");
        body.put("storage", storage);
        pageContent.put("body", body);

        HttpResponse<JsonNode> response = Unirest.post("https://pjolodevkb.atlassian.net/wiki/rest/api/content")
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + authCode)
                .body(pageContent)
                .asJson();
    }
}