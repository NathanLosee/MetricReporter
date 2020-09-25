package com.MetricReporter;

public class DerivedFunctions {
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
    public static void DeriveValues(Metric metric, String[] data){
        switch (data[1]) {
        case "Derived_GC":
            metric.values[0] = GCPercentage(data[4], true);
            metric.values[1] = GCPercentage(data[4], false);
            break;
        case "Derived_Error":
            metric.values[0] = ErrorPercentage(data[4], true);
            metric.values[1] = ErrorPercentage(data[4], false);
            break;
        }
    }

    /*
     * Function for having derived value threads wait for source values to be evaluated
     */
    public static void Wait(String derivedMetricsString) {
        try {
            String[] derivedMetrics = derivedMetricsString.split(":");
            if (ProgramData.metrics.containsKey(derivedMetrics[0])) {
                ProgramData.metrics.get(derivedMetrics[0]).valueThread.join();
            }
        } catch (Exception e) { }
    }

    /*
     * Function for percentage values derived from gcTime per minute
     * REQUIRES: One metric - amount of time in GC per minute
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
}
