package com.sbk.optionspricer.rates;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Fetches the European Short-Term Rate (€STR) from the European Central Bank (ECB) API.
 * Uses the short-term rate as an anchor to bootstrap an OIS curve.
 */
public class EsterRateProvider implements YieldCurveProvider {

    // ECB SDMX 2.1 REST API for €STR (Euro short-term rate)
    private static final String ECB_ESTER_URL = "https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?lastNObservations=1&format=jsondata";

    private final HttpClient httpClient;

    public EsterRateProvider() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public YieldCurve getYieldCurve() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ECB_ESTER_URL))
                    .header("Accept", "application/vnd.sdmx.data+json;version=1.0.0-wd")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String json = response.body();

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch €STR from ECB. Status: " + response.statusCode());
            }

            double esterRate = extractEsterRate(json);
            if (Double.isNaN(esterRate)) {
                throw new RuntimeException("Could not extract valid €STR rate from ECB response.");
            }

            // Convert percentage to decimal
            double r0 = esterRate / 100.0;
            
            // To bootstrap a full OIS curve, we need a series of swap rates. 
            // In a full production system, we would fetch OIS swap rates (1Y, 2Y, 5Y, etc.) from a data vendor.
            // Since we only have the ECB €STR benchmark anchor, we'll build a synthetic 
            // set of OIS swap rates projecting a mild normal yield curve shape.
            
            double[] maturities = {1.0/12.0, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0};
            double[] swapRates = new double[maturities.length];
            
            for (int i = 0; i < maturities.length; i++) {
                // A basic Nelson-Siegel style synthetic projection anchored on the real €STR rate.
                // Assuming long-term rate stabilizes 2% higher than short-term.
                double longTermSpread = 0.02; 
                double t = maturities[i];
                swapRates[i] = r0 + longTermSpread * (1.0 - Math.exp(-0.5 * t)); 
            }

            // Use the existing OIS Bootstrapper to solve for Discount Factors
            return OisCurveBootstrapper.bootstrap(maturities, swapRates);

        } catch (Exception e) {
            throw new RuntimeException("Error fetching €STR from ECB API", e);
        }
    }

    private double extractEsterRate(String json) {
        // Find the "observations" array in the SDMX JSON structure. 
        // The value is typically nested deep: dataSets -> series -> observations -> [0] -> [0]
        // Example: "observations":{"0":[3.896]}
        int obsIdx = json.indexOf("\"observations\":");
        if (obsIdx == -1) return Double.NaN;
        
        int bracketStart = json.indexOf('[', obsIdx);
        if (bracketStart == -1) return Double.NaN;
        
        int bracketEnd = json.indexOf(']', bracketStart);
        if (bracketEnd == -1) return Double.NaN;
        
        String valStr = json.substring(bracketStart + 1, bracketEnd).trim();
        try {
            return Double.parseDouble(valStr);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
