import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Runs the same Java document system as a localhost website.
// No Node.js, database, or additional libraries are needed.
public class WebServer {
    private static DocumentManager manager = new DocumentManager();
    private static LoginManager logins;
    private static CorrectionRequestManager correctionManager = new CorrectionRequestManager();

    public static void main(String[] args) throws Exception {
        DocumentStorage.loadOrCreate(manager);
        logins = new LoginManager();
        CorrectionRequestStorage.load(correctionManager);
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", new ApiHandler());
        server.createContext("/", new WebsiteHandler());
        server.start();
        System.out.println("============================================");
        System.out.println(" ARAM OFFICE - E-GOVERNANCE WEBSITE");
        System.out.println("============================================");
        System.out.println("Website running at: http://127.0.0.1:" + port);
        System.out.println("Loaded documents : " + manager.getDocumentCount());
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static class ApiHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            try {
                // All requests are handled in order, so updates and search stay consistent.
                synchronized (manager) {
                    handleApi(exchange);
                }
            } catch (Exception error) {
                System.out.println("Website API error: " + error.getMessage());
                sendJson(exchange, 500, result(false, "An internal error occurred.", null));
            }
        }

        private void handleApi(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            HashMap<String, String> input;
            if (method.equals("GET")) {
                input = parseFields(ex.getRequestURI().getRawQuery());
            } else if (method.equals("POST")) {
                String type = ex.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
                    sendJson(ex, 415, result(false, "Form data is required.", null));
                    return;
                }
                byte[] bytes = ex.getRequestBody().readNBytes(65537);
                if (bytes.length > 65536) {
                    sendJson(ex, 413, result(false, "Submitted data is too large.", null));
                    return;
                }
                input = parseFields(new String(bytes, StandardCharsets.UTF_8));
            } else {
                sendJson(ex, 405, result(false, "Method not allowed.", null));
                return;
            }

            // Login does not require an existing session.
            if (path.equals("/api/login") && method.equals("POST")) {
                if (!sameOrigin(ex)) {
                    sendJson(ex, 403, result(false, "Invalid request origin.", null));
                    return;
                }
                LoginManager.LoginSession newSession = logins.login(
                    input.getOrDefault("username", "").trim(), input.getOrDefault("password", ""));
                if (newSession == null) {
                    sendJson(ex, 401, result(false, "Wrong username or password.", null));
                    return;
                }
                ex.getResponseHeaders().set("Set-Cookie", "aram_session=" + newSession.token
                    + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800");
                sendJson(ex, 200, result(true, "Login successful.", userToMap(newSession)));
                return;
            }
            LoginManager.LoginSession user = logins.getSession(ex);
            if (user == null) {
                sendJson(ex, 401, result(false, "Please log in first.", null));
                return;
            }
            if (method.equals("GET") && path.equals("/api/me")) {
                sendJson(ex, 200, result(true, "", userToMap(user)));
                return;
            }
            // Every POST after login needs the session's CSRF token.
            if (method.equals("POST")) {
                String token = ex.getRequestHeaders().getFirst("X-CSRF-Token");
                if (!sameOrigin(ex) || token == null || !token.equals(user.csrf)) {
                    sendJson(ex, 403, result(false, "Request security check failed.", null));
                    return;
                }
            }
            if (method.equals("POST") && path.equals("/api/logout")) {
                logins.logout(user);
                ex.getResponseHeaders().set("Set-Cookie",
                    "aram_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
                sendJson(ex, 200, result(true, "Logged out.", null));
                return;
            }
            // LOWER_OFFICIAL may submit requests, but not edit documents.
            if (method.equals("POST") && path.equals("/api/correction-request")) {
                if (!user.role.equals("LOWER_OFFICIAL")) {
                    sendJson(ex, 403, result(false, "Only Lower Official can raise a request.", null));
                    return;
                }
                String id = input.getOrDefault("documentId", "").trim();
                String reason = input.getOrDefault("reason", "").trim();
                String suggestion = input.getOrDefault("suggestion", "").trim();
                GovernmentDocument doc = manager.searchById(id);
                if (doc == null) {
                    sendJson(ex, 404, result(false, "Document ID not found.", null));
                    return;
                }
                if (reason.isEmpty() || suggestion.isEmpty() || reason.length() > 1000
                        || suggestion.length() > 2000) {
                    sendJson(ex, 400, result(false, "Enter a reason and suggestion within the length limit.", null));
                    return;
                }
                CorrectionRequest request = correctionManager.create(doc.getDocumentId(),
                        user.username, reason, suggestion);
                CorrectionRequestStorage.save(correctionManager);
                sendJson(ex, 201, result(true, "Correction request submitted.", request.toMap()));
                return;
            }
            if (method.equals("POST") && path.equals("/api/review-request")) {
                if (!user.role.equals("HIGHER_OFFICIAL")) {
                    sendJson(ex, 403, result(false, "Only Higher Official can review requests.", null));
                    return;
                }
                int id = parseNumber(input.get("requestId"));
                String decision = input.getOrDefault("decision", "").trim().toUpperCase();
                String remarks = input.getOrDefault("remarks", "").trim();
                if (id < 1 || (!decision.equals("APPROVED") && !decision.equals("REJECTED"))
                        || remarks.isEmpty() || remarks.length() > 1000) {
                    sendJson(ex, 400, result(false, "Enter valid request, decision and remarks.", null));
                    return;
                }
                if (!correctionManager.review(id, decision, remarks, user.username)) {
                    sendJson(ex, 409, result(false, "Request not found or already reviewed.", null));
                    return;
                }
                CorrectionRequestStorage.save(correctionManager);
                sendJson(ex, 200, result(true, "Request " + decision.toLowerCase() + ". Document unchanged.", null));
                return;
            }
            if (method.equals("GET") && path.equals("/api/correction-requests")) {
                ArrayList<Object> rows = new ArrayList<Object>();
                boolean higher = user.role.equals("HIGHER_OFFICIAL");
                for (CorrectionRequest request : correctionManager.requestsFor(user.username, higher)) {
                    rows.add(request.toMap());
                }
                sendJson(ex, 200, result(true, "", rows));
                return;
            }
            if (method.equals("GET") && path.equals("/api/activity-log")) {
                if (!user.role.equals("HIGHER_OFFICIAL")) {
                    sendJson(ex, 403, result(false, "Only Higher Official can view activity logs.", null));
                    return;
                }
                ArrayList<Object> rows = new ArrayList<Object>();
                for (ActivityLogEntry log : correctionManager.getActivities()) rows.add(log.toMap());
                sendJson(ex, 200, result(true, "", rows));
                return;
            }
            // Read-only users cannot change a document, even by calling the API directly.
            if (method.equals("POST") && !user.role.equals("HIGHER_OFFICIAL")) {
                sendJson(ex, 403, result(false, "Only Higher Official can modify documents.", null));
                return;
            }

            if (method.equals("GET") && path.equals("/api/stats")) {
                ArrayList<GovernmentDocument> all = manager.getAllDocuments();
                HashMap<String, Integer> departments = new HashMap<String, Integer>();
                HashMap<String, Integer> categories = new HashMap<String, Integer>();
                int versionCount = 0;
                for (GovernmentDocument doc : all) {
                    departments.put(doc.getDepartment(), departments.getOrDefault(doc.getDepartment(), 0) + 1);
                    categories.put(doc.getCategory(), categories.getOrDefault(doc.getCategory(), 0) + 1);
                    versionCount += doc.getAllVersions().size();
                }
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("documents", all.size());
                data.put("departments", departments.size());
                data.put("categories", categories.size());
                data.put("keywords", manager.getKeywordIndex().size());
                data.put("versions", versionCount);
                data.put("departmentCounts", departments);
                sendJson(ex, 200, result(true, "", data));
                return;
            }
            if (method.equals("GET") && path.equals("/api/documents")) {
                ArrayList<Object> docs = new ArrayList<Object>();
                for (GovernmentDocument doc : manager.getAllDocuments()) docs.add(documentToMap(doc));
                sendJson(ex, 200, result(true, "", docs));
                return;
            }
            if (method.equals("GET") && path.equals("/api/search")) {
                String type = input.getOrDefault("type", "keyword");
                String query = input.getOrDefault("q", "").trim();
                ArrayList<Object> matches = new ArrayList<Object>();
                if (!query.isEmpty()) {
                    if (type.equals("id")) {
                        GovernmentDocument doc = manager.searchById(query);
                        if (doc != null) matches.add(documentToMap(doc));
                    } else if (type.equals("title")) {
                        for (GovernmentDocument doc : manager.searchByTitle(query)) matches.add(documentToMap(doc));
                    } else if (type.equals("keyword")) {
                        for (SearchResult match : manager.searchByKeywords(query)) {
                            Map<String, Object> row = documentToMap(match.getDocument());
                            row.put("matches", match.getMatchCount());
                            matches.add(row);
                        }
                    } else {
                        sendJson(ex, 400, result(false, "Unknown search type.", null));
                        return;
                    }
                }
                sendJson(ex, 200, result(true, "", matches));
                return;
            }
            if (method.equals("GET") && path.equals("/api/suggest")) {
                sendJson(ex, 200, result(true, "", manager.suggestKeywords(input.getOrDefault("prefix", ""))));
                return;
            }
            if (method.equals("GET") && path.equals("/api/history")) {
                GovernmentDocument doc = manager.searchById(input.getOrDefault("id", ""));
                if (doc == null) {
                    sendJson(ex, 404, result(false, "Document not found.", null));
                    return;
                }
                ArrayList<Object> versions = new ArrayList<Object>();
                for (DocumentVersion version : doc.getAllVersions()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("number", version.getVersionNumber());
                    row.put("title", version.getTitle());
                    row.put("keywords", version.getKeywords());
                    row.put("content", version.getContent());
                    row.put("current", version.getVersionNumber() == doc.getCurrentVersionNumber());
                    versions.add(row);
                }
                sendJson(ex, 200, result(true, "", versions));
                return;
            }
            if (method.equals("GET") && path.equals("/api/index")) {
                HashMap<String, java.util.HashSet<String>> index = manager.getKeywordIndex();
                ArrayList<String> words = new ArrayList<String>(index.keySet());
                Collections.sort(words);
                ArrayList<Object> rows = new ArrayList<Object>();
                for (String word : words) {
                    ArrayList<String> ids = new ArrayList<String>(index.get(word));
                    Collections.sort(ids);
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("word", word);
                    row.put("ids", ids);
                    rows.add(row);
                }
                sendJson(ex, 200, result(true, "", rows));
                return;
            }
            if (method.equals("POST") && path.equals("/api/add")) {
                String id = input.getOrDefault("id", "").trim();
                String title = input.getOrDefault("title", "").trim();
                String department = input.getOrDefault("department", "").trim();
                String category = input.getOrDefault("category", "").trim();
                String keywords = input.getOrDefault("keywords", "").trim();
                String content = input.getOrDefault("content", "").trim();
                int importance = parseNumber(input.get("importance"));
                if (id.isEmpty() || title.isEmpty() || department.isEmpty() || category.isEmpty()
                        || content.isEmpty() || new SearchQuery(keywords).getWords().isEmpty()
                        || importance < 1 || importance > 5) {
                    sendJson(ex, 400, result(false, "Fill all fields and use importance 1 to 5 and valid keywords.", null));
                    return;
                }
                if (manager.searchById(id) != null) {
                    sendJson(ex, 409, result(false, "Document ID already exists.", null));
                    return;
                }
                GovernmentDocument doc = new GovernmentDocument(id, title, department, category,
                        importance, keywords, content);
                if (!manager.addDocument(doc)) {
                    sendJson(ex, 400, result(false, "Could not add document. Check the details.", null));
                    return;
                }
                DocumentStorage.save(manager);
                correctionManager.addActivity(user.username, "DOCUMENT_ADDED", doc.getDocumentId(), "Document registered");
                CorrectionRequestStorage.save(correctionManager);
                sendJson(ex, 201, result(true, "Document added and saved to local file.", documentToMap(doc)));
                return;
            }
            if (method.equals("POST") && path.equals("/api/add-version")) {
                String id = input.getOrDefault("id", "").trim();
                GovernmentDocument doc = manager.searchById(id);
                if (doc == null) {
                    sendJson(ex, 404, result(false, "Document not found.", null));
                    return;
                }
                boolean saved = manager.addNewVersion(id,
                        input.getOrDefault("title", ""),
                        input.getOrDefault("keywords", ""),
                        input.getOrDefault("content", ""));
                if (!saved) {
                    sendJson(ex, 400, result(false, "Title, valid keywords and content are required.", null));
                    return;
                }
                DocumentStorage.save(manager);
                correctionManager.addActivity(user.username, "VERSION_ADDED", doc.getDocumentId(),
                        "New current version: V" + doc.getCurrentVersionNumber());
                CorrectionRequestStorage.save(correctionManager);
                sendJson(ex, 200, result(true, "New version saved to file. Previous versions kept.", documentToMap(doc)));
                return;
            }
            if (method.equals("POST") && path.equals("/api/change-version")) {
                String id = input.getOrDefault("id", "").trim();
                if (manager.searchById(id) == null) {
                    sendJson(ex, 404, result(false, "Document not found.", null));
                    return;
                }
                boolean changed = manager.switchVersion(id, parseNumber(input.get("version")));
                if (!changed) {
                    sendJson(ex, 400, result(false, "Invalid version number.", null));
                    return;
                }
                DocumentStorage.save(manager);
                correctionManager.addActivity(user.username, "CURRENT_VERSION_CHANGED", id,
                        "Selected version: V" + manager.searchById(id).getCurrentVersionNumber());
                CorrectionRequestStorage.save(correctionManager);
                sendJson(ex, 200, result(true, "Current version changed, index updated and saved to file.",
                        documentToMap(manager.searchById(id))));
                return;
            }
            sendJson(ex, 404, result(false, "Unknown API route.", null));
        }
    }

    private static Map<String, Object> userToMap(LoginManager.LoginSession user) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("username", user.username);
        data.put("role", user.role);
        data.put("csrf", user.csrf);
        return data;
    }

    // Reject cross-site POST submissions to this localhost server.
    private static boolean sameOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true; // Non-browser clients must still pass CSRF after login.
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host != null && origin.equals("http://" + host);
    }

    private static Map<String, Object> documentToMap(GovernmentDocument doc) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", doc.getDocumentId());
        row.put("title", doc.getTitle());
        row.put("department", doc.getDepartment());
        row.put("category", doc.getCategory());
        row.put("importance", doc.getImportance());
        row.put("keywords", doc.getKeywords());
        row.put("content", doc.getContent());
        row.put("currentVersion", doc.getCurrentVersionNumber());
        row.put("versionCount", doc.getAllVersions().size());
        return row;
    }

    private static int parseNumber(String text) {
        try { return Integer.parseInt(text); }
        catch (Exception error) { return -1; }
    }

    // Read URL query or normal HTML form submissions.
    private static HashMap<String, String> parseFields(String raw) {
        HashMap<String, String> fields = new HashMap<String, String>();
        if (raw == null || raw.isEmpty()) return fields;
        for (String part : raw.split("&")) {
            String[] pieces = part.split("=", 2);
            String key = URLDecoder.decode(pieces[0], StandardCharsets.UTF_8);
            String value = pieces.length > 1 ? URLDecoder.decode(pieces[1], StandardCharsets.UTF_8) : "";
            fields.put(key, value);
        }
        return fields;
    }

    private static Map<String, Object> result(boolean success, String message, Object data) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("success", success);
        row.put("message", message);
        row.put("data", data);
        return row;
    }

    // Convert Java Maps and Lists into JSON without any external library.
    private static String json(Object object) {
        if (object == null) return "null";
        if (object instanceof Number || object instanceof Boolean) return object.toString();
        if (object instanceof String) {
            String text = (String) object;
            StringBuilder escaped = new StringBuilder("\"");
            for (char ch : text.toCharArray()) {
                if (ch == '"') escaped.append("\\\"");
                else if (ch == '\\') escaped.append("\\\\");
                else if (ch == '\n') escaped.append("\\n");
                else if (ch == '\r') escaped.append("\\r");
                else if (ch == '\t') escaped.append("\\t");
                else if (ch < 32) escaped.append(String.format("\\u%04x", (int) ch));
                else escaped.append(ch);
            }
            return escaped.append('"').toString();
        }
        if (object instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Object key : ((Map<?, ?>) object).keySet()) {
                if (out.length() > 1) out.append(',');
                out.append(json(key.toString())).append(':').append(json(((Map<?, ?>) object).get(key)));
            }
            return out.append('}').toString();
        }
        if (object instanceof List) {
            StringBuilder out = new StringBuilder("[");
            for (Object value : (List<?>) object) {
                if (out.length() > 1) out.append(',');
                out.append(json(value));
            }
            return out.append(']').toString();
        }
        return json(object.toString());
    }

    private static void sendJson(HttpExchange ex, int status, Map<String, Object> data) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json(data).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, body.length);
        try (java.io.OutputStream stream = ex.getResponseBody()) { stream.write(body); }
    }

    private static class WebsiteHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("GET")) {
                send(exchange, 405, "text/plain", "Only GET is allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            if (requestPath.equals("/")) requestPath = "/index.html";
            if (!requestPath.equals("/index.html") && !requestPath.equals("/style.css")
                    && !requestPath.equals("/app.js") && !requestPath.equals("/favicon.svg")) {
                send(exchange, 404, "text/plain", "Page not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Path file = Paths.get("web", requestPath.substring(1));
            if (!Files.exists(file)) {
                send(exchange, 404, "text/plain", "Website file not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String type = requestPath.endsWith(".html") ? "text/html; charset=utf-8"
                    : requestPath.endsWith(".css") ? "text/css; charset=utf-8"
                    : requestPath.endsWith(".js") ? "text/javascript; charset=utf-8" : "image/svg+xml";
            send(exchange, 200, type, Files.readAllBytes(file));
        }
    }
}
