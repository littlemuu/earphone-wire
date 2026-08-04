package com.andxiaoqie.earphonewire;

import java.net.URI;
import java.net.URISyntaxException;

/** Validates a user-provided HTTPS origin without accepting an endpoint path. */
public final class PairingOrigin {
    private PairingOrigin() {
    }

    public static String normalize(String value) throws IllegalArgumentException {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("必须输入 Worker Origin。");
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("只能输入 HTTPS Origin。");
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("Worker Origin 不能包含路径。");
            }
            int port = uri.getPort();
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            return port == -1 || port == 443 ? "https://" + host : "https://" + host + ":" + port;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("请输入有效的 HTTPS Origin。");
        }
    }
}
