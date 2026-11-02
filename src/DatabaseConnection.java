import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

// Handles MySQL database connection using JDBC.
// Supports both local execution and online deployment (e.g. Railway).
public class DatabaseConnection {

    private static String getEnv(String name, String fallback) {
        String val = System.getenv(name);
        if (val != null && !val.trim().isEmpty()) {
            return val.trim();
        }
        return fallback;
    }

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.out.println("MySQL JDBC Driver not found in classpath: " + e.getMessage());
        }

        String rawUrl = System.getenv("MYSQL_URL");
        if (rawUrl != null && !rawUrl.trim().isEmpty()) {
            rawUrl = rawUrl.trim();
            // Convert mysql://user:pass@host:port/db to jdbc:mysql://...
            if (rawUrl.startsWith("mysql://")) {
                try {
                    String noPrefix = rawUrl.substring("mysql://".length());
                    String userInfo = "";
                    String hostPortDb = noPrefix;
                    if (noPrefix.contains("@")) {
                        String[] parts = noPrefix.split("@", 2);
                        userInfo = parts[0];
                        hostPortDb = parts[1];
                    }
                    String user = "";
                    String pass = "";
                    if (userInfo.contains(":")) {
                        String[] userParts = userInfo.split(":", 2);
                        user = userParts[0];
                        pass = userParts[1];
                    } else if (!userInfo.isEmpty()) {
                        user = userInfo;
                    }
                    String jdbcUrl = "jdbc:mysql://" + hostPortDb + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
                    return DriverManager.getConnection(jdbcUrl, user, pass);
                } catch (Exception parseEx) {
                    System.out.println("Could not parse MYSQL_URL, falling back to separate variables: " + parseEx.getMessage());
                }
            } else if (rawUrl.startsWith("jdbc:mysql://")) {
                String user = getEnv("MYSQLUSER", getEnv("DB_USER", "root"));
                String pass = getEnv("MYSQLPASSWORD", getEnv("DB_PASSWORD", ""));
                return DriverManager.getConnection(rawUrl, user, pass);
            }
        }

        // Railway and local environment variable fallbacks
        String host = getEnv("MYSQLHOST", getEnv("DB_HOST", "127.0.0.1"));
        String port = getEnv("MYSQLPORT", getEnv("DB_PORT", "3306"));
        String user = getEnv("MYSQLUSER", getEnv("DB_USER", "root"));
        String password = getEnv("MYSQLPASSWORD", getEnv("DB_PASSWORD", ""));
        String database = getEnv("MYSQLDATABASE", getEnv("DB_NAME", "aram_office"));

        String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        return DriverManager.getConnection(jdbcUrl, user, password);
    }

    // Ensures all required tables exist in the database.
    public static void initDatabase() {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            // 1. Users table
            st.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "username VARCHAR(50) PRIMARY KEY, "
                    + "role VARCHAR(50) NOT NULL, "
                    + "full_name VARCHAR(100) NULL, "
                    + "department VARCHAR(100) NULL, "
                    + "is_active BOOLEAN NOT NULL DEFAULT 1, "
                    + "salt VARCHAR(100) NOT NULL, "
                    + "password_hash VARCHAR(100) NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

            // Auto-upgrade existing users table schema if columns are missing
            boolean hasFullName = false;
            boolean hasDept = false;
            boolean hasIsActive = false;
            boolean hasVirtualCol = false;
            try (java.sql.ResultSet rs = st.executeQuery("SHOW COLUMNS FROM users")) {
                while (rs.next()) {
                    String col = rs.getString("Field");
                    if ("full_name".equalsIgnoreCase(col)) hasFullName = true;
                    if ("department".equalsIgnoreCase(col)) hasDept = true;
                    if ("is_active".equalsIgnoreCase(col)) hasIsActive = true;
                    if ("higher_official_dept".equalsIgnoreCase(col)) hasVirtualCol = true;
                }
            } catch (Exception ignored) {}

            if (!hasFullName) {
                try { st.execute("ALTER TABLE users ADD COLUMN full_name VARCHAR(100) NULL AFTER username"); } catch (Exception ignored) {}
            }
            if (!hasDept) {
                try { st.execute("ALTER TABLE users ADD COLUMN department VARCHAR(100) NULL AFTER role"); } catch (Exception ignored) {}
            }
            if (!hasIsActive) {
                try { st.execute("ALTER TABLE users ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT 1 AFTER department"); } catch (Exception ignored) {}
            }
            if (!hasVirtualCol) {
                try {
                    st.execute("ALTER TABLE users ADD COLUMN higher_official_dept VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN role = 'HIGHER_OFFICIAL' AND is_active = 1 THEN department ELSE NULL END) VIRTUAL");
                } catch (Exception ignored) {}
            }
            try {
                st.execute("CREATE UNIQUE INDEX uq_higher_official_dept ON users (higher_official_dept)");
            } catch (Exception ignored) {}

            // Migrate default departments for legacy accounts if not set
            try {
                st.execute("UPDATE users SET department = 'Health', full_name = 'Higher Official - Health' WHERE username = 'higher' AND (department IS NULL OR department = '')");
                st.execute("UPDATE users SET full_name = 'Public Viewer' WHERE username = 'viewer' AND (full_name IS NULL OR full_name = '')");
            } catch (Exception ignored) {}

            // 2. Documents table
            st.execute("CREATE TABLE IF NOT EXISTS documents ("
                    + "document_id VARCHAR(50) PRIMARY KEY, "
                    + "department VARCHAR(100) NOT NULL, "
                    + "category VARCHAR(100) NOT NULL, "
                    + "importance INT NOT NULL, "
                    + "current_version INT NOT NULL DEFAULT 1, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");

            // 3. Document versions & PDF storage (MEDIUMBLOB supports up to 16MB; max 5MB enforced in app)
            st.execute("CREATE TABLE IF NOT EXISTS document_versions ("
                    + "document_id VARCHAR(50) NOT NULL, "
                    + "version_number INT NOT NULL, "
                    + "title VARCHAR(255) NOT NULL, "
                    + "keywords TEXT NOT NULL, "
                    + "content LONGTEXT NOT NULL, "
                    + "pdf_filename VARCHAR(255) NULL, "
                    + "pdf_data MEDIUMBLOB NULL, "
                    + "pdf_size INT DEFAULT 0, "
                    + "pdf_uploaded_at TIMESTAMP NULL, "
                    + "PRIMARY KEY (document_id, version_number), "
                    + "FOREIGN KEY (document_id) REFERENCES documents(document_id) ON DELETE CASCADE)");

            // 4. Keywords index mapping table
            st.execute("CREATE TABLE IF NOT EXISTS keywords ("
                    + "keyword VARCHAR(100) NOT NULL, "
                    + "document_id VARCHAR(50) NOT NULL, "
                    + "PRIMARY KEY (keyword, document_id), "
                    + "FOREIGN KEY (document_id) REFERENCES documents(document_id) ON DELETE CASCADE)");

            // 5. Correction requests table
            st.execute("CREATE TABLE IF NOT EXISTS correction_requests ("
                    + "id INT PRIMARY KEY, "
                    + "document_id VARCHAR(50) NOT NULL, "
                    + "requested_by VARCHAR(50) NOT NULL, "
                    + "reason TEXT NOT NULL, "
                    + "suggestion TEXT NOT NULL, "
                    + "created_at VARCHAR(100) NOT NULL, "
                    + "status VARCHAR(20) NOT NULL DEFAULT 'PENDING', "
                    + "reviewed_by VARCHAR(50) NULL, "
                    + "remarks TEXT NULL, "
                    + "reviewed_at VARCHAR(100) NULL, "
                    + "FOREIGN KEY (document_id) REFERENCES documents(document_id) ON DELETE CASCADE)");

            // 6. Activity logs table
            st.execute("CREATE TABLE IF NOT EXISTS activity_logs ("
                    + "id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "date_time VARCHAR(100) NOT NULL, "
                    + "username VARCHAR(50) NOT NULL, "
                    + "action VARCHAR(100) NOT NULL, "
                    + "reference VARCHAR(100) NOT NULL, "
                    + "details TEXT NOT NULL)");

            System.out.println("MySQL database tables verified successfully.");
        } catch (SQLException e) {
            System.out.println("Database initialization warning: " + e.getMessage());
        }
    }

    public static boolean testConnection() {
        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed();
        } catch (Exception e) {
            System.out.println("MySQL connection test failed: " + e.getMessage());
            return false;
        }
    }
}
