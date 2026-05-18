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
            InetAddress address = InetAddress.getByName(host);
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

            // Block cloud metadata IP explicitly (169.254.169.254)
            if (addr.length == 4
                    && (addr[0] & 0xFF) == 169
                    && (addr[1] & 0xFF) == 254
                    && (addr[2] & 0xFF) == 169
                    && (addr[3] & 0xFF) == 254) {
                throw new ClientException("ERR_BLOCKED_URL_IP",
                        "URL resolves to cloud metadata endpoint");
            }

        } catch (UnknownHostException e) {
            throw new ClientException("ERR_INVALID_URL",
                    "Cannot resolve host: " + host);
        }
    }
}
