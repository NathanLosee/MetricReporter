package com.MetricReporter;

import java.time.*;
import java.time.format.*;

public class MetricReporter {
    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.out.println("Not enough arguments provided.");
        } else {
            for (String arg : args)
                System.out.println(arg);
            ProgramData.config = args[0];
            ProgramData.startDateTime = args[1];
            ProgramData.durationString = args[2];
            ProgramData.testType = args[3];
            ProgramData.exportName = args[4];
            ProgramData.overwriteString = args[5];
            ProgramData.baselineName = args[6];
            if (CheckArguments()) {
                MetricReader.GatherMetrics();
                MetricWriter.ReportMetrics();
            }
        }
    }

    private static boolean CheckArguments() {
        return CheckConfig() &&
            CheckStartDateTime() &&
            CheckDurationString() &&
            CheckExportType() &&
            CheckExportName() &&
            CheckOverwrite();
    }

    private static boolean CheckConfig() {
        if (MetricWriter.class.getResource("/res/Configs/" + ProgramData.config) == null) {
            System.out.println("Invalid config provided. Please use \"Online\" or \"Focus\".");
            return false;
        }
        return true;
    }

    private static boolean CheckStartDateTime() {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy-HH:mm");
            LocalDateTime startDTLocal = LocalDateTime.parse(ProgramData.startDateTime, formatter);
            ProgramData.startDT = startDTLocal.atZone(ZoneId.of("America/New_York"));
        } catch (DateTimeParseException dtpe) {
            System.out.println("Error parsing provided start date and time. It must be of the form \"MM/dd/yyyy-HH:mm\" (24-hr).");
            dtpe.printStackTrace();
            return false;
        }
        return true;
    }

    private static boolean CheckDurationString() {
        try {
            ProgramData.duration = Long.parseLong(ProgramData.durationString);
        } catch (NumberFormatException nfe) {
            System.out.println("Error parsing provided duration. It must be an integer.");
            nfe.printStackTrace();
            return false;
        }
        return true;
    }

    private static boolean CheckExportType() {
        if (MetricWriter.class.getResource("/res/Configs/" + ProgramData.config + "/Formats/" + ProgramData.testType + ".html") == null) {
            System.out.println("Unsupported test type provided.");
            return false;
        }
        return true;
    }

    private static boolean CheckExportName() {
        return true;
    }

    private static boolean CheckOverwrite() {
        if (!ProgramData.overwriteString.toLowerCase().equals("true") &&
            !ProgramData.overwriteString.toLowerCase().equals("false")) {
            System.out.println("Invalid overwrite value provided. Please use \"true\" or \"false\".");
            return false;
        }
        ProgramData.overwrite = Boolean.parseBoolean(ProgramData.overwriteString);
        return true;
    }
}
