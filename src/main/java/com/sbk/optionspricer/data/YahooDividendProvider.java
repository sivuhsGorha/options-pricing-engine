package com.sbk.optionspricer.data;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fetches historical dividend payouts from Yahoo Finance API.
 */
public class YahooDividendProvider {

    private static final String YAHOO_DIVIDEND_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=2y&events=div";
    private final HttpClient httpClient;

    public YahooDividendProvider() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record HistoricalDividend(LocalDate exDate, double amount) {}

    public List<HistoricalDividend> getHistoricalDividends(String symbol) {
        try {
            String url = String.format(YAHOO_DIVIDEND_URL, symbol);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String json = response.body();

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch dividends from Yahoo Finance. Status: " + response.statusCode());
            }

            return parseDividends(json);
        } catch (Exception e) {
            throw new RuntimeException("Error fetching dividend history for " + symbol, e);
        }
    }

    private List<HistoricalDividend> parseDividends(String json) {
        List<HistoricalDividend> dividends = new ArrayList<>();
        
        int divBlockIdx = json.indexOf("\"dividends\":{");
        if (divBlockIdx == -1) return dividends;
        
        int startBrace = json.indexOf('{', divBlockIdx);
        int endBrace = findClosingBrace(json, startBrace);
        if (startBrace == -1 || endBrace == -1) return dividends;
        
        String divsJson = json.substring(startBrace, endBrace + 1);
        
        // Very basic manual JSON parser to extract amount and date
        int idx = divsJson.indexOf("\"amount\":");
        while (idx != -1) {
            int colonIdx = divsJson.indexOf(':', idx);
            int commaIdx = divsJson.indexOf(',', colonIdx);
            if (commaIdx == -1) commaIdx = divsJson.indexOf('}', colonIdx);
            
            String amountStr = divsJson.substring(colonIdx + 1, commaIdx).trim();
            double amount = Double.parseDouble(amountStr);
            
            int dateKeyIdx = divsJson.indexOf("\"date\":", idx);
            if (dateKeyIdx != -1) {
                int dateColonIdx = divsJson.indexOf(':', dateKeyIdx);
                int dateCommaIdx = divsJson.indexOf(',', dateColonIdx);
                if (dateCommaIdx == -1) dateCommaIdx = divsJson.indexOf('}', dateColonIdx);
                
                String dateStr = divsJson.substring(dateColonIdx + 1, dateCommaIdx).trim();
                long timestamp = Long.parseLong(dateStr);
                
                LocalDate date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault()).toLocalDate();
                dividends.add(new HistoricalDividend(date, amount));
            }
            
            idx = divsJson.indexOf("\"amount\":", commaIdx);
        }
        
        dividends.sort(Comparator.comparing(HistoricalDividend::exDate));
        return dividends;
    }

    private int findClosingBrace(String json, int openIdx) {
        int count = 1;
        for (int i = openIdx + 1; i < json.length(); i++) {
            if (json.charAt(i) == '{') count++;
            else if (json.charAt(i) == '}') count--;
            if (count == 0) return i;
        }
        return -1;
    }
}
