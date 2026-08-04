package org.sunbird.common;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.sunbird.common.exception.ClientException;

/**
 * Zip extraction utility with zip-slip and path-traversal protections.
 * Provides safe extraction and path validation for IMS-CP manifest-based packages (SCORM, QTI, etc).
 */
public class SafeZipExtractor {

    /**
     * Validates that a zip file contains all required entries.
     *
     * @param zipFile zip file to check
     * @param requiredEntries list of required entry names (e.g., "imsmanifest.xml")
     * @return true if all required entries exist, false otherwise
     * @throws ClientException if zip is invalid or cannot be read
     */
    public static boolean hasRequiredEntries(File zipFile, List<String> requiredEntries) throws ClientException {
        if (zipFile == null || !zipFile.exists()) {
            return false;
        }
        try (ZipFile zf = new ZipFile(zipFile)) {
            return requiredEntries.stream()
                    .allMatch(entry -> zf.getEntry(entry) != null);
        } catch (IOException e) {
            throw new ClientException("ERR_INVALID_FILE", "Invalid or corrupted zip file: " + e.getMessage());
        }
    }

    /**
     * Extracts a zip file to a destination directory with zip-slip protection.
     * Validates that all extracted paths remain within the destination base directory.
     *
     * @param zipFile zip file to extract
     * @param destBasePath destination directory (will be created if it doesn't exist)
     * @throws ClientException if extraction fails or zip-slip attempt detected
     */
    public static void extract(File zipFile, String destBasePath) throws ClientException {
        try {
            Path baseDir = Paths.get(destBasePath).normalize();
            Files.createDirectories(baseDir);
            Path resolvedBaseDir = baseDir.toRealPath();

            try (ZipFile zf = new ZipFile(zipFile)) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    Path path = resolvedBaseDir.resolve(entry.getName()).normalize();

                    if (!path.startsWith(resolvedBaseDir)) {
                        throw new ClientException("ERR_INVALID_ZIP_ENTRY",
                                "Zip entry attempts path traversal: " + entry.getName());
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(path);
                    } else {
                        Files.createDirectories(path.getParent());
                        Files.copy(zf.getInputStream(entry), path);
                    }
                }
            }
        } catch (ClientException e) {
            throw e;
        } catch (IOException e) {
            throw new ClientException("ERR_INVALID_FILE", "Failed to extract zip: " + e.getMessage());
        }
    }

    /**
     * Resolves a relative href (from a manifest) against a base extraction directory,
     * ensuring the resolved path does not escape the base directory.
     * Handles query strings and fragments by stripping them before resolution.
     *
     * @param basePath base extraction directory
     * @param relativeHref relative path or href (may contain query strings/fragments)
     * @return resolved path relative to basePath
     * @throws ClientException if path traversal is attempted
     */
    public static String resolveWithinBase(String basePath, String relativeHref) throws ClientException {
        if (relativeHref == null || relativeHref.isEmpty()) {
            throw new ClientException("ERR_INVALID_FILE", "Invalid relative href");
        }

        String cleanHref = relativeHref;
        int delimiterIndex = cleanHref.indexOf('?');
        if (delimiterIndex == -1) {
            delimiterIndex = cleanHref.indexOf('#');
        }
        if (delimiterIndex != -1) {
            cleanHref = cleanHref.substring(0, delimiterIndex);
        }

        Path baseDirPath = Paths.get(basePath);
        Path resolvedPath = baseDirPath.resolve(cleanHref).normalize();

        if (!resolvedPath.startsWith(baseDirPath)) {
            throw new ClientException("ERR_INVALID_FILE",
                    "Potential path traversal detected: " + relativeHref);
        }

        if (!resolvedPath.toFile().exists()) {
            throw new ClientException("ERR_INVALID_FILE",
                    "Referenced file does not exist: " + cleanHref);
        }

        return cleanHref;
    }
}
