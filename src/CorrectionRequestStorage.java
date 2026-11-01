import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;

// Keeps requests and logs in MySQL database as source of truth,
// with text file backup.
public class CorrectionRequestStorage {
    private static final Path FILE = Paths.get("data", "correction_workflow.txt");
    private static final String HEADER = "ARAM_CORRECTIONS_V1";

    public static void load(CorrectionRequestManager manager) throws IOException {
        DatabaseConnection.initDatabase();

        boolean loadedFromDb = false;
        try (Connection conn = DatabaseConnection.getConnection()) {
            int requestCount = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM correction_requests")) {
                if (rs.next()) {
                    requestCount = rs.getInt(1);
                }
            }

            if (requestCount == 0 && Files.exists(FILE)) {
                System.out.println("Migrating correction workflow and activity logs to MySQL...");
                migrateFromTextFile(conn, manager);
            }

            loadedFromDb = loadFromDatabase(conn, manager);
        } catch (SQLException e) {
            System.out.println("MySQL correction request loading warning: " + e.getMessage() + ". Using text fallback.");
        }

        if (!loadedFromDb) {
            loadFromTextFile(manager);
        }
    }

    private static void migrateFromTextFile(Connection conn, CorrectionRequestManager manager) throws IOException, SQLException {
        loadFromTextFile(manager);

        // Migrate loaded requests to MySQL
        String reqSql = "INSERT IGNORE INTO correction_requests (id, document_id, requested_by, reason, suggestion, created_at, status, reviewed_by, remarks, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(reqSql)) {
            for (CorrectionRequest req : manager.requestsFor("", true)) {
                ps.setInt(1, req.id);
                ps.setString(2, req.documentId);
                ps.setString(3, req.requestedBy);
                ps.setString(4, req.reason);
                ps.setString(5, req.suggestion);
                ps.setString(6, req.createdAt);
                ps.setString(7, req.status);
                ps.setString(8, req.reviewedBy);
                ps.setString(9, req.remarks);
                ps.setString(10, req.reviewedAt);
                ps.executeUpdate();
            }
        }

        // Migrate activity logs to MySQL
        String logSql = "INSERT INTO activity_logs (date_time, username, action, reference, details) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(logSql)) {
            for (ActivityLogEntry log : manager.getActivities()) {
                ps.setString(1, log.dateTime);
                ps.setString(2, log.username);
                ps.setString(3, log.action);
                ps.setString(4, log.reference);
                ps.setString(5, log.details);
                ps.executeUpdate();
            }
        }
        System.out.println("Migrated correction requests and activity logs to MySQL successfully.");
    }

    private static boolean loadFromDatabase(Connection conn, CorrectionRequestManager manager) throws SQLException {
        String queryReq = "SELECT id, document_id, requested_by, reason, suggestion, created_at, status, reviewed_by, remarks, reviewed_at FROM correction_requests ORDER BY id ASC";
        int reqLoaded = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(queryReq)) {
            while (rs.next()) {
                CorrectionRequest req = new CorrectionRequest(
                        rs.getInt("id"),
                        rs.getString("document_id"),
                        rs.getString("requested_by"),
                        rs.getString("reason"),
                        rs.getString("suggestion"),
                        rs.getString("created_at")
                );
                req.status = rs.getString("status");
                req.reviewedBy = rs.getString("reviewed_by");
                req.remarks = rs.getString("remarks");
                req.reviewedAt = rs.getString("reviewed_at");
                manager.loadRequest(req);
                reqLoaded++;
            }
        }

        String queryLog = "SELECT date_time, username, action, reference, details FROM activity_logs ORDER BY id ASC";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(queryLog)) {
            while (rs.next()) {
                manager.loadActivity(new ActivityLogEntry(
                        rs.getString("date_time"),
                        rs.getString("username"),
                        rs.getString("action"),
                        rs.getString("reference"),
                        rs.getString("details")
                ));
            }
        }

        System.out.println("Loaded " + reqLoaded + " correction requests and " + manager.getActivities().size() + " activity logs from MySQL.");
        return true;
    }

    public static void save(CorrectionRequestManager manager) throws IOException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            // Save or update requests
            String sqlReq = "INSERT INTO correction_requests (id, document_id, requested_by, reason, suggestion, created_at, status, reviewed_by, remarks, reviewed_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE status = VALUES(status), reviewed_by = VALUES(reviewed_by), remarks = VALUES(remarks), reviewed_at = VALUES(reviewed_at)";
            try (PreparedStatement ps = conn.prepareStatement(sqlReq)) {
                for (CorrectionRequest req : manager.requestsFor("", true)) {
                    ps.setInt(1, req.id);
                    ps.setString(2, req.documentId);
                    ps.setString(3, req.requestedBy);
                    ps.setString(4, req.reason);
                    ps.setString(5, req.suggestion);
                    ps.setString(6, req.createdAt);
                    ps.setString(7, req.status);
                    ps.setString(8, req.reviewedBy);
                    ps.setString(9, req.remarks);
                    ps.setString(10, req.reviewedAt);
                    ps.executeUpdate();
                }
            }

            // Sync latest activity logs: insert only new entries if needed
            // For simple consistency, clear and reload or insert new
            // Since activity log entries are append-only:
            int currentDbLogs = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM activity_logs")) {
                if (rs.next()) currentDbLogs = rs.getInt(1);
            }
            ArrayList<ActivityLogEntry> list = manager.getActivities();
            if (list.size() > currentDbLogs) {
                String logSql = "INSERT INTO activity_logs (date_time, username, action, reference, details) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(logSql)) {
                    for (int i = currentDbLogs; i < list.size(); i++) {
                        ActivityLogEntry log = list.get(i);
                        ps.setString(1, log.dateTime);
                        ps.setString(2, log.username);
                        ps.setString(3, log.action);
                        ps.setString(4, log.reference);
                        ps.setString(5, log.details);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Warning: Could not save correction workflow to MySQL: " + e.getMessage());
        }

        saveToTextFile(manager);
    }

    private static void loadFromTextFile(CorrectionRequestManager manager) throws IOException {
        if (!Files.exists(FILE)) {
            saveToTextFile(manager);
            return;
        }
        ArrayList<String> lines = new ArrayList<String>(Files.readAllLines(FILE, StandardCharsets.UTF_8));
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) {
            throw new IOException("Invalid correction_workflow.txt; do not delete the saved data.");
        }
        try {
            for (int i = 1; i < lines.size(); i++) {
                String[] items = lines.get(i).split("\\t", -1);
                if (items.length == 11 && items[0].equals("R")) {
                    CorrectionRequest request = new CorrectionRequest(
                            Integer.parseInt(items[1]), read(items[2]), read(items[3]),
                            read(items[4]), read(items[5]), read(items[6]));
                    request.status = read(items[7]);
                    request.reviewedBy = read(items[8]);
                    request.remarks = read(items[9]);
                    request.reviewedAt = read(items[10]);
                    if (!request.status.equals("PENDING") && !request.status.equals("APPROVED")
                            && !request.status.equals("REJECTED")) {
                        throw new IllegalArgumentException("Invalid request status");
                    }
                    manager.loadRequest(request);
                } else if (items.length == 6 && items[0].equals("L")) {
                    manager.loadActivity(new ActivityLogEntry(read(items[1]), read(items[2]),
                            read(items[3]), read(items[4]), read(items[5])));
                } else {
                    throw new IllegalArgumentException("Invalid saved row");
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("Correction file could not be read. Restore .bak if needed.", error);
        }
    }

    private static void saveToTextFile(CorrectionRequestManager manager) throws IOException {
        Files.createDirectories(FILE.getParent());
        ArrayList<String> lines = new ArrayList<String>();
        lines.add(HEADER);
        for (CorrectionRequest request : manager.requestsFor("", true)) {
            lines.add("R\t" + request.id + "\t" + write(request.documentId) + "\t"
                    + write(request.requestedBy) + "\t" + write(request.reason) + "\t"
                    + write(request.suggestion) + "\t" + write(request.createdAt) + "\t"
                    + write(request.status) + "\t" + write(request.reviewedBy) + "\t"
                    + write(request.remarks) + "\t" + write(request.reviewedAt));
        }
        for (ActivityLogEntry log : manager.getActivities()) {
            lines.add("L\t" + write(log.dateTime) + "\t" + write(log.username) + "\t"
                    + write(log.action) + "\t" + write(log.reference) + "\t"
                    + write(log.details));
        }
        Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        if (Files.exists(FILE)) {
            Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String write(String text) {
        if (text == null) return "";
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
