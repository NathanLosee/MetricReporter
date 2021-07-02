package com.MetricReporter;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import com.mashape.unirest.http.*;
import com.mashape.unirest.request.*;

import org.json.*;

public class GetTest {
    public static void main(String[] args) throws Exception {
        try {
            ProgramData.metricProperties = new Properties();
            InputStream fin = new FileInputStream("C:\\Users\\nathan_losee\\Documents\\PJI\\MetricReporter\\src\\res\\metrics.properties");
            ProgramData.metricProperties.load(fin);
            fin.close();
        } catch(IOException ioE) {
            ioE.printStackTrace();
            System.exit(-1);
        }

        String username = ProgramData.metricProperties.getProperty("atlaUsername");
        String password = ProgramData.metricProperties.getProperty("atlaToken");
        String authCode = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        System.out.println(authCode);

        // Send to Confluence
        HttpRequest request = Unirest.get("https://pjolodevkb.atlassian.net/rest/api/3/issue/SRENG-1689")
            .header("Accept", "application/json")
            .header("Authorization", "Basic " + authCode);
        System.out.println(request.asString().getBody());
    }
}
