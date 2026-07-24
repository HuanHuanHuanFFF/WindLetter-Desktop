package com.windletter.desktop.receive;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Best-effort save extension inferred from the authenticated payload MIME. */
public final class ReceivePayloadFileName {

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
        Map.entry("application/pdf", "pdf"),
        Map.entry("application/json", "json"),
        Map.entry("application/xml", "xml"),
        Map.entry("application/zip", "zip"),
        Map.entry("application/x-zip-compressed", "zip"),
        Map.entry("application/gzip", "gz"),
        Map.entry("application/x-gzip", "gz"),
        Map.entry("application/x-7z-compressed", "7z"),
        Map.entry("application/vnd.rar", "rar"),
        Map.entry("application/x-rar-compressed", "rar"),
        Map.entry("application/msword", "doc"),
        Map.entry(
            "application/vnd.openxmlformats-officedocument"
                + ".wordprocessingml.document",
            "docx"
        ),
        Map.entry("application/vnd.ms-excel", "xls"),
        Map.entry(
            "application/vnd.openxmlformats-officedocument"
                + ".spreadsheetml.sheet",
            "xlsx"
        ),
        Map.entry("application/vnd.ms-powerpoint", "ppt"),
        Map.entry(
            "application/vnd.openxmlformats-officedocument"
                + ".presentationml.presentation",
            "pptx"
        ),
        Map.entry("image/jpeg", "jpg"),
        Map.entry("image/png", "png"),
        Map.entry("image/gif", "gif"),
        Map.entry("image/webp", "webp"),
        Map.entry("image/svg+xml", "svg"),
        Map.entry("image/bmp", "bmp"),
        Map.entry("image/tiff", "tiff"),
        Map.entry("audio/mpeg", "mp3"),
        Map.entry("audio/wav", "wav"),
        Map.entry("audio/x-wav", "wav"),
        Map.entry("audio/ogg", "ogg"),
        Map.entry("video/mp4", "mp4"),
        Map.entry("video/webm", "webm"),
        Map.entry("text/csv", "csv"),
        Map.entry("text/html", "html"),
        Map.entry("text/css", "css"),
        Map.entry("text/markdown", "md"),
        Map.entry("text/calendar", "ics"),
        Map.entry("text/javascript", "js")
    );

    private ReceivePayloadFileName() {
    }

    public static String suggestedName(String contentType) {
        return "recovered-payload." + suggestedExtension(contentType);
    }

    public static String suggestedExtension(String contentType) {
        Objects.requireNonNull(contentType, "contentType");
        String mime = contentType
            .split(";", 2)[0]
            .trim()
            .toLowerCase(Locale.ROOT);
        String known = EXTENSIONS.get(mime);
        if (known != null) {
            return known;
        }
        if (mime.endsWith("+json")) {
            return "json";
        }
        if (mime.endsWith("+xml")) {
            return "xml";
        }
        if (mime.startsWith("text/")) {
            return "txt";
        }
        return "bin";
    }
}
