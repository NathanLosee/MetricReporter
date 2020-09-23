package com.MetricReporter;

public class Metric {
    String type;
    float baseValue;
    float testValue;
    float diffValue;
    float[] thresholds;
    Thread valueThread;

    public Metric() {
        baseValue = Float.NaN;
        testValue = Float.NaN;
        diffValue = Float.NaN;
    }
}
