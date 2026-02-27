/*
 * Copyright 2025 Schubert Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.iso6523;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Converts the ISO 6523 ICD list PDF into CSV files:
 * <ul>
 *   <li>Summary CSV (pages 1–7): ICD code and Name of Scheme</li>
 *   <li>Details CSV (pages 8+): one row per ICD with all documented fields</li>
 *   <li>Combined CSV: Details with Name of Scheme as 2nd column (joined from Summary)</li>
 * </ul>
 */
public final class IcdPdfToCsv {

    private static final String PDF_SOURCE_URL = "http://iso6523.info/icd_list.pdf";
    private static final String PDF_SOURCE_DATE = "2025-11-08";
    private static final int SUMMARY_START_PAGE = 1;
    private static final int SUMMARY_END_PAGE = 7;
    private static final int DETAILS_START_PAGE = 8;
    private static final String COMBINED_ICD_HEADER = "International Code Designator (ICD)";
    /** Replaces actual line breaks when merging multi-line values (CSV-safe). */
    private static final String LINE_BREAK_REPLACEMENT = "\\n";

    private static final java.util.Set<String> KNOWN_HEADERS = java.util.Set.of(
            "International Code Designator", "ICD", "Name of Coding System",
            "Intended Purpose/App. Area", "Issuing Organization", "Structure of Code",
            "Display Requirements", "Character Repertoire", "Language(s) Used",
            "Supports Org. Parts?", "Org. Identifier Reuse", "Orgs Covered by System",
            "Orgs Covered By System", "Notes on Use of Code", "Alt. Names for Scheme",
            "Alt. Names of Scheme", "Sponsoring Authority", "Date of Issue of ICD",
            "Additional Comments", "Record Last Updated",
            "number of characters and their significance, if any: 1)",
            "identification of the check digit characters, if any: 2)"
    );

    /** Headers that may be absent on some pages (e.g. Structure-of-Code sub-headers). No missing warning. */
    private static final java.util.Set<String> OPTIONAL_HEADERS = java.util.Set.of(
            "number of characters and their significance, if any: 1)",
            "identification of the check digit characters, if any: 2)"
    );

    private static final java.util.Map<String, String> HEADER_NORMALIZE = java.util.Map.of(
            "Orgs Covered By System", "Orgs Covered by System",
            "Alt. Names of Scheme", "Alt. Names for Scheme"
    );

    /** Headers sorted by length descending – for stable "header at start of line" matching (longest first). */
    private static final java.util.List<String> HEADERS_BY_LENGTH_DESC = KNOWN_HEADERS.stream()
            .sorted((a, b) -> Integer.compare(b.length(), a.length()))
            .toList();

    private record HeaderMatch(String header, String value) {}

    /** Ensures ICD is output as a 4-digit string (e.g. 1 -> 0001). Non-numeric values are returned unchanged. */
    private static String formatIcdFourDigits(String icd) {
        if (icd == null || icd.isBlank()) return "";
        var s = icd.trim();
        if (s.matches("[0-9]+")) {
            try {
                return String.format("%04d", Integer.parseInt(s));
            } catch (NumberFormatException ignored) {}
        }
        return s;
    }

    /** ICD/International Code Designator appears only at record start with a 4-digit code. Reject when value looks like doc (e.g. "(4-digit)"). */
    private static boolean isInternationalCodeDesignatorDocOnly(String fieldName, String value) {
        if (!"International Code Designator".equals(fieldName) && !"ICD".equals(fieldName)) return false;
        return !value.trim().matches("[0-9]{4}(\\s.*)?");
    }

    /** Matches known headers at the start of a line (bold headers heuristic). Returns header + value or null. */
    private static HeaderMatch matchHeaderAtLineStart(String line) {
        for (var h : HEADERS_BY_LENGTH_DESC) {
            if ("International Code Designator".equals(h) || "ICD".equals(h)) {
                continue;
            }
            if (line.equals(h)) {
                return new HeaderMatch(HEADER_NORMALIZE.getOrDefault(h, h), "");
            }
            if (line.startsWith(h + " ")) {
                var value = line.substring(h.length() + 1).trim();
                return new HeaderMatch(HEADER_NORMALIZE.getOrDefault(h, h), value);
            }
            if (line.startsWith(h + ":")) {
                var value = line.substring(h.length() + 1).replaceFirst("^\\s*", "").trim();
                return new HeaderMatch(HEADER_NORMALIZE.getOrDefault(h, h), value);
            }
        }
        return null;
    }

    private IcdPdfToCsv() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        var projectRoot = Path.of("").toAbsolutePath();
        var defaultPdfPath = projectRoot.resolve("src/test/resources/icd_list.pdf");
        var pdfPath = args.length >= 1 ? Path.of(args[0]) : defaultPdfPath;

        if (pdfPath.equals(defaultPdfPath)) {
            pdfPath = ensurePdfUpToDate(pdfPath);
        }

        if (!Files.exists(pdfPath)) {
            throw new IllegalArgumentException("PDF not found at: " + pdfPath.toAbsolutePath());
        }

        var outputDir = args.length >= 2 ? Path.of(args[1]) : projectRoot.resolve("target");
        Files.createDirectories(outputDir);

        var summaryCsv = outputDir.resolve("icd_summary.csv");
        var detailsCsv = outputDir.resolve("icd_details.csv");

        var combinedCsv = outputDir.resolve("icd_combined.csv");
        try (var document = Loader.loadPDF(pdfPath.toFile())) {
            var summaryRecords = extractSummaryRecords(document);
            writeSummaryCsv(summaryCsv, summaryRecords);

            var details = extractDetailRecords(document);
            writeDetailsCsv(detailsCsv, details);
            details.missingHeaderWarnings().forEach(System.out::println);

            writeCombinedCsv(combinedCsv, summaryRecords, details);
        }

        var referencesDir = projectRoot.resolve("src/test/resources/references");
        Files.createDirectories(referencesDir);
        Files.copy(summaryCsv, referencesDir.resolve("icd_summary.csv"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(detailsCsv, referencesDir.resolve("icd_details.csv"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(combinedCsv, referencesDir.resolve("icd_combined.csv"), StandardCopyOption.REPLACE_EXISTING);

        System.out.println("Summary CSV written to  : " + summaryCsv.toAbsolutePath());
        System.out.println("Details CSV written to  : " + detailsCsv.toAbsolutePath());
        System.out.println("Combined CSV written to : " + combinedCsv.toAbsolutePath());
        System.out.println("Reference copies in     : " + referencesDir.toAbsolutePath());
    }

    private static Path ensurePdfUpToDate(Path defaultPdfPath) throws IOException, InterruptedException {
        try {
            Files.createDirectories(defaultPdfPath.getParent());
            var tempFile = defaultPdfPath.getParent().resolve("icd_list_download.tmp");

            String dateStr;
            try (var client = HttpClient.newBuilder()
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .build()) {
                var request = HttpRequest.newBuilder(URI.create(PDF_SOURCE_URL)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("Download failed: HTTP " + response.statusCode());
                }
                try (var in = response.body()) {
                    Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                }
                dateStr = parseDateFromResponse(response).orElse(PDF_SOURCE_DATE);
            }

            if (Files.exists(defaultPdfPath) && sha256Hex(defaultPdfPath).equals(sha256Hex(tempFile))) {
                Files.deleteIfExists(tempFile);
                return defaultPdfPath;
            }

            var datedPath = defaultPdfPath.getParent().resolve("icd_list_%s.pdf".formatted(dateStr));
            Files.move(tempFile, datedPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("PDF downloaded: %s from %s".formatted(datedPath.getFileName(), PDF_SOURCE_URL));
            return datedPath;
        } catch (Exception e) {
            if (Files.exists(defaultPdfPath)) {
                System.err.println("Could not verify/update PDF (" + e.getMessage() + "); using local file.");
                return defaultPdfPath;
            }
            throw new IOException("Could not download PDF from " + PDF_SOURCE_URL + ": " + e.getMessage(), e);
        }
    }

    private static Optional<String> parseDateFromResponse(HttpResponse<?> response) {
        return response.headers().firstValue("last-modified")
                .flatMap(header -> {
                    try {
                        return Optional.of(java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toLocalDate()
                                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
                    } catch (Exception __) {
                        return Optional.empty();
                    }
                });
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                var buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Removes trailing " END OF LIST" (PDF list-end marker, not part of the actual name). */
    private static String stripEndOfListNoise(String value) {
        if (value == null) return "";
        return value.endsWith(" END OF LIST") ? value.substring(0, value.length() - " END OF LIST".length()).trim() : value;
    }

    private static java.util.List<SummaryRecord> extractSummaryRecords(PDDocument document) throws IOException {
        var stripper = new PDFTextStripper();
        var records = new java.util.ArrayList<SummaryRecord>();
        var lastPage = Math.min(SUMMARY_END_PAGE, document.getNumberOfPages());

        for (var page = SUMMARY_START_PAGE; page <= lastPage; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            var lines = stripper.getText(document).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("Page ") && !line.startsWith("--"))
                    .filter(line -> !(line.startsWith("ICD") && line.contains("Name of Scheme")))
                    .toList();

            String currentIcd = null;
            var nameBuilder = new StringBuilder();

            for (var line : lines) {
                if (line.matches("^[0-9]{4}\\s+.+")) {
                    if (currentIcd != null) {
                        records.add(new SummaryRecord(currentIcd, stripEndOfListNoise(nameBuilder.toString())));
                    }
                    var firstSpace = line.indexOf(' ');
                    currentIcd = line.substring(0, firstSpace).trim();
                    nameBuilder.setLength(0);
                    nameBuilder.append(line.substring(firstSpace).trim());
                } else if (currentIcd != null && !line.isBlank()) {
                    nameBuilder.append(" ").append(line);
                }
            }
            if (currentIcd != null) {
                records.add(new SummaryRecord(currentIcd, stripEndOfListNoise(nameBuilder.toString())));
            }
        }
        return records;
    }

    private static DetailExtractionResult extractDetailRecords(PDDocument document) throws IOException {
        var numberOfPages = document.getNumberOfPages();
        if (numberOfPages < DETAILS_START_PAGE) {
            return new DetailExtractionResult(java.util.List.of(), java.util.List.of(), java.util.List.of());
        }

        var stripper = new PDFTextStripper();
        var canonicalHeaders = new java.util.LinkedHashSet<String>();
        canonicalHeaders.add("International Code Designator");
        var rows = new java.util.ArrayList<java.util.Map<String, String>>();
        var headersPerPage = new java.util.LinkedHashMap<Integer, java.util.Set<String>>();

        for (var page = DETAILS_START_PAGE; page <= numberOfPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            var lines = stripper.getText(document).split("\\r?\\n");

            var values = new java.util.LinkedHashMap<String, String>();
            String currentField = null;
            var inRecord = false;
            var headersOnThisPage = new java.util.LinkedHashSet<String>();
            String previousLine = null;

            for (var rawLine : lines) {
                var line = rawLine.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("--") && line.contains("of")) break;
                if (line.startsWith("Page ")) continue;

                if (inRecord && previousLine != null) {
                    var combined = previousLine + " " + line;
                    var multiLineMatch = matchHeaderAtLineStart(combined);
                    if (multiLineMatch != null) {
                        if (currentField != null) {
                            var existing = values.get(currentField);
                            if (existing != null) {
                                if (existing.equals(previousLine)) {
                                    values.put(currentField, "");
                                } else if (existing.endsWith(LINE_BREAK_REPLACEMENT + previousLine)) {
                                    values.put(currentField, existing.substring(0, existing.length() - LINE_BREAK_REPLACEMENT.length() - previousLine.length()).trim());
                                }
                            }
                        }
                        var fieldName = multiLineMatch.header();
                        var fieldValue = multiLineMatch.value();
                        currentField = fieldName;
                        headersOnThisPage.add(fieldName);
                        canonicalHeaders.add(fieldName);
                        values.merge(fieldName, fieldValue, (old, v) -> old + LINE_BREAK_REPLACEMENT + v);
                        if (fieldValue.isEmpty()) values.putIfAbsent(fieldName, "");
                        previousLine = null;
                        continue;
                    }
                }

                if (!inRecord && line.startsWith("International Code Designator")) {
                    var value = line.substring("International Code Designator".length()).replaceFirst("^:\\s*", "").trim();
                    values.put("International Code Designator", value);
                    inRecord = true;
                    currentField = null;
                    headersOnThisPage.add("International Code Designator");
                    canonicalHeaders.add("International Code Designator");
                    continue;
                }
                if (!inRecord) continue;

                var colonPos = line.indexOf(':');
                if (colonPos > 0) {
                    var fieldNameRaw = line.substring(0, colonPos).trim();
                    if (KNOWN_HEADERS.contains(fieldNameRaw)) {
                        var fieldName = HEADER_NORMALIZE.getOrDefault(fieldNameRaw, fieldNameRaw);
                        var fieldValue = line.substring(colonPos + 1).trim();
                        if (isInternationalCodeDesignatorDocOnly(fieldName, fieldValue)) {
                        } else {
                            currentField = fieldName;
                            headersOnThisPage.add(fieldName);
                            canonicalHeaders.add(fieldName);
                            values.merge(fieldName, fieldValue, (old, v) -> old + LINE_BREAK_REPLACEMENT + v);
                            if (fieldValue.isEmpty()) values.putIfAbsent(fieldName, "");
                            previousLine = null;
                            continue;
                        }
                    }
                }
                var headerMatch = matchHeaderAtLineStart(line);
                if (headerMatch != null) {
                    var fieldName = headerMatch.header();
                    var fieldValue = headerMatch.value();
                    currentField = fieldName;
                    headersOnThisPage.add(fieldName);
                    canonicalHeaders.add(fieldName);
                    values.merge(fieldName, fieldValue, (old, v) -> old + LINE_BREAK_REPLACEMENT + v);
                    if (fieldValue.isEmpty()) values.putIfAbsent(fieldName, "");
                    previousLine = null;
                    continue;
                }
                if (currentField != null) {
                    var existing = values.getOrDefault(currentField, "");
                    values.put(currentField, existing.isEmpty() ? line : existing + LINE_BREAK_REPLACEMENT + line);
                    previousLine = line;
                } else {
                    previousLine = null;
                }
            }

            if (!values.isEmpty()) {
                rows.add(values);
                headersPerPage.put(page, new java.util.LinkedHashSet<>(headersOnThisPage));
            }
        }

        var requiredHeaders = new java.util.LinkedHashSet<>(canonicalHeaders);
        requiredHeaders.removeAll(OPTIONAL_HEADERS);
        var missingWarnings = headersPerPage.entrySet().stream()
                .filter(e -> !e.getValue().containsAll(requiredHeaders))
                .map(e -> {
                    var missing = new java.util.LinkedHashSet<>(requiredHeaders);
                    missing.removeAll(e.getValue());
                    return "Seite %d: Folgende Spaltenüberschriften fehlen: %s".formatted(e.getKey(), String.join(", ", missing));
                })
                .toList();

        return new DetailExtractionResult(new java.util.ArrayList<>(canonicalHeaders), rows, missingWarnings);
    }

    private static void writeSummaryCsv(Path file, java.util.List<SummaryRecord> records) throws IOException {
        Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeCsvRow(writer, "ICD", "Name of Scheme");
            for (var r : records) {
                writeCsvRow(writer, formatIcdFourDigits(r.icd()), r.name());
            }
        }
    }

    /** Writes combined CSV: details with "Name of Scheme" as 2nd column, joined from summary. Call after both CSVs exist. */
    private static void writeCombinedCsv(Path file, java.util.List<SummaryRecord> summaryRecords,
            DetailExtractionResult details) throws IOException {
        var icdToName = summaryRecords.stream()
                .collect(Collectors.toMap(r -> formatIcdFourDigits(r.icd()), SummaryRecord::name, (a, b) -> a));

        Files.createDirectories(file.getParent());
        var combinedHeaders = new java.util.ArrayList<String>();
        var combinedAdded = false;
        for (var h : details.headers()) {
            if ("International Code Designator".equals(h) || "ICD".equals(h)) {
                if (!combinedAdded) {
                    combinedHeaders.add(COMBINED_ICD_HEADER);
                    combinedHeaders.add("'Scheme' equal 'Coding System'");
                    combinedHeaders.add("Name of Scheme");
                    combinedAdded = true;
                }
            } else {
                combinedHeaders.add(h);
            }
        }

        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeCsvRow(writer, combinedHeaders);
            for (var row : details.rows()) {
                var icd = row.getOrDefault("ICD", row.getOrDefault("International Code Designator", ""));
                var icdFormatted = formatIcdFourDigits(icd);
                var nameOfScheme = stripEndOfListNoise(icdToName.getOrDefault(icdFormatted, ""));
                var nameOfCodingSystem = stripEndOfListNoise(row.getOrDefault("Name of Coding System", ""));
                var schemeEqualsCodingSystem = nameOfScheme.equals(nameOfCodingSystem);
                var fields = new java.util.ArrayList<String>();
                for (var h : combinedHeaders) {
                    if (COMBINED_ICD_HEADER.equals(h)) {
                        fields.add(icdFormatted);
                    } else if ("'Scheme' equal 'Coding System'".equals(h)) {
                        fields.add(String.valueOf(schemeEqualsCodingSystem));
                    } else if ("Name of Scheme".equals(h)) {
                        fields.add(nameOfScheme);
                    } else {
                        fields.add(row.getOrDefault(h, ""));
                    }
                }
                writeCsvRow(writer, fields);
            }
        }
    }

    private static void writeDetailsCsv(Path file, DetailExtractionResult details) throws IOException {
        Files.createDirectories(file.getParent());
        var combinedHeaders = new java.util.ArrayList<String>();
        var combinedAdded = false;
        for (var h : details.headers()) {
            if ("International Code Designator".equals(h) || "ICD".equals(h)) {
                if (!combinedAdded) {
                    combinedHeaders.add(COMBINED_ICD_HEADER);
                    combinedAdded = true;
                }
            } else {
                combinedHeaders.add(h);
            }
        }

        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeCsvRow(writer, combinedHeaders);
            for (var row : details.rows()) {
                var fields = combinedHeaders.stream()
                        .map(h -> COMBINED_ICD_HEADER.equals(h)
                                ? formatIcdFourDigits(row.getOrDefault("ICD", row.getOrDefault("International Code Designator", "")))
                                : row.getOrDefault(h, ""))
                        .toList();
                writeCsvRow(writer, fields);
            }
        }
    }

    private static void writeCsvRow(Writer writer, String... fields) throws IOException {
        writeCsvRow(writer, java.util.List.of(fields));
    }

    private static void writeCsvRow(Writer writer, java.util.List<String> fields) throws IOException {
        writer.write(fields.stream().map(IcdPdfToCsv::escapeCsv).collect(Collectors.joining(",")) + System.lineSeparator());
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        var escaped = value.replace("\"", "\"\"");
        return (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r"))
                ? "\"" + escaped + "\""
                : escaped;
    }

    private record SummaryRecord(String icd, String name) {}
    private record DetailExtractionResult(
            java.util.List<String> headers,
            java.util.List<java.util.Map<String, String>> rows,
            java.util.List<String> missingHeaderWarnings
    ) {}
}
