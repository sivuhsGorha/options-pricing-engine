package com.sbk.optionspricer.web;

import com.sbk.optionspricer.risk.GreekAggregator;
import com.sbk.optionspricer.risk.PortfolioPosition;
import com.sbk.optionspricer.risk.SpanMarginSimulator;
import com.sbk.optionspricer.volatility.SabrModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.Executors;

/**
 * Zero-dependency embedded Web Server.
 * Exposes the Options Pricing Engine via a REST API and serves the Dashboard UI.
 */
public class OptionsDashboardServer {

    private static final GreekAggregator riskEngine = new GreekAggregator(50000.0, 10000.0);

    public static void main(String[] args) throws Exception {
        // Initialize some live risk
        PortfolioPosition calls = new PortfolioPosition("SPY_CALL_500", -1000, 100);
        calls.updateGreeks(0.50, 0.05, 10.0);
        riskEngine.addPosition(calls);

        PortfolioPosition puts = new PortfolioPosition("SPY_PUT_450", 500, 100);
        puts.updateGreeks(-0.25, 0.03, 12.0);
        riskEngine.addPosition(puts);

        String portEnv = System.getenv("PORT");
        int port = (portEnv != null) ? Integer.parseInt(portEnv) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Serve the UI
        server.createContext("/", new StaticFileHandler());

        // REST API: Live Portfolio Risk
        server.createContext("/api/risk", (exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            
            double margin = SpanMarginSimulator.calculateInitialMargin(riskEngine, 500.0);
            
            String json = String.format(java.util.Locale.US, "{\n" +
                    "  \"netDelta\": %.2f,\n" +
                    "  \"netGamma\": %.2f,\n" +
                    "  \"netVega\": %.2f,\n" +
                    "  \"spanMargin\": %.2f\n" +
                    "}", 
                    riskEngine.calculateNetDelta(),
                    riskEngine.calculateNetGamma(),
                    riskEngine.calculateNetVega(),
                    margin);
                    
            byte[] response = json.getBytes();
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }));

        // REST API: 3D Volatility Surface (SABR)
        server.createContext("/api/surface3d", (exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            
            int[] strikes = {80, 85, 90, 95, 100, 105, 110, 115, 120};
            double[] expiries = {0.1, 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0};
            
            StringBuilder json = new StringBuilder("{\n");
            
            // Strikes (x)
            json.append("  \"x\": [");
            for(int i=0; i<strikes.length; i++) {
                json.append(strikes[i]);
                if(i<strikes.length-1) json.append(",");
            }
            json.append("],\n");
            
            // Expiries (y)
            json.append("  \"y\": [");
            for(int i=0; i<expiries.length; i++) {
                json.append(String.format(java.util.Locale.US, "%.2f", expiries[i]));
                if(i<expiries.length-1) json.append(",");
            }
            json.append("],\n");
            
            // Vols (z - 2D array)
            json.append("  \"z\": [\n");
            for (int i=0; i<expiries.length; i++) {
                json.append("    [");
                for (int j=0; j<strikes.length; j++) {
                    double vol = SabrModel.impliedVolatility(100.0, strikes[j], expiries[i], 0.25, 1.0, -0.6, 0.4);
                    json.append(String.format(java.util.Locale.US, "%.4f", vol));
                    if(j<strikes.length-1) json.append(",");
                }
                json.append("]");
                if(i<expiries.length-1) json.append(",");
                json.append("\n");
            }
            json.append("  ]\n}");
            
            byte[] response = json.toString().getBytes();
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }));

        server.setExecutor(Executors.newFixedThreadPool(10)); // multi-threaded
        server.start();
        System.out.println("=================================================");
        System.out.println("Options Trading Dashboard Live at: http://localhost:8080");
        System.out.println("=================================================");
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            File file = new File("web" + path);
            if (file.exists()) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                if (path.endsWith(".html")) exchange.getResponseHeaders().add("Content-Type", "text/html");
                else if (path.endsWith(".css")) exchange.getResponseHeaders().add("Content-Type", "text/css");
                else if (path.endsWith(".js")) exchange.getResponseHeaders().add("Content-Type", "application/javascript");
                
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream os = exchange.getResponseBody();
                os.write(bytes);
                os.close();
            } else {
                String error = "404 Not Found";
                exchange.sendResponseHeaders(404, error.length());
                OutputStream os = exchange.getResponseBody();
                os.write(error.getBytes());
                os.close();
            }
        }
    }
}
