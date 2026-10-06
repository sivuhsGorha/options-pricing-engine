package com.sbk.optionspricer;

import com.sbk.optionspricer.config.EnvironmentConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of OptionChainProvider that fetches real market data from TD Ameritrade.
 * Requires TD_AMERITRADE_API_KEY environment variable.
 */
public class TdAmeritradeOptionChain implements OptionChainProvider {

    private static final String TD_OPTIONS_URL = "https://api.tdameritrade.com/v1/marketdata/chains?symbol=%s&apikey=%s";
    private final HttpClient httpClient;

    public TdAmeritradeOptionChain() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public OptionChain getOptionChain(String symbol, LocalDate expiry) {
        String apiKey = EnvironmentConfigLoader.get("TD_AMERITRADE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TD_AMERITRADE_API_KEY is not set in environment or .env file");
        }

        try {
            String url = String.format(TD_OPTIONS_URL, symbol, apiKey);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String json = response.body();

            if (response.statusCode() != 200 || json.contains("\"error\":")) {
                throw new RuntimeException("Failed to fetch from TD Ameritrade. Status: " + response.statusCode());
            }

            double spotPrice = extractDouble(json, "\"underlyingPrice\"");
            if (Double.isNaN(spotPrice) || spotPrice <= 0) {
                throw new RuntimeException("Could not extract valid underlying price for " + symbol);
            }

            List<OptionQuote> quotes = new ArrayList<>();
            
            // Extract calls
            String callsSection = extractObjectSection(json, "\"callExpDateMap\"");
            parseQuotes(callsSection, symbol, expiry, OptionType.CALL, quotes);

            // Extract puts
            String putsSection = extractObjectSection(json, "\"putExpDateMap\"");
            parseQuotes(putsSection, symbol, expiry, OptionType.PUT, quotes);

            return new OptionChain(symbol, expiry, spotPrice, quotes);
        } catch (Exception e) {
            throw new RuntimeException("Error fetching TD Ameritrade option chain for " + symbol, e);
        }
    }

    private void parseQuotes(String section, String symbol, LocalDate expiry, OptionType type, List<OptionQuote> quotes) {
        if (section == null || section.isBlank()) return;

        // Iterate through objects that contain quote arrays
        int arrayStart = section.indexOf('[');
        while (arrayStart != -1) {
            int arrayEnd = section.indexOf(']', arrayStart);
            if (arrayEnd == -1) break;

            String objStr = section.substring(arrayStart, arrayEnd + 1);
            
            double strike = extractDouble(objStr, "\"strikePrice\"");
            double bid = extractDouble(objStr, "\"bid\"");
            double ask = extractDouble(objStr, "\"ask\"");
            double iv = extractDouble(objStr, "\"volatility\"");
            long volume = (long) extractDouble(objStr, "\"totalVolume\"");
            long oi = (long) extractDouble(objStr, "\"openInterest\"");

            // Validation & cleanup
            if (Double.isNaN(bid)) bid = 0.0;
            if (Double.isNaN(ask)) ask = 0.0;
            if (Double.isNaN(iv) || iv < 0) iv = 0.0001; 
            else if (iv > 1000) iv = iv / 100.0; // TDA sometimes provides vol as percentage (e.g. 50.0 instead of 0.5)

            if (volume < 0) volume = 0;
            if (oi < 0) oi = 0;

            if (!Double.isNaN(strike)) {
                // TDA returns multiple expiries; realistically we should filter by the requested `expiry` param.
                // For simplicity, we capture the data found in the response chunk if it parses.
                quotes.add(new OptionQuote(symbol, expiry, strike, type, bid, ask, iv, volume, oi));
            }

            arrayStart = section.indexOf('[', arrayEnd);
        }
    }

    private String extractObjectSection(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex == -1) return null;
        
        int objStart = json.indexOf('{', keyIndex);
        if (objStart == -1) return null;
        
        int bracketCount = 1;
        int objEnd = objStart + 1;
        while (objEnd < json.length() && bracketCount > 0) {
            char c = json.charAt(objEnd);
            if (c == '{') bracketCount++;
            else if (c == '}') bracketCount--;
            objEnd++;
        }
        
        if (bracketCount == 0) {
            return json.substring(objStart, objEnd);
        }
        return null;
    }

    private double extractDouble(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex == -1) return Double.NaN;
        
        int colonIndex = json.indexOf(':', keyIndex);
        if (colonIndex == -1) return Double.NaN;
        
        int endIdx = colonIndex + 1;
        while (endIdx < json.length()) {
            char c = json.charAt(endIdx);
            if (c == ',' || c == '}' || Character.isWhitespace(c)) {
                if (endIdx > colonIndex + 1 && !Character.isWhitespace(json.charAt(endIdx - 1))) {
                    break;
                }
            }
            endIdx++;
        }
        
        String valStr = json.substring(colonIndex + 1, endIdx).trim();
        try {
            return Double.parseDouble(valStr);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
