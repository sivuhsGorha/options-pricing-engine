package com.sbk.optionspricer.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The dashboard can serve HTTPS itself from a keystore, so a deployment does not need a reverse proxy to have TLS. */
class DashboardTlsTest {

    private static final String SECRET = "0123456789012345678901234567890123456789";
    private static final String PASSWORD = "StrongPassword123!";
    private static final String STORE_PASSWORD = "keystore-pass-1";

    @TempDir
    Path dir;

    private OptionsDashboardServer server;
    private Path keystore;

    private Path makeKeystore() throws Exception {
        Path store = dir.resolve("dashboard.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "dashboard", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", STORE_PASSWORD)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return store;
    }

    private MmapStateReader reader() {
        return new MmapStateReader(true) {
            @Override public RiskState readState() { return new RiskState(5.0, 10.0, 15.0, 25000.0); }
            @Override public void close() {}
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        keystore = makeKeystore();
        Path webRoot = Files.createDirectory(dir.resolve("web"));
        Files.writeString(webRoot.resolve("index.html"), "<html><body>Dashboard</body></html>");
        SSLContext tls = TlsSupport.load(keystore, STORE_PASSWORD.toCharArray());
        server = new OptionsDashboardServer(SECRET, PASSWORD, "https://dashboard.test", "127.0.0.1", 0, 0, reader(), webRoot.toString(),
                null, null, null, new OptionsDashboardServer.SecurityOptions(Set.of(), false, tls));
        server.start();
        OptionsDashboardServerTest.waitForWebSocketPort(server);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.stop();
    }

    private HttpClient clientTrusting(Path store) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) {
            trust.load(in, STORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).sslParameters(new SSLParameters()).build();
    }

    @Test
    void theDashboardAnswersOverHttpsAndMarksItselfSecure() throws Exception {
        HttpResponse<String> response = clientTrusting(keystore).send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.getPort() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Dashboard"));
        assertTrue(response.headers().firstValue("Strict-Transport-Security").isPresent(), "HSTS is sent when the server is the TLS endpoint");
    }

    @Test
    void aClientThatDoesNotTrustTheCertificateIsRefusedAndPlainHttpGetsNothing() throws Exception {
        assertThrows(javax.net.ssl.SSLException.class, () -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.getPort() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));

        assertThrows(java.io.IOException.class, () -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString()), "a plain request to a TLS port is not served");
    }

    @Test
    void aMissingKeystoreOrAWrongPasswordIsRefusedWithoutQuotingThePassword() {
        Exception missing = assertThrows(IllegalArgumentException.class, () -> TlsSupport.load(dir.resolve("nope.p12"), "x".toCharArray()));
        assertFalse(missing.getMessage().contains("nope.p12") && missing.getMessage().contains("xxxx"));

        Exception wrong = assertThrows(IllegalArgumentException.class, () -> TlsSupport.load(keystore, "not-the-password".toCharArray()));
        assertFalse(wrong.getMessage().contains("not-the-password"), wrong.getMessage());
    }

    @Test
    void withoutAKeystoreInTheEnvironmentThereIsNoTls() throws Exception {
        assertNull(TlsSupport.fromEnvironment(name -> null));
        assertNotNull(TlsSupport.fromEnvironment(name -> switch (name) {
            case "TLS_KEYSTORE" -> keystore.toString();
            case "TLS_KEYSTORE_PASSWORD" -> STORE_PASSWORD;
            default -> null;
        }));
        assertThrows(IllegalArgumentException.class, () -> TlsSupport.fromEnvironment(name -> "TLS_KEYSTORE".equals(name) ? keystore.toString() : null),
                "a keystore without its password is a mistake, not a silent fall back to plain HTTP");
    }
}
