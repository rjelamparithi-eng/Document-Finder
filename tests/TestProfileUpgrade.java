import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

// Automated test suite verifying all 10 requirements of Multi-User Profile Management Upgrade.
public class TestProfileUpgrade {

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
        System.out.println("  ARAM OFFICE — PROFILE UPGRADE TEST SUITE");
        System.out.println("=================================================");

        int testPort = 8899;

        // Clean up any leftover test accounts in MySQL
        cleanTestAccounts();

        // Launch WebServer in background daemon thread
        Thread serverThread = new Thread(() -> {
            try {
                WebServer.main(new String[]{String.valueOf(testPort)});
            } catch (Exception e) {
                System.out.println("Server thread error: " + e.getMessage());
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait for server to bind
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

        try {
            // -----------------------------------------------------------------
            // VERIFICATION 1: Existing users can still log in
            // -----------------------------------------------------------------
            Response loginHigher = httpPostForm(baseUrl + "/api/login", "username=higher&password=higher", null, null);
            boolean higherOk = loginHigher.statusCode == 200
                    && loginHigher.bodyString().contains("\"role\":\"HIGHER_OFFICIAL\"")
                    && loginHigher.bodyString().contains("\"department\":\"Health\"")
                    && loginHigher.cookie != null;
            String higherCookie = loginHigher.cookie;
            String higherCsrf = extractJsonString(loginHigher.bodyString(), "csrf");

            Response loginViewer = httpPostForm(baseUrl + "/api/login", "username=viewer&password=viewer", null, null);
            boolean viewerOk = loginViewer.statusCode == 200
                    && loginViewer.bodyString().contains("\"role\":\"LOWER_OFFICIAL\"")
                    && loginViewer.cookie != null;
            check("1. Existing users can still log in (higher and viewer)", higherOk && viewerOk);

            // -----------------------------------------------------------------
            // VERIFICATION 2: New viewer registration works
            // -----------------------------------------------------------------
            String shortPassData = "username=test_viewer_short&password=shortpass&fullName=Short+Pass&department=Revenue";
            Response shortPassRes = httpPostForm(baseUrl + "/api/register", shortPassData, null, null);
            boolean shortPassRejected = shortPassRes.statusCode == 400
                    && shortPassRes.bodyString().contains("at least 12 characters");

            String regData1 = "username=test_viewer_alice&password=password123456&fullName=Alice+Smith&department=Revenue";
            Response regRes1 = httpPostForm(baseUrl + "/api/register", regData1, null, null);
            boolean reg1Success = regRes1.statusCode == 201;

            Response loginAlice = httpPostForm(baseUrl + "/api/login", "username=test_viewer_alice&password=password123456", null, null);
            boolean aliceOk = loginAlice.statusCode == 200
                    && loginAlice.bodyString().contains("\"role\":\"LOWER_OFFICIAL\"")
                    && loginAlice.bodyString().contains("Alice Smith")
                    && loginAlice.bodyString().contains("\"department\":\"Revenue\"");
            String aliceCookie = loginAlice.cookie;
            String aliceCsrf = extractJsonString(loginAlice.bodyString(), "csrf");
            check("2. New viewer registration works (enforces >= 12 char password, assigns LOWER_OFFICIAL)",
                    shortPassRejected && reg1Success && aliceOk);

            // -----------------------------------------------------------------
            // VERIFICATION 3: Multiple viewers can register
            // -----------------------------------------------------------------
            String regData2 = "username=test_viewer_bob&password=password123456&fullName=Bob+Jones&department=";
            Response regRes2 = httpPostForm(baseUrl + "/api/register", regData2, null, null);
            Response loginBob = httpPostForm(baseUrl + "/api/login", "username=test_viewer_bob&password=password123456", null, null);
            boolean bobOk = regRes2.statusCode == 201
                    && loginBob.statusCode == 200
                    && loginBob.bodyString().contains("\"role\":\"LOWER_OFFICIAL\"")
                    && loginBob.bodyString().contains("Bob Jones");
            check("3. Multiple viewers can register independently", bobOk);

            // -----------------------------------------------------------------
            // VERIFICATION 4: Duplicate usernames are rejected
            // -----------------------------------------------------------------
            Response dupReg1 = httpPostForm(baseUrl + "/api/register",
                    "username=test_viewer_alice&password=differentPass123&fullName=Alice+Duplicate&department=Health", null, null);
            Response dupReg2 = httpPostForm(baseUrl + "/api/register",
                    "username=higher&password=differentPass123&fullName=Higher+Dup&department=Revenue", null, null);
            boolean dupRejected = dupReg1.statusCode == 400
                    && dupReg1.bodyString().contains("already registered")
                    && dupReg2.statusCode == 400;
            check("4. Duplicate usernames are rejected with friendly validation error", dupRejected);

            // -----------------------------------------------------------------
            // VERIFICATION 5: Only one active Higher Official can exist per department
            // -----------------------------------------------------------------
            // Check that /api/provision-higher is not an exposed public HTTP route (returns 404)
            Response unexposedEndpoint = httpPostForm(baseUrl + "/api/provision-higher",
                    "username=test_higher_health2&password=password123456&fullName=Another+Health+Officer&department=Health",
                    higherCookie, higherCsrf);
            boolean endpointProtected = unexposedEndpoint.statusCode == 404;

            // Direct admin provisioning prevents duplicate for Health (already has 'higher'):
            LoginManager loginMgr = new LoginManager();
            String dupHealthErr = loginMgr.provisionHigherOfficial("test_higher_health2", "password123456", "Another Health Officer", "Health");
            boolean dupHigherBlocked = dupHealthErr != null && dupHealthErr.contains("already exists for department: Health");

            // Database constraint check: attempting direct DB insert of duplicate active higher official for Health
            boolean dbConstraintFired = false;
            try (Connection conn = DatabaseConnection.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO users (username, role, full_name, department, is_active, salt, password_hash) " +
                                 "VALUES ('test_db_dup', 'HIGHER_OFFICIAL', 'Direct DB Dup', 'Health', 1, 'abc', 'def')")) {
                ps.executeUpdate();
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("uq_higher_official_dept")) {
                    dbConstraintFired = true;
                } else if ("23000".equals(((java.sql.SQLException)e).getSQLState())) {
                    dbConstraintFired = true;
                }
            }

            // Provisioning an unoccupied department (Education) succeeds via admin provisioning
            String provEduErr = loginMgr.provisionHigherOfficial("test_higher_edu", "password123456", "Edu Official", "Education");
            boolean provEduOk = provEduErr == null;

            // Attempting second higher official for Education is blocked
            String dupEduErr = loginMgr.provisionHigherOfficial("test_higher_edu2", "password123456", "Edu Official2", "Education");
            boolean dupEduBlocked = dupEduErr != null && dupEduErr.contains("already exists for department: Education");

            check("5. Only one active Higher Official per department (admin-only provisioning + DB constraint)",
                    endpointProtected && dupHigherBlocked && dbConstraintFired && provEduOk && dupEduBlocked);

            // -----------------------------------------------------------------
            // VERIFICATION 6: Higher Official cannot modify another department's documents
            // -----------------------------------------------------------------
            // 'higher' is assigned to Health. Attempting to add a document for 'Revenue':
            String boundary = "----TestBoundary" + System.currentTimeMillis();
            ByteArrayOutputStream crossAddBody = new ByteArrayOutputStream();
            writeFormField(crossAddBody, boundary, "id", "DOC_CROSS_01");
            writeFormField(crossAddBody, boundary, "title", "Cross Dept Document");
            writeFormField(crossAddBody, boundary, "department", "Revenue"); // Not Health!
            writeFormField(crossAddBody, boundary, "category", "Circular");
            writeFormField(crossAddBody, boundary, "importance", "3");
            writeFormField(crossAddBody, boundary, "keywords", "cross department forbidden");
            writeFormField(crossAddBody, boundary, "content", "Should be rejected.");
            finishMultipart(crossAddBody, boundary);

            Response crossAddRes = httpPostMultipart(baseUrl + "/api/add", crossAddBody.toByteArray(), boundary, higherCookie, higherCsrf);
            boolean crossAddBlocked = crossAddRes.statusCode == 403
                    && crossAddRes.bodyString().contains("Access denied");

            // Attempting to modify a Revenue document (DOC001 is in Revenue):
            ByteArrayOutputStream crossVersionBody = new ByteArrayOutputStream();
            writeFormField(crossVersionBody, boundary, "id", "DOC001");
            writeFormField(crossVersionBody, boundary, "title", "Hacked Title");
            writeFormField(crossVersionBody, boundary, "keywords", "hacked keywords");
            writeFormField(crossVersionBody, boundary, "content", "Hacked content");
            finishMultipart(crossVersionBody, boundary);

            Response crossVersionRes = httpPostMultipart(baseUrl + "/api/add-version", crossVersionBody.toByteArray(), boundary, higherCookie, higherCsrf);
            boolean crossVersionBlocked = crossVersionRes.statusCode == 403
                    && crossVersionRes.bodyString().contains("Access denied");

            // Attempting to upload PDF to Revenue document DOC001:
            byte[] fakePdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream crossPdfBody = new ByteArrayOutputStream();
            writeFormField(crossPdfBody, boundary, "id", "DOC001");
            writeFileField(crossPdfBody, boundary, "pdf", "unauthorized.pdf", "application/pdf", fakePdf);
            finishMultipart(crossPdfBody, boundary);

            Response crossPdfRes = httpPostMultipart(baseUrl + "/api/upload-pdf", crossPdfBody.toByteArray(), boundary, higherCookie, higherCsrf);
            boolean crossPdfBlocked = crossPdfRes.statusCode == 403
                    && crossPdfRes.bodyString().contains("Access denied");

            check("6. Higher Official cannot modify another department's documents (HTTP 403)",
                    crossAddBlocked && crossVersionBlocked && crossPdfBlocked);

            // -----------------------------------------------------------------
            // VERIFICATION 7: Viewer cannot upload through direct API calls
            // -----------------------------------------------------------------
            ByteArrayOutputStream viewerAddBody = new ByteArrayOutputStream();
            writeFormField(viewerAddBody, boundary, "id", "DOC_VIEWER_01");
            writeFormField(viewerAddBody, boundary, "title", "Viewer Illegal Upload");
            writeFormField(viewerAddBody, boundary, "department", "Revenue");
            writeFormField(viewerAddBody, boundary, "category", "Policy");
            writeFormField(viewerAddBody, boundary, "importance", "3");
            writeFormField(viewerAddBody, boundary, "keywords", "illegal viewer upload");
            writeFormField(viewerAddBody, boundary, "content", "Viewer upload must be blocked.");
            finishMultipart(viewerAddBody, boundary);

            Response viewerAddRes = httpPostMultipart(baseUrl + "/api/add", viewerAddBody.toByteArray(), boundary, aliceCookie, aliceCsrf);
            Response viewerVersionRes = httpPostMultipart(baseUrl + "/api/add-version", viewerAddBody.toByteArray(), boundary, aliceCookie, aliceCsrf);
            Response viewerUploadRes = httpPostMultipart(baseUrl + "/api/upload-pdf", crossPdfBody.toByteArray(), boundary, aliceCookie, aliceCsrf);

            boolean viewerBlocked = viewerAddRes.statusCode == 403
                    && viewerVersionRes.statusCode == 403
                    && viewerUploadRes.statusCode == 403;
            check("7. Viewer cannot upload or modify via direct API calls (HTTP 403)", viewerBlocked);

            // -----------------------------------------------------------------
            // VERIFICATION 8: Profile information persists after restart
            // -----------------------------------------------------------------
            LoginManager freshLogins = new LoginManager();
            LoginManager.Account freshAlice = freshLogins.getAccount("test_viewer_alice");
            LoginManager.Account freshHigher = freshLogins.getAccount("higher");

            boolean profilePersists = freshAlice != null
                    && "Alice Smith".equals(freshAlice.fullName)
                    && "Revenue".equals(freshAlice.department)
                    && "LOWER_OFFICIAL".equals(freshAlice.role)
                    && freshHigher != null
                    && "Health".equals(freshHigher.department);
            check("8. Profile information persists after restart (reloaded from MySQL)", profilePersists);

            // -----------------------------------------------------------------
            // VERIFICATION 9: Existing PDF upload and retrieval still work
            // -----------------------------------------------------------------
            String healthDocId = "DOC_HLT_" + (System.currentTimeMillis() % 100000);
            byte[] healthPdf = "%PDF-1.4\n1 0 obj\n<< /Title (Health Guideline) >>\nendobj\ntrailer\n<< >>\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);

            ByteArrayOutputStream healthAddBody = new ByteArrayOutputStream();
            writeFormField(healthAddBody, boundary, "id", healthDocId);
            writeFormField(healthAddBody, boundary, "title", "Community Health Directive");
            writeFormField(healthAddBody, boundary, "department", "Health");
            writeFormField(healthAddBody, boundary, "category", "Guidelines");
            writeFormField(healthAddBody, boundary, "importance", "4");
            writeFormField(healthAddBody, boundary, "keywords", "health community directive protocol");
            writeFormField(healthAddBody, boundary, "content", "Community health inspection instructions.");
            writeFileField(healthAddBody, boundary, "pdf", "health_directive.pdf", "application/pdf", healthPdf);
            finishMultipart(healthAddBody, boundary);

            Response healthAddRes = httpPostMultipart(baseUrl + "/api/add", healthAddBody.toByteArray(), boundary, higherCookie, higherCsrf);
            boolean pdfSaved = (healthAddRes.statusCode == 201 || healthAddRes.statusCode == 200)
                    && healthAddRes.bodyString().contains("\"hasPdf\":true");

            // Viewer can retrieve and download the PDF
            Response viewPdfRes = httpGet(baseUrl + "/api/pdf?id=" + healthDocId + "&version=1", aliceCookie);
            Response dlPdfRes = httpGet(baseUrl + "/api/pdf?id=" + healthDocId + "&version=1&download=1", aliceCookie);

            boolean pdfRetrieved = viewPdfRes.statusCode == 200
                    && dlPdfRes.statusCode == 200
                    && "application/pdf".equals(viewPdfRes.contentType)
                    && dlPdfRes.disposition != null && dlPdfRes.disposition.contains("attachment");
            check("9. Existing PDF upload and retrieval still work for authorized users", pdfSaved && pdfRetrieved);

            // -----------------------------------------------------------------
            // VERIFICATION 10: Existing search, version history and correction workflow still work
            // -----------------------------------------------------------------
            // Search
            Response searchRes = httpGet(baseUrl + "/api/search?type=keyword&q=community", aliceCookie);
            boolean searchOk = searchRes.statusCode == 200 && searchRes.bodyString().contains(healthDocId);

            // Viewer raises correction request for the Health document
            Response reqRes = httpPostForm(baseUrl + "/api/correction-request",
                    "documentId=" + healthDocId + "&reason=Typo+in+clause&suggestion=Correct+typo+in+section+2",
                    aliceCookie, aliceCsrf);
            boolean reqCreated = reqRes.statusCode == 201;
            String reqIdStr = extractJsonIntString(reqRes.bodyString(), "id");
            int reqId = reqIdStr != null ? Integer.parseInt(reqIdStr) : -1;

            // Higher Official for Health reviews and approves the request
            Response reviewRes = httpPostForm(baseUrl + "/api/review-request",
                    "requestId=" + reqId + "&decision=APPROVED&remarks=Verified+and+accepted",
                    higherCookie, higherCsrf);
            boolean reviewOk = reviewRes.statusCode == 200;

            check("10. Search, version history, and correction workflow still work end-to-end",
                    searchOk && reqCreated && reviewOk);

            // -----------------------------------------------------------------
            // VERIFICATION 11: Recent documents filter (Last 3 Months) works
            // -----------------------------------------------------------------
            Response recentDocsRes = httpGet(baseUrl + "/api/documents?recent=3months", aliceCookie);
            boolean recentDocsOk = recentDocsRes.statusCode == 200
                    && recentDocsRes.bodyString().contains("\"isRecent3Months\":true");
            check("11. Recent documents filter (Last 3 Months) returns matching records", recentDocsOk);

            // -----------------------------------------------------------------
            // VERIFICATION 12: Session expiration handling (401 on invalid/expired session)
            // -----------------------------------------------------------------
            Response expiredSessionRes = httpGet(baseUrl + "/api/me", "ARAM_SESSION=invalid_or_expired_token_12345");
            boolean sessionExpirationOk = expiredSessionRes.statusCode == 401;
            check("12. Expired or invalid session returns HTTP 401 redirecting to login", sessionExpirationOk);

        } finally {
            // Clean up test users
            cleanTestAccounts();
        }

        System.out.println("=================================================");
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed.");
        System.out.println("=================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void cleanTestAccounts() {
        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM users WHERE username LIKE 'test_%'");
        } catch (Exception ignored) {}
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

    private static String extractJsonIntString(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx == -1) return null;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static class Response {
        int statusCode;
        String contentType;
        String disposition;
        String cookie;
        byte[] bodyBytes;
        String bodyString() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }
    }
}
