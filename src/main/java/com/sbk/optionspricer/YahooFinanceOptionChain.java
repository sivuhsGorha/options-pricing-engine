package com.sbk.optionspricer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of OptionChainProvider that fetches real market data from Yahoo Finance API.
 * Uses a dependency-free JSON parser.
 */
public class YahooFinanceOptionChain implements OptionChainProvider {

    private static final String YAHOO_OPTIONS_URL = "https://query1.finance.yahoo.com/v7/finance/options/%s";
    private final HttpClient httpClient;

    public YahooFinanceOptionChain() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public OptionChain getOptionChain(String symbol, LocalDate expiry) {
        try {
            long unixExpiry = expiry.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            String url = String.format(YAHOO_OPTIONS_URL, symbol) + "?date=" + unixExpiry;
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String json = response.body();

            if (response.statusCode() != 200 || json.contains("\"error\":{")) {
                throw new RuntimeException("Failed to fetch from Yahoo Finance. Status: " + response.statusCode());
            }

            double spotPrice = extractDouble(json, "\"regularMarketPrice\"");
            if (Double.isNaN(spotPrice) || spotPrice <= 0) {
                // Try previous close if regular market is missing
                spotPrice = extractDouble(json, "\"previousClose\"");
            }
            if (Double.isNaN(spotPrice) || spotPrice <= 0) {
                throw new RuntimeException("Could not extract valid spot price for " + symbol);
            }

            List<OptionQuote> quotes = new ArrayList<>();
            
            // Extract calls
            String callsSection = extractArraySection(json, "\"calls\"");
            parseQuotes(callsSection, symbol, expiry, OptionType.CALL, quotes);

            // Extract puts
            String putsSection = extractArraySection(json, "\"puts\"");
            parseQuotes(putsSection, symbol, expiry, OptionType.PUT, quotes);

            return new OptionChain(symbol, expiry, spotPrice, quotes);
        } catch (Exception e) {
            throw new RuntimeException("Error fetching option chain for " + symbol, e);
        }
    }

    private void parseQuotes(String section, String symbol, LocalDate expiry, OptionType type, List<OptionQuote> quotes) {
        if (section == null || section.isBlank()) return;

        // Split by object brackets
        int openBrace = section.indexOf('{');
        while (openBrace != -1) {
            int closeBrace = section.indexOf('}', openBrace);
            if (closeBrace == -1) break;

            String objStr = section.substring(openBrace, closeBrace + 1);
            
            double strike = extractDouble(objStr, "\"strike\"");
            double bid = extractDouble(objStr, "\"bid\"");
            double ask = extractDouble(objStr, "\"ask\"");
            double iv = extractDouble(objStr, "\"impliedVolatility\"");
            long volume = (long) extractDouble(objStr, "\"volume\"");
            long oi = (long) extractDouble(objStr, "\"openInterest\"");

            // Validation & cleanup of bad yahoo data
            if (Double.isNaN(bid)) bid = 0.0;
            if (Double.isNaN(ask)) ask = 0.0;
            if (Double.isNaN(iv) || iv < 0) iv = 0.0001; // Clamp negative IVs
            if (volume < 0) volume = 0;
            if (oi < 0) oi = 0;

            if (!Double.isNaN(strike)) {
                quotes.add(new OptionQuote(symbol, expiry, strike, type, bid, ask, iv, volume, oi));
            }

            openBrace = section.indexOf('{', closeBrace);
        }
    }

    private String extractArraySection(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex == -1) return null;
        
        int arrayStart = json.indexOf('[', keyIndex);
        if (arrayStart == -1) return null;
        
        int bracketCount = 1;
        int arrayEnd = arrayStart + 1;
        while (arrayEnd < json.length() && bracketCount > 0) {
            char c = json.charAt(arrayEnd);
            if (c == '[') bracketCount++;
            else if (c == ']') bracketCount--;
            arrayEnd++;
        }
        
        if (bracketCount == 0) {
            return json.substring(arrayStart, arrayEnd);
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
