package com.socp.search.config.infrastructure.opensearch;

import com.socp.search.config.config.OpenSearchProperties;
import org.springframework.stereotype.Component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

/** Shared authenticated/TLS-aware transport for all OpenSearch adapters. */
@Component
public class OpenSearchHttpTransport {

    private final OpenSearchProperties properties;
    private volatile SSLSocketFactory sslSocketFactory;

    public OpenSearchHttpTransport(OpenSearchProperties properties) {
        this.properties = properties;
    }

    public Response exchange(String method, String path, String contentType, byte[] body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(properties.getUrl() + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
            String auth = Base64.getEncoder().encodeToString(
                    (properties.getUsername() + ":" + properties.getPassword())
                            .getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + auth);
            if (connection instanceof HttpsURLConnection https) configureHttps(https);
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] responseBody;
            if (stream == null) {
                responseBody = new byte[0];
            } else {
                try (stream) {
                    responseBody = stream.readAllBytes();
                }
            }
            return new Response(status, responseBody);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void configureHttps(HttpsURLConnection https) throws Exception {
        https.setSSLSocketFactory(sslSocketFactory());
        if (properties.getTls().isInsecureSkipVerify()) {
            https.setHostnameVerifier((hostname, session) -> true);
        } else {
            https.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier());
        }
    }

    private SSLSocketFactory sslSocketFactory() throws Exception {
        SSLSocketFactory current = sslSocketFactory;
        if (current != null) return current;
        synchronized (this) {
            if (sslSocketFactory == null) sslSocketFactory = createSslSocketFactory(properties.getTls());
            return sslSocketFactory;
        }
    }

    private static SSLSocketFactory createSslSocketFactory(OpenSearchProperties.Tls tls) throws Exception {
        if (tls.isInsecureSkipVerify()) {
            TrustManager[] managers = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers, new SecureRandom());
            return context.getSocketFactory();
        }
        if (tls.getTrustStore() == null || tls.getTrustStore().isBlank()) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        char[] password = tls.getTrustStorePassword() == null
                ? new char[0] : tls.getTrustStorePassword().toCharArray();
        try (FileInputStream input = new FileInputStream(tls.getTrustStore())) {
            store.load(input, password);
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), new SecureRandom());
        return context.getSocketFactory();
    }

    public record Response(int status, byte[] body) {
        public boolean successful() {
            return status >= 200 && status < 300;
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
