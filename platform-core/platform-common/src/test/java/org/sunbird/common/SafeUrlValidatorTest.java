package org.sunbird.common;

import org.junit.Assert;
import org.junit.Test;
import org.sunbird.common.exception.ClientException;

public class SafeUrlValidatorTest {

    // === Protocol validation (SSRF-VULN-03: file:// read) ===

    @Test
    public void testValidHttpUrl() {
        SafeUrlValidator.validate("http://example.com/file.pdf");
    }

    @Test
    public void testValidHttpsUrl() {
        SafeUrlValidator.validate("https://example.com/file.pdf");
    }

    @Test(expected = ClientException.class)
    public void testBlockFileProtocol() {
        SafeUrlValidator.validate("file:///etc/passwd");
    }

    @Test(expected = ClientException.class)
    public void testBlockFtpProtocol() {
        SafeUrlValidator.validate("ftp://internal-server/data");
    }

    @Test(expected = ClientException.class)
    public void testBlockJarProtocol() {
        SafeUrlValidator.validate("jar:file:///tmp/evil.jar!/payload");
    }

    @Test(expected = ClientException.class)
    public void testBlockGopherProtocol() {
        SafeUrlValidator.validate("gopher://evil.com:70/");
    }

    // === Blocked hostnames ===

    @Test(expected = ClientException.class)
    public void testBlockLocalhost() {
        SafeUrlValidator.validate("http://localhost:9200/");
    }

    @Test(expected = ClientException.class)
    public void testBlockMetadataGoogle() {
        SafeUrlValidator.validate("http://metadata.google.internal/computeMetadata/v1/");
    }

    // === Loopback addresses (SSRF-VULN-03: port scan) ===

    @Test(expected = ClientException.class)
    public void testBlockLoopback127() {
        SafeUrlValidator.validate("http://127.0.0.1:9000/health");
    }

    @Test(expected = ClientException.class)
    public void testBlockLoopback127Alt() {
        SafeUrlValidator.validate("http://127.0.0.2/");
    }

    // === Private network ranges ===

    @Test(expected = ClientException.class)
    public void testBlockPrivate10() {
        SafeUrlValidator.validate("http://10.0.0.1:8080/");
    }

    @Test(expected = ClientException.class)
    public void testBlockPrivate172() {
        SafeUrlValidator.validate("http://172.16.0.1/internal");
    }

    @Test(expected = ClientException.class)
    public void testBlockPrivate192() {
        SafeUrlValidator.validate("http://192.168.1.1/admin");
    }

    // === Cloud metadata endpoint (169.254.169.254) ===

    @Test(expected = ClientException.class)
    public void testBlockCloudMetadata() {
        SafeUrlValidator.validate("http://169.254.169.254/latest/meta-data/");
    }

    // === Link-local ===

    @Test(expected = ClientException.class)
    public void testBlockLinkLocal() {
        SafeUrlValidator.validate("http://169.254.1.1/");
    }

    // === Wildcard / unspecified ===

    @Test(expected = ClientException.class)
    public void testBlockWildcard() {
        SafeUrlValidator.validate("http://0.0.0.0/");
    }

    // === Null / blank ===

    @Test(expected = ClientException.class)
    public void testBlockNull() {
        SafeUrlValidator.validate(null);
    }

    @Test(expected = ClientException.class)
    public void testBlockEmpty() {
        SafeUrlValidator.validate("");
    }

    @Test(expected = ClientException.class)
    public void testBlockBlank() {
        SafeUrlValidator.validate("   ");
    }

    // === Malformed URL ===

    @Test(expected = ClientException.class)
    public void testBlockMalformedUrl() {
        SafeUrlValidator.validate("not-a-url");
    }

    // === Public URLs should pass ===

    @Test
    public void testAllowPublicHttps() {
        SafeUrlValidator.validate("https://www.google.com/");
    }

    @Test
    public void testAllowPublicUrl2() {
        SafeUrlValidator.validate("https://github.com/");
    }

    @Test
    public void testAllowPublicUrlWithPath() {
        SafeUrlValidator.validate("https://www.google.com/images/test.png");
    }

    // === Security report exact payloads ===

    @Test(expected = ClientException.class)
    public void testSsrfVuln03_FileReadEtcPasswd() {
        SafeUrlValidator.validate("file:///etc/passwd");
    }

    @Test(expected = ClientException.class)
    public void testSsrfVuln03_FileReadEtcHosts() {
        SafeUrlValidator.validate("file:///etc/hosts");
    }

    @Test(expected = ClientException.class)
    public void testSsrfVuln03_InternalServiceProbe() {
        SafeUrlValidator.validate("http://127.0.0.1:9000/health");
    }

    @Test(expected = ClientException.class)
    public void testSsrfVuln03_ElasticsearchProbe() {
        SafeUrlValidator.validate("http://127.0.0.1:9200/");
    }

    @Test(expected = ClientException.class)
    public void testSsrfVuln07_InternalNetworkScan() {
        SafeUrlValidator.validate("http://10.255.255.1:9999/dead");
    }

    // === Error message validation ===

    @Test
    public void testFileProtocolErrorMessage() {
        try {
            SafeUrlValidator.validate("file:///etc/passwd");
            Assert.fail("Should throw");
        } catch (ClientException e) {
            Assert.assertEquals("ERR_BLOCKED_URL_PROTOCOL", e.getErrCode());
            Assert.assertTrue(e.getMessage().contains("file"));
        }
    }

    @Test
    public void testLoopbackErrorMessage() {
        try {
            SafeUrlValidator.validate("http://127.0.0.1/");
            Assert.fail("Should throw");
        } catch (ClientException e) {
            Assert.assertEquals("ERR_BLOCKED_URL_IP", e.getErrCode());
            Assert.assertTrue(e.getMessage().contains("loopback"));
        }
    }

    @Test
    public void testPrivateNetErrorMessage() {
        try {
            SafeUrlValidator.validate("http://10.0.0.1/");
            Assert.fail("Should throw");
        } catch (ClientException e) {
            Assert.assertEquals("ERR_BLOCKED_URL_IP", e.getErrCode());
            Assert.assertTrue(e.getMessage().contains("private"));
        }
    }
}
