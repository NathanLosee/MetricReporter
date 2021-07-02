package com.MetricReporter;

import java.time.ZonedDateTime;
import java.util.*;

public class ProgramData {
    public static String config;
    public static String startDateTime;
    public static String durationString;
    public static String testType;
    public static String exportName;
    public static String overwriteString;
    public static String baselineName;

    public static ZonedDateTime startDT;
    public static long duration;
    public static boolean overwrite;

    public static Map<String, String> urlCodes;
    public static String metricPropertiesFile = "/res/metrics.properties";
    public static Properties metricProperties = new Properties();

    public static HashMap<String, Metric> metrics;
}
