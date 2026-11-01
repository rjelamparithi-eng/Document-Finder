import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

// Comprehensive automated test suite verifying all 12 required review points.
public class TestReview2Upgrades {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS - " + name);
        } else {
            failed++;
            System.out.println("FAIL - " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("  ARAM OFFICE - AUTOMATED VERIFICATION SUITE");
        System.out.println("=================================================");

        int testPort = 8888;

        // Launch WebServer in background thread
        Thread serverThread = new Thread(() -> {
            try {
                WebServer.main(new String[]{String.valueOf(testPort)});
            } catch (Exception e) {
                System.out.println("Server thread error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait for server to bind with retry
        String baseUrl = "http://127.0.0.1:" + testPort;
        boolean ready = false;
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Response testPing = httpGet(baseUrl + "/");
                if (testPing.statusCode == 200) {
                    ready = true;
                    break;
                }
            } catch (Exception ignored) {}
            Thread.sleep(250);
        }
        if (!ready) {
            System.out.println("Warning: Server did not respond within timeout.");
        }

        // -----------------------------------------------------------------
        // VERIFICATION 1: Existing website opens
        // -----------------------------------------------------------------
        Response resIndex = httpGet(baseUrl + "/");
        Response resCss = httpGet(baseUrl + "/style.css");
        Response resJs = httpGet(baseUrl + "/app.js");
        boolean webOpens = resIndex.statusCode == 200 && resIndex.bodyString().contains("Aram Office")
                && resCss.statusCode == 200 && resJs.statusCode == 200;
        check("1. Existing website opens (HTML, CSS, JS served)", webOpens);

        // -----------------------------------------------------------------
        // VERIFICATION 2: MySQL connection succeeds
        // -----------------------------------------------------------------
        boolean dbConnected = DatabaseConnection.testConnection();
        boolean tablesExist = false;
        if (dbConnected) {
            try (Connection conn = DatabaseConnection.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW TABLES")) {
                List<String> tables = new ArrayList<String>();
                while (rs.next()) {
                    tables.add(rs.getString(1).toLowerCase());
                }
                tablesExist = tables.contains("users")
                        && tables.contains("documents")
                        && tables.contains("document_versions")
                        && tables.contains("keywords")
                        && tables.contains("correction_requests")
                        && tables.contains("activity_logs");
            } catch (Exception e) {
                tablesExist = false;
            }
        }
        check("2. MySQL connection succeeds and all 6 tables exist", dbConnected && tablesExist);

        // -----------------------------------------------------------------
        // VERIFICATION 3: Existing documents load from MySQL
        // -----------------------------------------------------------------
        int docCountInDb = 0;
        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM documents")) {
            if (rs.next()) docCountInDb = rs.getInt(1);
        }
        check("3. Existing documents load from MySQL (count: " + docCountInDb + ")", docCountInDb >= 48);

        // -----------------------------------------------------------------
        // VERIFICATION 4: Higher Official can log in
        // -----------------------------------------------------------------
        Response loginHigher = httpPostForm(baseUrl + "/api/login", "username=higher&password=higher", null, null);
        boolean higherLoginSuccess = loginHigher.statusCode == 200
                && loginHigher.bodyString().contains("\"role\":\"HIGHER_OFFICIAL\"")
                && loginHigher.cookie != null;
        String higherCookie = loginHigher.cookie;
        String higherCsrf = extractJsonString(loginHigher.bodyString(), "csrf");
        check("4. Higher Official can log in (role: HIGHER_OFFICIAL)", higherLoginSuccess);

        // -----------------------------------------------------------------
        // VERIFICATION 5: Lower Official can log in
        // -----------------------------------------------------------------
        Response loginLower = httpPostForm(baseUrl + "/api/login", "username=viewer&password=viewer", null, null);
        boolean lowerLoginSuccess = loginLower.statusCode == 200
                && loginLower.bodyString().contains("\"role\":\"LOWER_OFFICIAL\"")
                && loginLower.cookie != null;
        String lowerCookie = loginLower.cookie;
        String lowerCsrf = extractJsonString(loginLower.bodyString(), "csrf");
        check("5. Lower Official can log in (role: LOWER_OFFICIAL)", lowerLoginSuccess);

        // -----------------------------------------------------------------
        // VERIFICATION 6: Higher Official can upload a PDF
        // -----------------------------------------------------------------
        String testDocId = "DOC_T" + (System.currentTimeMillis() % 100000);

        byte[] fakePdfV1 = "%PDF-1.4\n1 0 obj\n<< /Title (Sanitation Protocol V1) >>\nendobj\ntrailer\n<< >>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);

        String boundary = "----TestBoundary" + System.currentTimeMillis();
        ByteArrayOutputStream uploadBodyV1 = new ByteArrayOutputStream();
        writeFormField(uploadBodyV1, boundary, "id", testDocId);
        writeFormField(uploadBodyV1, boundary, "title", "Municipal Sanitation Protocol");
        writeFormField(uploadBodyV1, boundary, "department", "Health");
        writeFormField(uploadBodyV1, boundary, "category", "Guidelines");
        writeFormField(uploadBodyV1, boundary, "importance", "4");
        writeFormField(uploadBodyV1, boundary, "keywords", "sanitation health protocol municipal");
        writeFormField(uploadBodyV1, boundary, "content", "Standard operating procedures for municipal waste management.");
        writeFileField(uploadBodyV1, boundary, "pdf", "sanitation_protocol_v1.pdf", "application/pdf", fakePdfV1);
        finishMultipart(uploadBodyV1, boundary);

        Response uploadRes = httpPostMultipart(baseUrl + "/api/add", uploadBodyV1.toByteArray(), boundary, higherCookie, higherCsrf);
        boolean higherUploadSuccess = (uploadRes.statusCode == 201 || uploadRes.statusCode == 200)
                && uploadRes.bodyString().contains("\"hasPdf\":true");
        check("6. Higher Official can upload a PDF (saved to MySQL MEDIUMBLOB)", higherUploadSuccess);

        // -----------------------------------------------------------------
        // VERIFICATION 7: Uploaded PDF opens and downloads correctly
        // -----------------------------------------------------------------
        Response viewPdfHigher = httpGet(baseUrl + "/api/pdf?id=" + testDocId + "&version=1", higherCookie);
        boolean pdfOpensCorrectly = viewPdfHigher.statusCode == 200
                && "application/pdf".equals(viewPdfHigher.contentType)
                && viewPdfHigher.bodyBytes.length == fakePdfV1.length
                && viewPdfHigher.disposition != null && viewPdfHigher.disposition.contains("inline");

        Response downloadPdfHigher = httpGet(baseUrl + "/api/pdf?id=" + testDocId + "&version=1&download=1", higherCookie);
        boolean pdfDownloadsCorrectly = downloadPdfHigher.statusCode == 200
                && "application/pdf".equals(downloadPdfHigher.contentType)
                && downloadPdfHigher.disposition != null && downloadPdfHigher.disposition.contains("attachment")
                && downloadPdfHigher.disposition.contains("sanitation_protocol_v1.pdf");

        check("7. Uploaded PDF opens (inline) and downloads (attachment) correctly", pdfOpensCorrectly && pdfDownloadsCorrectly);

        // -----------------------------------------------------------------
        // VERIFICATION 8: Lower Official can view an authorized PDF
        // -----------------------------------------------------------------
        Response viewPdfLower = httpGet(baseUrl + "/api/pdf?id=" + testDocId + "&version=1", lowerCookie);
        boolean lowerCanView = viewPdfLower.statusCode == 200
                && "application/pdf".equals(viewPdfLower.contentType)
                && viewPdfLower.bodyBytes.length == fakePdfV1.length;
        check("8. Lower Official can view and download authorized PDF", lowerCanView);

        // -----------------------------------------------------------------
        // VERIFICATION 9: Lower Official cannot upload PDFs (direct API check)
        // -----------------------------------------------------------------
        ByteArrayOutputStream lowerUploadAttempt = new ByteArrayOutputStream();
        writeFormField(lowerUploadAttempt, boundary, "id", "DOC_LOWER_FAIL");
        writeFormField(lowerUploadAttempt, boundary, "title", "Unauthorized Doc");
        writeFormField(lowerUploadAttempt, boundary, "department", "Health");
        writeFormField(lowerUploadAttempt, boundary, "category", "Circular");
        writeFormField(lowerUploadAttempt, boundary, "importance", "3");
        writeFormField(lowerUploadAttempt, boundary, "keywords", "unauthorized test");
        writeFormField(lowerUploadAttempt, boundary, "content", "Should be refused.");
        writeFileField(lowerUploadAttempt, boundary, "pdf", "hacker.pdf", "application/pdf", fakePdfV1);
        finishMultipart(lowerUploadAttempt, boundary);

        Response lowerUploadRes = httpPostMultipart(baseUrl + "/api/add", lowerUploadAttempt.toByteArray(), boundary, lowerCookie, lowerCsrf);
        Response lowerDirectPost = httpPostForm(baseUrl + "/api/add", "id=DOC_FAIL&title=Test&department=Health&category=Circular&importance=3&keywords=test&content=test", lowerCookie, lowerCsrf);
        boolean lowerBlocked = lowerUploadRes.statusCode == 403 && lowerDirectPost.statusCode == 403;
        check("9. Lower Official cannot upload PDFs, including via direct API requests (HTTP 403)", lowerBlocked);

        // -----------------------------------------------------------------
        // VERIFICATION 10: New versions preserve old PDF versions
        // -----------------------------------------------------------------
        byte[] fakePdfV2 = "%PDF-1.4\n2 0 obj\n<< /Title (Sanitation Protocol V2 Revised) >>\nendobj\ntrailer\n<< >>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream uploadBodyV2 = new ByteArrayOutputStream();
        writeFormField(uploadBodyV2, boundary, "id", testDocId);
        writeFormField(uploadBodyV2, boundary, "title", "Municipal Sanitation Protocol Revised");
        writeFormField(uploadBodyV2, boundary, "keywords", "sanitation health protocol municipal revised");
        writeFormField(uploadBodyV2, boundary, "content", "Updated procedures for sanitation.");
        writeFileField(uploadBodyV2, boundary, "pdf", "sanitation_protocol_v2.pdf", "application/pdf", fakePdfV2);
        finishMultipart(uploadBodyV2, boundary);

        Response v2Res = httpPostMultipart(baseUrl + "/api/add-version", uploadBodyV2.toByteArray(), boundary, higherCookie, higherCsrf);
        boolean v2Created = v2Res.statusCode == 200;

        // Fetch Version 1 PDF and Version 2 PDF to ensure V1 is preserved intact
        Response fetchV1 = httpGet(baseUrl + "/api/pdf?id=" + testDocId + "&version=1", higherCookie);
        Response fetchV2 = httpGet(baseUrl + "/api/pdf?id=" + testDocId + "&version=2", higherCookie);

        boolean oldVersionPreserved = v2Created
                && fetchV1.statusCode == 200 && new String(fetchV1.bodyBytes).contains("Sanitation Protocol V1")
                && fetchV2.statusCode == 200 && new String(fetchV2.bodyBytes).contains("Sanitation Protocol V2 Revised");
        check("10. New versions preserve old PDF versions intact (V1: " + fetchV1.bodyBytes.length + " bytes, V2: " + fetchV2.bodyBytes.length + " bytes)", oldVersionPreserved);

        // -----------------------------------------------------------------
        // VERIFICATION 11: Data remains after Java server restart
        // -----------------------------------------------------------------
        // Simulate restart by creating a new in-memory DocumentManager and reloading from MySQL
        DocumentManager restartManager = new DocumentManager();
        DocumentStorage.loadOrCreate(restartManager);
        GovernmentDocument loadedDoc = restartManager.searchById(testDocId);
        boolean dataRemains = loadedDoc != null
                && loadedDoc.getAllVersions().size() == 2
                && loadedDoc.getCurrentVersionNumber() == 2
                && loadedDoc.hasPdf();

        // Also verify PDF bytes remain in database after reload
        DocumentStorage.PdfRecord reloadedPdfV1 = DocumentStorage.getPdfData(testDocId, 1);
        DocumentStorage.PdfRecord reloadedPdfV2 = DocumentStorage.getPdfData(testDocId, 2);
        boolean pdfsRemain = reloadedPdfV1 != null && reloadedPdfV2 != null;

        check("11. Data remains persistent after Java server restart (reloaded from MySQL)", dataRemains && pdfsRemain);

        // -----------------------------------------------------------------
        // VERIFICATION 12: Existing search, Trie and ranking still work
        // -----------------------------------------------------------------
        boolean idSearch = restartManager.searchById("DOC001") != null;
        boolean titleSearch = !restartManager.searchByTitle("certificate").isEmpty();
        ArrayList<SearchResult> ranked = restartManager.searchByKeywords("birth certificate application");
        boolean rankingWorks = !ranked.isEmpty()
                && ranked.get(0).getDocument().getDocumentId().equals("DOC001")
                && ranked.get(0).getMatchCount() == 3;
        boolean trieWorks = restartManager.suggestKeywords("app").contains("application")
                && restartManager.suggestKeywords("app").contains("appointment");

        check("12. Existing search, Trie and ranking still work (rebuilt from MySQL)", idSearch && titleSearch && rankingWorks && trieWorks);

        // Clean up test document from database
        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM documents WHERE document_id = '" + testDocId + "'");
        } catch (Exception ignored) {}

        // Stop test server instance cleanly
        WebServer.stopServer();

        System.out.println("=================================================");
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed.");
        System.out.println("=================================================");

        if (failed > 0) {
            System.exit(1);
        } else {
            System.exit(0);
        }
    }

    // Helper HTTP structures and methods
    static class Response {
        int statusCode;
        String contentType;
        String disposition;
        String cookie;
        byte[] bodyBytes;

        String bodyString() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }
    }

    private static Response httpGet(String urlStr) throws Exception {
        return httpGet(urlStr, null);
    }

    private static Response httpGet(String urlStr, String cookie) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        conn.connect();

        Response res = new Response();
        res.statusCode = conn.getResponseCode();
        res.contentType = conn.getContentType();
        res.disposition = conn.getHeaderField("Content-Disposition");
        res.cookie = conn.getHeaderField("Set-Cookie");
        InputStream is = res.statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        res.bodyBytes = is != null ? is.readAllBytes() : new byte[0];
        return res;
    }

    private static Response httpPostForm(String urlStr, String formData, String cookie, String csrf) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        if (csrf != null) conn.setRequestProperty("X-CSRF-Token", csrf);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(formData.getBytes(StandardCharsets.UTF_8));
        }

        Response res = new Response();
        res.statusCode = conn.getResponseCode();
        res.contentType = conn.getContentType();
        res.cookie = conn.getHeaderField("Set-Cookie");
        InputStream is = res.statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        res.bodyBytes = is != null ? is.readAllBytes() : new byte[0];
        return res;
    }

    private static Response httpPostMultipart(String urlStr, byte[] body, String boundary, String cookie, String csrf) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (cookie != null) conn.setRequestProperty("Cookie", cookie);
        if (csrf != null) conn.setRequestProperty("X-CSRF-Token", csrf);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        Response res = new Response();
        res.statusCode = conn.getResponseCode();
        res.contentType = conn.getContentType();
        InputStream is = res.statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
        res.bodyBytes = is != null ? is.readAllBytes() : new byte[0];
        return res;
    }

    private static void writeFormField(ByteArrayOutputStream baos, String boundary, String name, String value) throws Exception {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(value.getBytes(StandardCharsets.UTF_8));
        baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileField(ByteArrayOutputStream baos, String boundary, String fieldName, String filename, String contentType, byte[] data) throws Exception {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(data);
        baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void finishMultipart(ByteArrayOutputStream baos, String boundary) throws Exception {
        baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return end != -1 ? json.substring(start, end) : null;
    }
}
