import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// Simple multipart/form-data parser for file uploads in Java without external libraries.
public class MultipartRequest {

    public static final int MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB limit

    public static class FileItem {
        public final String fieldName;
        public final String fileName;
        public final String contentType;
        public final byte[] data;

        public FileItem(String fieldName, String fileName, String contentType, byte[] data) {
            this.fieldName = fieldName;
            this.fileName = fileName;
            this.contentType = contentType;
            this.data = data;
        }

        public boolean isValidPdf() {
            if (data == null || data.length == 0 || data.length > MAX_FILE_SIZE) {
                return false;
            }
            if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
                return false;
            }
            // Check PDF magic signature: starts with %PDF- (0x25, 0x50, 0x44, 0x46, 0x2D)
            if (data.length < 5) {
                return false;
            }
            return data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F' && data[4] == '-';
        }
    }

    private final Map<String, String> fields = new HashMap<String, String>();
    private final Map<String, FileItem> files = new HashMap<String, FileItem>();

    public Map<String, String> getFields() { return fields; }
    public Map<String, FileItem> getFiles() { return files; }

    public static MultipartRequest parse(InputStream stream, String contentTypeHeader, int maxTotalBytes) throws Exception {
        if (contentTypeHeader == null || !contentTypeHeader.toLowerCase().contains("multipart/form-data")) {
            throw new IllegalArgumentException("Not a multipart/form-data request.");
        }

        // Extract boundary
        String boundary = null;
        for (String param : contentTypeHeader.split(";")) {
            String trimmed = param.trim();
            if (trimmed.toLowerCase().startsWith("boundary=")) {
                boundary = trimmed.substring("boundary=".length()).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalArgumentException("Boundary delimiter missing in Content-Type.");
        }

        // Read all stream bytes up to limit
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        int total = 0;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > maxTotalBytes) {
                throw new IllegalArgumentException("Uploaded data exceeds the allowed limit.");
            }
            buffer.write(chunk, 0, read);
        }
        byte[] body = buffer.toByteArray();

        MultipartRequest parsed = new MultipartRequest();
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        int pos = 0;
        while (pos < body.length) {
            int partStart = findBytes(body, boundaryBytes, pos);
            if (partStart == -1) break;
            partStart += boundaryBytes.length;

            // Check if end of multipart (boundary followed by --)
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                break;
            }
            // Skip CRLF
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            } else if (partStart < body.length && body[partStart] == '\n') {
                partStart += 1;
            }

            int nextBoundary = findBytes(body, boundaryBytes, partStart);
            if (nextBoundary == -1) break;

            int partEnd = nextBoundary;
            if (partEnd >= 2 && body[partEnd - 2] == '\r' && body[partEnd - 1] == '\n') {
                partEnd -= 2;
            } else if (partEnd >= 1 && body[partEnd - 1] == '\n') {
                partEnd -= 1;
            }

            // In this part, find header separator: \r\n\r\n or \n\n
            int headerEnd = -1;
            int bodyStart = -1;
            for (int i = partStart; i < partEnd - 1; i++) {
                if (body[i] == '\r' && body[i + 1] == '\n' && i + 3 <= partEnd && body[i + 2] == '\r' && body[i + 3] == '\n') {
                    headerEnd = i;
                    bodyStart = i + 4;
                    break;
                } else if (body[i] == '\n' && body[i + 1] == '\n') {
                    headerEnd = i;
                    bodyStart = i + 2;
                    break;
                }
            }

            if (headerEnd != -1 && bodyStart != -1 && bodyStart <= partEnd) {
                String headerText = new String(body, partStart, headerEnd - partStart, StandardCharsets.UTF_8);
                String fieldName = extractHeaderParam(headerText, "name");
                String rawFileName = extractHeaderParam(headerText, "filename");
                String contentType = extractHeaderValue(headerText, "Content-Type");

                byte[] partData = new byte[partEnd - bodyStart];
                System.arraycopy(body, bodyStart, partData, 0, partData.length);

                if (rawFileName != null) {
                    // Sanitize filename: remove directory paths and special characters
                    String cleanName = sanitizeFileName(rawFileName);
                    if (!cleanName.isEmpty() && partData.length > 0) {
                        parsed.files.put(fieldName != null ? fieldName : "file",
                                new FileItem(fieldName, cleanName, contentType, partData));
                    }
                } else if (fieldName != null) {
                    String value = new String(partData, StandardCharsets.UTF_8);
                    parsed.fields.put(fieldName, value);
                }
            }

            pos = nextBoundary;
        }

        return parsed;
    }

    private static int findBytes(byte[] source, byte[] target, int fromIndex) {
        if (target.length == 0 || fromIndex >= source.length) return -1;
        outer:
        for (int i = fromIndex; i <= source.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String extractHeaderParam(String headers, String param) {
        String lowerParam = param.toLowerCase() + "=";
        for (String line : headers.split("\r?\n")) {
            if (line.toLowerCase().startsWith("content-disposition:")) {
                int idx = line.toLowerCase().indexOf(lowerParam);
                if (idx != -1) {
                    String val = line.substring(idx + lowerParam.length()).trim();
                    if (val.startsWith("\"")) {
                        int end = val.indexOf('"', 1);
                        if (end != -1) return val.substring(1, end);
                    } else {
                        int end = val.indexOf(';');
                        return end != -1 ? val.substring(0, end).trim() : val;
                    }
                }
            }
        }
        return null;
    }

    private static String extractHeaderValue(String headers, String headerName) {
        String prefix = headerName.toLowerCase() + ":";
        for (String line : headers.split("\r?\n")) {
            if (line.toLowerCase().startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "application/octet-stream";
    }

    // Prevents directory traversal attacks by extracting only the clean base filename
    public static String sanitizeFileName(String original) {
        if (original == null) return "";
        // Strip out paths
        String name = original.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash != -1) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_").trim();
        return name;
    }
}
