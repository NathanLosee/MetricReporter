package com.MetricReporter;

import java.time.ZonedDateTime;
import java.util.*;

public class ProgramData {
    public static String config;
    public static String startDateTime;
    public static String durationString;
    public static String exportType;
    public static String exportName;
    public static String overwriteString;

    public static ZonedDateTime startDT;
    public static long duration;
    public static boolean overwrite;

    public static Map<String, String> urlCodes;
    public static String metricPropertiesFile = "/res/metrics.properties";
    public static Properties metricProperties = new Properties();

    // We use a LinkedHashMap for the metrics to preserve ordering, using the metric names as the keys
    public static LinkedHashMap<String, Metric> metrics;
}
