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

// Manages document persistence using MySQL database as source of truth,
// with text file backup for safety.
public class DocumentStorage {

    private static final Path FILE = Paths.get("data", "saved_documents.txt");
    private static final String HEADER = "ARAM_OFFICE_V1";

    public static class PdfRecord {
        public final String fileName;
        public final byte[] data;
        public final int size;

        public PdfRecord(String fileName, byte[] data, int size) {
            this.fileName = fileName;
            this.data = data;
            this.size = size;
        }
    }

    public static void loadOrCreate(DocumentManager manager) throws IOException {
        DatabaseConnection.initDatabase();

        boolean loadedFromDb = false;
        try (Connection conn = DatabaseConnection.getConnection()) {
            // Check if documents table has records
            int docCount = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM documents")) {
                if (rs.next()) {
                    docCount = rs.getInt(1);
                }
            }

            if (docCount == 0) {
                // If MySQL is empty, migrate existing text file or sample data
                System.out.println("MySQL database is empty. Performing initial migration...");
                migrateToDatabase(conn, manager);
            }

            // Load persistent records from MySQL
            loadedFromDb = loadFromDatabase(conn, manager);
        } catch (SQLException e) {
            System.out.println("MySQL load warning: " + e.getMessage() + ". Attempting text backup fallback...");
        }

        if (!loadedFromDb) {
            loadFromTextFile(manager);
        }
    }

    private static void migrateToDatabase(Connection conn, DocumentManager manager) throws IOException, SQLException {
        // First load from text file into memory if it exists, else load samples
        if (Files.exists(FILE)) {
            loadFromTextFile(manager);
        } else {
            SampleData.loadSampleDocuments(manager);
            saveToTextFile(manager);
        }

        // Now save all in-memory documents to MySQL
        conn.setAutoCommit(false);
        try {
            for (GovernmentDocument doc : manager.getAllDocuments()) {
                insertDocumentIntoDb(conn, doc);
            }
            conn.commit();
            System.out.println("Successfully migrated " + manager.getDocumentCount() + " documents into MySQL.");
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static void insertDocumentIntoDb(Connection conn, GovernmentDocument doc) throws SQLException {
        // Check if already exists to prevent duplicate migration
        try (PreparedStatement check = conn.prepareStatement("SELECT document_id FROM documents WHERE document_id = ?")) {
            check.setString(1, doc.getDocumentId());
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    return; // Already exists
                }
            }
        }

        String insertDocSql = "INSERT INTO documents (document_id, department, category, importance, current_version) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertDocSql)) {
            ps.setString(1, doc.getDocumentId());
            ps.setString(2, doc.getDepartment());
            ps.setString(3, doc.getCategory());
            ps.setInt(4, doc.getImportance());
            ps.setInt(5, doc.getCurrentVersionNumber());
            ps.executeUpdate();
        }

        String insertVerSql = "INSERT INTO document_versions (document_id, version_number, title, keywords, content, pdf_filename, pdf_data, pdf_size) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertVerSql)) {
            for (DocumentVersion ver : doc.getAllVersions()) {
                ps.setString(1, doc.getDocumentId());
                ps.setInt(2, ver.getVersionNumber());
                ps.setString(3, ver.getTitle());
                ps.setString(4, ver.getKeywords());
                ps.setString(5, ver.getContent());
                ps.setString(6, ver.getPdfFilename());
                ps.setBytes(7, null);
                ps.setInt(8, ver.getPdfSize());
                ps.executeUpdate();
            }
        }

        updateKeywordsInDb(conn, doc.getDocumentId(), doc.getKeywords());
    }

    private static void updateKeywordsInDb(Connection conn, String docId, String keywordsText) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM keywords WHERE document_id = ?")) {
            del.setString(1, docId);
            del.executeUpdate();
        }

        SearchQuery query = new SearchQuery(keywordsText);
        String insertKwSql = "INSERT IGNORE INTO keywords (keyword, document_id) VALUES (?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertKwSql)) {
            for (String word : query.getWords()) {
                ps.setString(1, word);
                ps.setString(2, docId);
                ps.executeUpdate();
            }
        }
    }

    private static boolean loadFromDatabase(Connection conn, DocumentManager manager) throws SQLException {
        String queryDocs = "SELECT document_id, department, category, importance, current_version, created_at FROM documents ORDER BY document_id";
        String queryVers = "SELECT version_number, title, keywords, content, pdf_filename, pdf_size FROM document_versions WHERE document_id = ? ORDER BY version_number ASC";

        ArrayList<GovernmentDocument> loadedList = new ArrayList<GovernmentDocument>();

        try (PreparedStatement psDocs = conn.prepareStatement(queryDocs);
             ResultSet rsDocs = psDocs.executeQuery()) {

            while (rsDocs.next()) {
                String id = rsDocs.getString("document_id");
                String dept = rsDocs.getString("department");
                String cat = rsDocs.getString("category");
                int importance = rsDocs.getInt("importance");
                int currentVer = rsDocs.getInt("current_version");
                java.sql.Timestamp createdAtTs = rsDocs.getTimestamp("created_at");

                GovernmentDocument doc = null;

                try (PreparedStatement psVers = conn.prepareStatement(queryVers)) {
                    psVers.setString(1, id);
                    try (ResultSet rsVers = psVers.executeQuery()) {
                        while (rsVers.next()) {
                            String title = rsVers.getString("title");
                            String keywords = rsVers.getString("keywords");
                            String content = rsVers.getString("content");
                            String pdfFilename = rsVers.getString("pdf_filename");
                            int pdfSize = rsVers.getInt("pdf_size");

                            if (doc == null) {
                                doc = new GovernmentDocument(id, title, dept, cat, importance, keywords, content, pdfFilename, pdfSize);
                            } else {
                                doc.addVersion(title, keywords, content, pdfFilename, pdfSize);
                            }
                        }
                    }
                }

                if (doc != null) {
                    doc.setCurrentVersion(currentVer);
                    if (createdAtTs != null) {
                        doc.setCreatedAt(createdAtTs.toString());
                    }
                    loadedList.add(doc);
                }
            }
        }

        if (loadedList.isEmpty()) {
            return false;
        }

        for (GovernmentDocument doc : loadedList) {
            manager.addDocument(doc);
        }

        System.out.println("Loaded " + manager.getDocumentCount() + " documents from MySQL database.");
        return true;
    }

    public static void save(DocumentManager manager) throws IOException {
        // Save to MySQL first (persistent source of truth)
        try (Connection conn = DatabaseConnection.getConnection()) {
            for (GovernmentDocument doc : manager.getAllDocuments()) {
                // Ensure document row exists
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO documents (document_id, department, category, importance, current_version) "
                                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE current_version = VALUES(current_version)")) {
                    ps.setString(1, doc.getDocumentId());
                    ps.setString(2, doc.getDepartment());
                    ps.setString(3, doc.getCategory());
                    ps.setInt(4, doc.getImportance());
                    ps.setInt(5, doc.getCurrentVersionNumber());
                    ps.executeUpdate();
                }

                // Update keywords for current version
                updateKeywordsInDb(conn, doc.getDocumentId(), doc.getKeywords());
            }
        } catch (SQLException e) {
            System.out.println("Warning: Could not save to MySQL: " + e.getMessage());
        }

        // Keep local backup file synchronized
        saveToTextFile(manager);
    }

    // Saves a new document with an optional uploaded PDF
    public static void saveNewDocument(GovernmentDocument doc, String pdfFilename, byte[] pdfData) throws SQLException, IOException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Insert documents row
                String sqlDoc = "INSERT INTO documents (document_id, department, category, importance, current_version) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sqlDoc)) {
                    ps.setString(1, doc.getDocumentId());
                    ps.setString(2, doc.getDepartment());
                    ps.setString(3, doc.getCategory());
                    ps.setInt(4, doc.getImportance());
                    ps.setInt(5, doc.getCurrentVersionNumber());
                    ps.executeUpdate();
                }

                // 2. Insert document_versions row
                String sqlVer = "INSERT INTO document_versions (document_id, version_number, title, keywords, content, pdf_filename, pdf_data, pdf_size, pdf_uploaded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sqlVer)) {
                    DocumentVersion ver = doc.getCurrentVersion();
                    ps.setString(1, doc.getDocumentId());
                    ps.setInt(2, ver.getVersionNumber());
                    ps.setString(3, ver.getTitle());
                    ps.setString(4, ver.getKeywords());
                    ps.setString(5, ver.getContent());
                    ps.setString(6, pdfFilename);
                    ps.setBytes(7, pdfData);
                    ps.setInt(8, pdfData != null ? pdfData.length : 0);
                    if (pdfData != null) {
                        ps.setTimestamp(9, new java.sql.Timestamp(System.currentTimeMillis()));
                    } else {
                        ps.setTimestamp(9, null);
                    }
                    ps.executeUpdate();
                }

                // 3. Update keywords index
                updateKeywordsInDb(conn, doc.getDocumentId(), doc.getKeywords());

                conn.commit();
                if (doc.getCreatedAt() == null || doc.getCreatedAt().isEmpty()) {
                    doc.setCreatedAt(new java.sql.Timestamp(System.currentTimeMillis()).toString());
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // Saves a new version with an optional uploaded PDF (preserves old version PDFs)
    public static void saveNewVersion(String docId, int versionNumber, String title, String keywords, String content,
                                      String pdfFilename, byte[] pdfData) throws SQLException, IOException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Insert new version row
                String sqlVer = "INSERT INTO document_versions (document_id, version_number, title, keywords, content, pdf_filename, pdf_data, pdf_size, pdf_uploaded_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sqlVer)) {
                    ps.setString(1, docId);
                    ps.setInt(2, versionNumber);
                    ps.setString(3, title);
                    ps.setString(4, keywords);
                    ps.setString(5, content);
                    ps.setString(6, pdfFilename);
                    ps.setBytes(7, pdfData);
                    ps.setInt(8, pdfData != null ? pdfData.length : 0);
                    if (pdfData != null) {
                        ps.setTimestamp(9, new java.sql.Timestamp(System.currentTimeMillis()));
                    } else {
                        ps.setTimestamp(9, null);
                    }
                    ps.executeUpdate();
                }

                // 2. Update current_version in documents
                String sqlDoc = "UPDATE documents SET current_version = ? WHERE document_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(sqlDoc)) {
                    ps.setInt(1, versionNumber);
                    ps.setString(2, docId);
                    ps.executeUpdate();
                }

                // 3. Update keywords in database
                updateKeywordsInDb(conn, docId, keywords);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    // Switches the active version in MySQL
    public static void switchVersionInDb(String docId, int versionNumber, String keywords) throws SQLException {
        try (Connection conn = DatabaseConnection.getConnection()) {
            String sqlDoc = "UPDATE documents SET current_version = ? WHERE document_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlDoc)) {
                ps.setInt(1, versionNumber);
                ps.setString(2, docId);
                ps.executeUpdate();
            }
            updateKeywordsInDb(conn, docId, keywords);
        }
    }

    // Retrieves PDF binary data for viewing or downloading
    public static PdfRecord getPdfData(String docId, int versionNumber) {
        String sql = "SELECT pdf_filename, pdf_data, pdf_size FROM document_versions WHERE document_id = ? AND version_number = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, docId);
            ps.setInt(2, versionNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String filename = rs.getString("pdf_filename");
                    byte[] data = rs.getBytes("pdf_data");
                    int size = rs.getInt("pdf_size");
                    if (data != null && data.length > 0) {
                        return new PdfRecord(filename != null ? filename : docId + "_V" + versionNumber + ".pdf", data, size);
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error reading PDF from MySQL: " + e.getMessage());
        }
        return null;
    }

    // Text file reading for backup
    private static void loadFromTextFile(DocumentManager manager) throws IOException {
        if (!Files.exists(FILE)) {
            SampleData.loadSampleDocuments(manager);
            saveToTextFile(manager);
            System.out.println("Created initial data file: " + FILE);
            return;
        }

        ArrayList<String> lines = new ArrayList<String>(Files.readAllLines(FILE, StandardCharsets.UTF_8));
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IOException("Invalid saved documents file.");
        }

        int pos = 1;
        while (pos < lines.size()) {
            String[] info = lines.get(pos++).split("\\t", -1);
            if (info.length != 7 || !info[0].equals("D")) {
                throw new IllegalArgumentException("Invalid document");
            }

            String id = decode(info[1]);
            String dept = decode(info[2]);
            String category = decode(info[3]);
            int importance = Integer.parseInt(info[4]);
            int current = Integer.parseInt(info[5]);
            int count = Integer.parseInt(info[6]);

            GovernmentDocument doc = null;
            for (int i = 0; i < count; i++) {
                if (pos >= lines.size()) throw new IllegalArgumentException("Missing version");
                String[] row = lines.get(pos++).split("\\t", -1);
                if (row.length < 4 || !row[0].equals("V")) throw new IllegalArgumentException("Invalid version");

                String title = decode(row[1]);
                String keywords = decode(row[2]);
                String content = decode(row[3]);
                String pdfName = row.length >= 5 ? decode(row[4]) : null;
                int pdfSize = row.length >= 6 ? Integer.parseInt(row[5]) : 0;

                if (doc == null) {
                    doc = new GovernmentDocument(id, title, dept, category, importance, keywords, content, pdfName, pdfSize);
                } else {
                    doc.addVersion(title, keywords, content, pdfName, pdfSize);
                }
            }

            if (doc == null || !doc.setCurrentVersion(current) || !manager.addDocument(doc)) {
                throw new IllegalArgumentException("Invalid saved document");
            }
        }
        System.out.println("Loaded saved documents from text file: " + manager.getDocumentCount());
    }

    private static void saveToTextFile(DocumentManager manager) throws IOException {
        Files.createDirectories(FILE.getParent());
        ArrayList<String> output = new ArrayList<String>();
        output.add(HEADER);

        for (GovernmentDocument doc : manager.getAllDocuments()) {
            ArrayList<DocumentVersion> versions = doc.getAllVersions();
            output.add("D\t" + encode(doc.getDocumentId()) + "\t" + encode(doc.getDepartment()) + "\t"
                    + encode(doc.getCategory()) + "\t" + doc.getImportance() + "\t"
                    + doc.getCurrentVersionNumber() + "\t" + versions.size());

            for (DocumentVersion ver : versions) {
                output.add("V\t" + encode(ver.getTitle()) + "\t" + encode(ver.getKeywords()) + "\t"
                        + encode(ver.getContent()) + "\t"
                        + encode(ver.getPdfFilename() != null ? ver.getPdfFilename() : "") + "\t"
                        + ver.getPdfSize());
            }
        }

        Path temp = FILE.resolveSibling(FILE.getFileName().toString() + ".tmp");
        Files.write(temp, output, StandardCharsets.UTF_8);

        if (Files.exists(FILE)) {
            Path backup = FILE.resolveSibling(FILE.getFileName().toString() + ".bak");
            Files.copy(FILE, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String encode(String text) {
        if (text == null) return "";
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String data) {
        if (data == null || data.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
    }
}
