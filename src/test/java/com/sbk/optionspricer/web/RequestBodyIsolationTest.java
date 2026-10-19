package com.sbk.optionspricer.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Found live on 2026-10-19 (JDK 25.0.4.1): a session POST with the body {"confirm":"invalid"} was answered as if it had
 * carried the {"confirm":false} of an earlier signed request, and after a signed request with no body every session POST to
 * /api/control read an empty body and was refused. The body a signed request had been read into was stored as an exchange
 * attribute, and the documented contract of {@link HttpExchange#setAttribute} is that it is the same as setting it on the
 * HTTP context: shared by every request to that path. (The JDK 26 that runs the build does not behave that way, so this test
 * follows the documented contract with a fake exchange instead of relying on the JDK in use.) A request must read its own
 * body and no other.
 */
class RequestBodyIsolationTest {

    /** The attributes of one HTTP context, which every exchange on it shares, as the HttpExchange documentation says. */
    private static final class Context {
        final Map<String, Object> attributes = new HashMap<>();
    }

    private static final class FakeExchange extends HttpExchange {
        private final Context context;
        private InputStream in;

        FakeExchange(Context context, String body) {
            this.context = context;
            this.in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override public Object getAttribute(String name) { return context.attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { context.attributes.put(name, value); }
        @Override public InputStream getRequestBody() { return in; }
        @Override public void setStreams(InputStream i, OutputStream o) { if (i != null) in = i; }

        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create("/api/control/test-straddle"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int rCode, long responseLength) throws IOException { }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public int getResponseCode() { return -1; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void aRequestReadsItsOwnBodyEvenAfterASignedRequestToTheSamePathHadOneOfItsOwn() throws IOException {
        Context control = new Context();

        // a signed request: its body is read for the signature check and handed back to its own handler
        FakeExchange signed = new FakeExchange(control, "{\"confirm\":false}");
        byte[] verified = signed.getRequestBody().readNBytes(4097);
        OptionsDashboardServer.keepBody(signed, verified);
        assertEquals("{\"confirm\":false}", text(OptionsDashboardServer.requestBody(signed)), "the signed request still reads its body");

        // a session request to the same path, with a different body
        FakeExchange session = new FakeExchange(control, "{\"confirm\":\"invalid\"}");
        assertEquals("{\"confirm\":\"invalid\"}", text(OptionsDashboardServer.requestBody(session)),
                "a later request must not be given the signed request's body");
    }

    @Test
    void aRequestWithNoBodyIsNotGivenAnEarlierRequestsBodyEither() throws IOException {
        Context control = new Context();
        FakeExchange signed = new FakeExchange(control, "{\"confirm\":true}");
        OptionsDashboardServer.keepBody(signed, signed.getRequestBody().readNBytes(4097));

        FakeExchange bare = new FakeExchange(control, "");

        assertEquals("", text(OptionsDashboardServer.requestBody(bare)), "an empty body stays empty; it must not become {\"confirm\":true}");
    }
}
