package com.sbk.optionspricer.rates;

import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches Treasury yields from the Federal Reserve Economic Data (FRED) API.
 * Requires FRED_API_KEY environment variable.
 */
public class FredYieldCurve implements YieldCurveProvider {

    private static final String FRED_URL = "https://api.stlouisfed.org/fred/series/observations?series_id=%s&api_key=%s&file_type=json&sort_order=desc&limit=1";
    
    // Treasury Constant Maturity Series
    private static final String[] SERIES_IDS = {
        "DGS1MO", "DGS3MO", "DGS6MO", "DGS1", "DGS2", 
        "DGS3", "DGS5", "DGS7", "DGS10", "DGS20", "DGS30"
    };
    
    // Corresponding times to maturity in years
    private static final double[] TENORS_YEARS = {
        1.0/12.0, 0.25, 0.5, 1.0, 2.0, 
        3.0, 5.0, 7.0, 10.0, 20.0, 30.0
    };

    private final HttpClient httpClient;

    public FredYieldCurve() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public YieldCurve getYieldCurve() {
        String apiKey = EnvironmentConfigLoader.get("FRED_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY is not set in environment or .env file");
        }

        List<Double> validTimes = new ArrayList<>();
        List<Double> validRates = new ArrayList<>();

        for (int i = 0; i < SERIES_IDS.length; i++) {
            String url = String.format(FRED_URL, SERIES_IDS[i], apiKey);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                        
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String json = response.body();

                if (response.statusCode() == 200) {
                    double rate = extractValue(json);
                    if (!Double.isNaN(rate) && rate > 0) {
                        validTimes.add(TENORS_YEARS[i]);
                        validRates.add(rate / 100.0); // Convert percentage to decimal
                    }
                }
            } catch (Exception e) {
                // Skip this tenor if network error occurs
            }
        }

        if (validTimes.isEmpty()) {
            throw new RuntimeException("Failed to fetch any valid Treasury rates from FRED.");
        }

        double[] times = new double[validTimes.size()];
        double[] discountFactors = new double[validTimes.size()];

        for (int i = 0; i < validTimes.size(); i++) {
            times[i] = validTimes.get(i);
            // Z(t) = exp(-r * t) for continuously compounded zero rates
            discountFactors[i] = Math.exp(-validRates.get(i) * times[i]);
        }

        return new YieldCurve(times, discountFactors);
    }

    private double extractValue(String json) {
        int valIdx = json.indexOf("\"value\":");
        if (valIdx == -1) return Double.NaN;
        
        int startQuote = json.indexOf('"', valIdx + 8);
        if (startQuote == -1) return Double.NaN;
        
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote == -1) return Double.NaN;
        
        String valStr = json.substring(startQuote + 1, endQuote).trim();
        if (".".equals(valStr) || valStr.isEmpty()) {
            return Double.NaN;
        }
        
        try {
            return Double.parseDouble(valStr);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
