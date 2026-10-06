package com.sbk.optionspricer.rates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Builds a discount curve from US Treasury Constant Maturity yields published by FRED (requires FRED_API_KEY).
 *
 * <p>DGS series are <em>par yields</em> quoted on a bond-equivalent (semiannual) basis, not zero rates.
 * Tenors up to six months pay once at maturity; longer ones are bootstrapped as semiannual-coupon par bonds.
 * An earlier version used each yield as a continuously compounded zero rate, which misprices every discount
 * factor (about 0.04% of price per year at a 4% yield).
 */
public class FredYieldCurve implements YieldCurveProvider {

    private static final String FRED_URL = "https://api.stlouisfed.org/fred/series/observations?series_id=%s&api_key=%s&file_type=json&sort_order=desc&limit=1";
    private static final ObjectMapper JSON = new ObjectMapper();

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

    /** Returns the response body for a FRED series id, or throws. */
    @FunctionalInterface
    interface Fetcher {
        String fetch(String seriesId) throws IOException;
    }

    private final Supplier<String> apiKey;
    private final Fetcher fetcher;

    public FredYieldCurve() {
        this(() -> EnvironmentConfigLoader.get("FRED_API_KEY"), null);
    }

    FredYieldCurve(Supplier<String> apiKey, Fetcher fetcher) {
        this.apiKey = apiKey;
        this.fetcher = fetcher;
    }

    @Override
    public YieldCurve getYieldCurve() {
        String key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("FRED_API_KEY is not set in environment or .env file");
        }
        Fetcher source = fetcher != null ? fetcher : httpFetcher(key);

        List<Double> times = new ArrayList<>();
        List<Double> yields = new ArrayList<>();
        for (int i = 0; i < SERIES_IDS.length; i++) {
            try {
                double yield = extractValue(source.fetch(SERIES_IDS[i]));
                if (Double.isFinite(yield)) {
                    times.add(TENORS_YEARS[i]);
                    yields.add(yield / 100.0); // percent to decimal; a genuine 0% yield is valid data
                }
            } catch (Exception e) {
                // Skip this tenor: one missing point must not drop the whole curve.
            }
        }
        if (times.isEmpty()) {
            // No cause is attached: HTTP exceptions embed the request URL, which contains the API key.
            throw new IllegalStateException("Failed to fetch any valid Treasury yields from FRED.");
        }
        return OisCurveBootstrapper.bootstrap(
                times.stream().mapToDouble(Double::doubleValue).toArray(),
                yields.stream().mapToDouble(Double::doubleValue).toArray(),
                2);
    }

    static double extractValue(String body) {
        try {
            JsonNode observations = JSON.readTree(body).path("observations");
            if (!observations.isArray() || observations.isEmpty()) {
                return Double.NaN;
            }
            String value = observations.get(0).path("value").asText("").trim();
            if (value.isEmpty() || ".".equals(value)) {
                return Double.NaN; // FRED marks a missing observation with "."
            }
            return Double.parseDouble(value);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static Fetcher httpFetcher(String key) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return series -> {
            URI uri = URI.create(String.format(FRED_URL, series, URLEncoder.encode(key, StandardCharsets.UTF_8)));
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
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
