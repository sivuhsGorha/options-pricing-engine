package com.sbk.optionspricer.web;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.function.Function;

/**
 * TLS for the dashboard from a PKCS12 keystore ({@code TLS_KEYSTORE} path, {@code TLS_KEYSTORE_PASSWORD}). Without {@code TLS_KEYSTORE} the dashboard
 * serves plain HTTP as before. A keystore that is set but cannot be used stops the start: silently serving plain HTTP when TLS was asked for would be
 * the worse failure. Messages never quote the password.
 */
public final class TlsSupport {

    private TlsSupport() {}

    /** The context for the configured keystore, or null when none is configured. */
    public static SSLContext fromEnvironment(Function<String, String> environment) {
        String path = environment.apply("TLS_KEYSTORE");
        if (path == null || path.isBlank()) {
            return null;
        }
        String password = environment.apply("TLS_KEYSTORE_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("TLS_KEYSTORE is set but TLS_KEYSTORE_PASSWORD is not");
        }
        return load(Path.of(path.trim()), password.toCharArray());
    }

    public static SSLContext load(Path keystore, char[] password) {
        if (!Files.isReadable(keystore)) {
            throw new IllegalArgumentException("the TLS keystore cannot be read");
        }
        try (InputStream in = Files.newInputStream(keystore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, password);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalArgumentException("the TLS keystore could not be opened (wrong password or not a PKCS12 file)");
        }
    }
}
