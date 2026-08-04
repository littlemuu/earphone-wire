package com.andxiaoqie.earphonewire;

import java.net.URI;
import java.net.URISyntaxException;

/** Validates a user-provided HTTPS origin without accepting an endpoint path. */
public final class PairingOrigin {
    private PairingOrigin() {
    }

    public static String normalize(String value) throws IllegalArgumentException {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Worker origin is required.");
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Enter an HTTPS origin only.");
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("The Worker origin cannot include a path.");
            }
            int port = uri.getPort();
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return port == -1 || port == 443 ? "https://" + host : "https://" + host + ":" + port;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Enter a valid HTTPS origin.");
        }
    }
}
