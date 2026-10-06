package com.sbk.optionspricer.rates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Euro short-term rate (€STR) from the ECB, extended into a curve.
 *
 * <p><strong>Only the overnight anchor is market data.</strong> The ECB publishes no swap curve, so the
 * OIS swap rates here are generated from the anchor as {@code r0 + 2% * (1 - exp(-t/2))}. The result is a
 * plausible upward-sloping shape, not quoted rates: {@link #isMarketData()} is false, and it must not be
 * used to value or risk-report anything that matters. A real curve needs OIS swap quotes from a vendor.
 */
public class EsterRateProvider implements YieldCurveProvider {

    // ECB SDMX 2.1 REST API for €STR (Euro short-term rate)
    private static final String ECB_ESTER_URL = "https://data-api.ecb.europa.eu/service/data/EST/B.EU000A2X2A25.WT?lastNObservations=1&format=jsondata";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Returns the ECB response body, or throws. */
    @FunctionalInterface
    interface Fetcher {
        String fetch() throws IOException;
    }

    private final Fetcher fetcher;

    public EsterRateProvider() {
        this(null);
    }

    EsterRateProvider(Fetcher fetcher) {
        this.fetcher = fetcher != null ? fetcher : httpFetcher();
    }

    @Override
    public boolean isMarketData() {
        return false;
    }

    @Override
    public YieldCurve getYieldCurve() {
        double anchorPercent;
        try {
            anchorPercent = extractEsterRate(fetcher.fetch());
        } catch (IOException e) {
            throw new IllegalStateException("Could not fetch the euro short-term rate from the ECB: " + e.getMessage());
        }
        if (!Double.isFinite(anchorPercent)) {
            throw new IllegalStateException("Could not extract a valid euro short-term rate from the ECB response.");
        }

        double r0 = anchorPercent / 100.0;
        double[] maturities = {1.0 / 12.0, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.0, 10.0, 20.0, 30.0};
        double[] swapRates = new double[maturities.length];
        for (int i = 0; i < maturities.length; i++) {
            swapRates[i] = r0 + 0.02 * (1.0 - Math.exp(-0.5 * maturities[i])); // synthetic shape, see class comment
        }
        return OisCurveBootstrapper.bootstrap(maturities, swapRates);
    }

    /** First value of the first observation of the first series: {@code dataSets[0].series.*.observations.*[0]}. */
    static double extractEsterRate(String body) {
        try {
            JsonNode series = JSON.readTree(body).path("dataSets").path(0).path("series");
            if (!series.isObject() || series.isEmpty()) {
                return Double.NaN;
            }
            JsonNode observations = series.elements().next().path("observations");
            if (!observations.isObject() || observations.isEmpty()) {
                return Double.NaN;
            }
            JsonNode first = observations.elements().next().path(0);
            return first.isNumber() ? first.asDouble() : Double.NaN;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ECB_ESTER_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.sdmx.data+json;version=1.0.0-wd")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        };
    }
}
