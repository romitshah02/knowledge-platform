package org.sunbird.common;

import org.apache.commons.lang3.StringUtils;
import org.sunbird.common.exception.ClientException;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SafeUrlValidator {

    private static final Set<String> ALLOWED_PROTOCOLS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("http", "https")));

    private static final Set<String> BLOCKED_HOSTNAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "localhost",
                    "metadata.google.internal",
                    "metadata.google",
                    "kubernetes.default.svc"
            )));

    private static final Set<String> ALLOWED_PROTOCOLS_CONFIG;

    static {
        Set<String> configured = null;
        try {
            if (Platform.config.hasPath("ssrf.allowed.protocols")) {
                configured = new HashSet<>(Platform.config.getStringList("ssrf.allowed.protocols"));
            }
        } catch (Exception e) {
            // Config not available
        }
        ALLOWED_PROTOCOLS_CONFIG = configured != null ? Collections.unmodifiableSet(configured) : ALLOWED_PROTOCOLS;
    }

    public static void validate(String urlString) {
        if (StringUtils.isBlank(urlString)) {
            throw new ClientException("ERR_INVALID_URL", "URL must not be blank");
        }

        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            throw new ClientException("ERR_INVALID_URL", "Malformed URL: " + urlString);
        }

        validateProtocol(url);
        validateHost(url);
        validateIpAddress(url);
    }

    private static void validateProtocol(URL url) {
        String protocol = url.getProtocol().toLowerCase();
        if (!ALLOWED_PROTOCOLS_CONFIG.contains(protocol)) {
            throw new ClientException("ERR_BLOCKED_URL_PROTOCOL",
                    "URL protocol not allowed: " + protocol + ". Only " + ALLOWED_PROTOCOLS_CONFIG + " allowed.");
        }
    }

    private static void validateHost(URL url) {
        String host = url.getHost().toLowerCase();
        if (BLOCKED_HOSTNAMES.contains(host)) {
            throw new ClientException("ERR_BLOCKED_URL_HOST",
                    "URL host not allowed: " + host);
        }
    }

    private static void validateIpAddress(URL url) {
        String host = url.getHost();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                byte[] addr = address.getAddress();

                if (address.isLoopbackAddress()) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to loopback address");
                }

                if (address.isSiteLocalAddress()) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to private network address");
                }

                if (address.isLinkLocalAddress()) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to link-local address");
                }

                if (address.isAnyLocalAddress()) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to wildcard address");
                }

                // Block IPv6 ULA (fc00::/7)
                if (addr.length == 16 && (addr[0] & 0xFE) == 0xFC) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to IPv6 unique local address");
                }

                // Block IPv4-mapped IPv6 (::ffff:x.x.x.x) pointing to private ranges
                if (addr.length == 16 && isIPv4MappedPrivate(addr)) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to private network address");
                }

                // Block cloud metadata IP explicitly (169.254.169.254)
                if (addr.length == 4
                        && (addr[0] & 0xFF) == 169
                        && (addr[1] & 0xFF) == 254
                        && (addr[2] & 0xFF) == 169
                        && (addr[3] & 0xFF) == 254) {
                    throw new ClientException("ERR_BLOCKED_URL_IP",
                            "URL resolves to cloud metadata endpoint");
                }
            }
        } catch (UnknownHostException e) {
            throw new ClientException("ERR_INVALID_URL",
                    "Cannot resolve host: " + host);
        }
    }

    private static boolean isIPv4MappedPrivate(byte[] addr) {
        // Check ::ffff: prefix (bytes 0-9 = 0, bytes 10-11 = 0xFF)
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) return false;
        }
        if ((addr[10] & 0xFF) != 0xFF || (addr[11] & 0xFF) != 0xFF) return false;
        int b0 = addr[12] & 0xFF;
        int b1 = addr[13] & 0xFF;
        // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, 169.254.0.0/16
        return b0 == 10 || b0 == 127
                || (b0 == 172 && (b1 & 0xF0) == 16)
                || (b0 == 192 && b1 == 168)
                || (b0 == 169 && b1 == 254);
    }
}
