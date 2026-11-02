import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Login manager backed by MySQL database.
// Preserves existing password hashes, roles, and session security.
public class LoginManager {
    private static final Path USER_FILE = Paths.get("data", "user_accounts.txt");
    private static final int ROUNDS = 150000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final HashMap<String, Account> users = new HashMap<String, Account>();
    private final HashMap<String, LoginSession> sessions = new HashMap<String, LoginSession>();

    public static class Account {
        public String name;
        public String role;
        public String fullName;
        public String department;
        public boolean isActive;
        public byte[] salt;
        public byte[] hash;
        public Account(String name, String role, String fullName, String department, boolean isActive, byte[] salt, byte[] hash) {
            this.name = name;
            this.role = role;
            this.fullName = (fullName != null && !fullName.trim().isEmpty()) ? fullName.trim() : name;
            this.department = (department != null && !department.trim().isEmpty()) ? department.trim() : "";
            this.isActive = isActive;
            this.salt = salt;
            this.hash = hash;
        }
        public Account(String name, String role, byte[] salt, byte[] hash) {
            this(name, role, name, role.equals("HIGHER_OFFICIAL") ? "Revenue" : "", true, salt, hash);
        }
    }

    public static class LoginSession {
        public final String username;
        public final String role;
        public final String fullName;
        public final String department;
        public final String token;
        public final String csrf;
        public final long expiresAt;
        public LoginSession(String username, String role, String fullName, String department, String token, String csrf) {
            this.username = username;
            this.role = role;
            this.fullName = (fullName != null && !fullName.trim().isEmpty()) ? fullName.trim() : username;
            this.department = (department != null && !department.trim().isEmpty()) ? department.trim() : "";
            this.token = token;
            this.csrf = csrf;
            this.expiresAt = System.currentTimeMillis() + 8L * 60 * 60 * 1000;
        }
        public LoginSession(String username, String role, String token, String csrf) {
            this(username, role, username, "", token, csrf);
        }
    }

    public static final String[] VALID_DEPARTMENTS = {"Revenue", "Health", "Education", "Public Works"};

    public static boolean isValidDepartment(String dept) {
        if (dept == null) return false;
        for (String d : VALID_DEPARTMENTS) {
            if (d.equalsIgnoreCase(dept.trim())) return true;
        }
        return false;
    }

    public static String normalizeDepartment(String dept) {
        if (dept == null) return "";
        for (String d : VALID_DEPARTMENTS) {
            if (d.equalsIgnoreCase(dept.trim())) return d;
        }
        return dept.trim();
    }

    public LoginManager() throws Exception {
        Files.createDirectories(USER_FILE.getParent());
        DatabaseConnection.initDatabase();

        boolean loadedFromDb = false;
        try (Connection conn = DatabaseConnection.getConnection()) {
            // Check if users table is empty
            int userCount = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next()) {
                    userCount = rs.getInt(1);
                }
            }

            if (userCount == 0) {
                // Initial migration of accounts to MySQL
                System.out.println("Migrating user accounts to MySQL...");
                if (!Files.exists(USER_FILE)) {
                    makeDemoAccounts();
                }
                migrateAccountsToDb(conn);
            }

            // Load all accounts from MySQL as source of truth
            loadedFromDb = loadAccountsFromDb(conn);
        } catch (SQLException e) {
            System.out.println("MySQL user loading warning: " + e.getMessage() + ". Using text file fallback.");
        }

        if (!loadedFromDb) {
            if (!Files.exists(USER_FILE)) {
                makeDemoAccounts();
            }
            loadAccountsFromTextFile();
        }
    }

    private void migrateAccountsToDb(Connection conn) throws IOException, SQLException {
        if (!Files.exists(USER_FILE)) return;
        String insertSql = "INSERT IGNORE INTO users (username, role, salt, password_hash) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (String line : Files.readAllLines(USER_FILE, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length == 4) {
                    ps.setString(1, parts[0]);
                    ps.setString(2, parts[1]);
                    ps.setString(3, parts[2]);
                    ps.setString(4, parts[3]);
                    ps.executeUpdate();
                }
            }
        }
        System.out.println("Migrated accounts to MySQL database successfully.");
    }

    private boolean loadAccountsFromDb(Connection conn) throws SQLException {
        users.clear();
        String query = "SELECT username, role, full_name, department, is_active, salt, password_hash FROM users";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) {
                String name = rs.getString("username");
                String role = rs.getString("role");
                String fullName = rs.getString("full_name");
                String department = rs.getString("department");
                boolean isActive = rs.getBoolean("is_active");
                String saltStr = rs.getString("salt");
                String hashStr = rs.getString("password_hash");

                try {
                    byte[] salt = Base64.getDecoder().decode(saltStr);
                    byte[] hash = Base64.getDecoder().decode(hashStr);
                    Account acc = new Account(name, role, fullName, department, isActive, salt, hash);
                    users.put(name, acc);
                    users.put(name.toLowerCase(), acc);
                } catch (IllegalArgumentException e) {
                    System.out.println("Error decoding account for: " + name);
                }
            }
        }
        if (!users.isEmpty()) {
            System.out.println("Loaded " + users.size() + " user accounts from MySQL database.");
            return true;
        }
        return false;
    }

    private void makeDemoAccounts() throws Exception {
        String higherPassword = randomPassword();
        String viewerPassword = randomPassword();
        ArrayList<String> lines = new ArrayList<String>();
        lines.add(makeAccountLine("higher", "HIGHER_OFFICIAL", higherPassword));
        lines.add(makeAccountLine("viewer", "LOWER_OFFICIAL", viewerPassword));
        Files.write(USER_FILE, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("============================================");
        System.out.println(" FIRST START - WRITE THESE PASSWORDS DOWN");
        System.out.println(" Higher Official: higher / " + higherPassword);
        System.out.println(" Lower Official : viewer / " + viewerPassword);
        System.out.println(" Passwords are displayed only on FIRST START.");
        System.out.println("============================================");
    }

    private static String randomPassword() {
        String chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            password.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return password.toString();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String makeAccountLine(String name, String role, String password) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = passwordHash(password, salt);
        return name + "|" + role + "|" + Base64.getEncoder().encodeToString(salt)
               + "|" + Base64.getEncoder().encodeToString(hash);
    }

    private void loadAccountsFromTextFile() throws IOException {
        users.clear();
        for (String line : Files.readAllLines(USER_FILE, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length != 4 && parts.length != 7) throw new IOException("Invalid user account file.");
            String name = parts[0];
            String role = parts[1];
            if (!role.equals("HIGHER_OFFICIAL") && !role.equals("LOWER_OFFICIAL")) {
                throw new IOException("Invalid account role.");
            }
            if (users.containsKey(name) || users.containsKey(name.toLowerCase())) {
                throw new IOException("Duplicate username in account file.");
            }
            String fullName = name;
            String department = role.equals("HIGHER_OFFICIAL") ? "Revenue" : "";
            boolean isActive = true;
            String saltStr, hashStr;
            if (parts.length == 7) {
                fullName = parts[2];
                department = parts[3];
                isActive = "1".equals(parts[4]) || "true".equalsIgnoreCase(parts[4]);
                saltStr = parts[5];
                hashStr = parts[6];
            } else {
                saltStr = parts[2];
                hashStr = parts[3];
            }

            try {
                Account acc = new Account(name, role, fullName, department, isActive,
                    Base64.getDecoder().decode(saltStr), Base64.getDecoder().decode(hashStr));
                users.put(name, acc);
                users.put(name.toLowerCase(), acc);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid account encoding.", error);
            }
        }
        if (users.isEmpty()) throw new IOException("No accounts found.");
    }

    private static byte[] passwordHash(String password, byte[] salt) throws Exception {
        char[] characters = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(characters, salt, ROUNDS, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(characters, '\0');
        }
    }

    public synchronized LoginSession login(String username, String password) {
        if (username == null || password == null) return null;
        Account account = users.get(username);
        if (account == null) {
            account = users.get(username.toLowerCase());
        }
        if (account == null || !account.isActive) return null;
        try {
            byte[] supplied = passwordHash(password, account.salt);
            if (!MessageDigest.isEqual(account.hash, supplied)) return null;
        } catch (Exception error) {
            return null;
        }
        LoginSession session = new LoginSession(account.name, account.role, account.fullName, account.department, randomToken(), randomToken());
        sessions.put(session.token, session);
        return session;
    }

    // Public registration for viewers (Lower Officials)
    // Server assigns LOWER_OFFICIAL role; elevated roles cannot be requested.
    public synchronized String registerViewer(String username, String password, String fullName, String department) {
        if (username == null || username.trim().isEmpty()) {
            return "Username is required.";
        }
        username = username.trim();
        if (!username.matches("^[a-zA-Z0-9_-]{3,30}$")) {
            return "Username must be 3 to 30 characters (letters, numbers, underscore, hyphen).";
        }
        if (password == null || password.length() < 12) {
            return "Password must be at least 12 characters long.";
        }
        if (fullName == null || fullName.trim().isEmpty() || fullName.trim().length() > 100) {
            return "Full name is required (up to 100 characters).";
        }
        fullName = fullName.trim();

        String cleanDept = null;
        if (department != null && !department.trim().isEmpty()) {
            if (!isValidDepartment(department)) {
                return "Invalid department. Choose from: Revenue, Health, Education, Public Works.";
            }
            cleanDept = normalizeDepartment(department);
        }

        // Duplicate username validation
        if (users.containsKey(username) || users.containsKey(username.toLowerCase())) {
            return "Username is already registered. Please choose another username.";
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement checkPs = conn.prepareStatement("SELECT username FROM users WHERE LOWER(username) = LOWER(?)")) {
                checkPs.setString(1, username);
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (rs.next()) {
                        return "Username is already registered. Please choose another username.";
                    }
                }
            }

            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = passwordHash(password, salt);

            String sql = "INSERT INTO users (username, role, full_name, department, is_active, salt, password_hash) VALUES (?, 'LOWER_OFFICIAL', ?, ?, 1, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, fullName);
                if (cleanDept != null) {
                    ps.setString(3, cleanDept);
                } else {
                    ps.setNull(3, java.sql.Types.VARCHAR);
                }
                ps.setString(4, Base64.getEncoder().encodeToString(salt));
                ps.setString(5, Base64.getEncoder().encodeToString(hash));
                ps.executeUpdate();
            }

            Account newAcc = new Account(username, "LOWER_OFFICIAL", fullName, cleanDept != null ? cleanDept : "", true, salt, hash);
            users.put(username, newAcc);
            users.put(username.toLowerCase(), newAcc);
            return null; // Success
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062 || "23000".equals(e.getSQLState())) {
                return "Username is already registered. Please choose another username.";
            }
            return "Database error during registration: " + e.getMessage();
        } catch (Exception e) {
            return "Error during registration: " + e.getMessage();
        }
    }

    // Administrative provisioning for Higher Officials.
    // Each department can have at most one active Higher Official.
    public synchronized String provisionHigherOfficial(String username, String password, String fullName, String department) {
        if (username == null || username.trim().isEmpty()) {
            return "Username is required.";
        }
        username = username.trim();
        if (!username.matches("^[a-zA-Z0-9_-]{3,30}$")) {
            return "Username must be 3 to 30 characters (letters, numbers, underscore, hyphen).";
        }
        if (password == null || password.length() < 12) {
            return "Password must be at least 12 characters long.";
        }
        if (fullName == null || fullName.trim().isEmpty() || fullName.trim().length() > 100) {
            return "Full name is required (up to 100 characters).";
        }
        fullName = fullName.trim();

        if (department == null || department.trim().isEmpty() || !isValidDepartment(department)) {
            return "Valid department is required for Higher Official (Revenue, Health, Education, Public Works).";
        }
        String cleanDept = normalizeDepartment(department);

        // Check duplicate username
        if (users.containsKey(username) || users.containsKey(username.toLowerCase())) {
            return "Username is already taken. Please choose another username.";
        }

        // Backend validation: check if active Higher Official already exists for this department
        for (Account acc : users.values()) {
            if (acc.isActive && "HIGHER_OFFICIAL".equals(acc.role) && cleanDept.equalsIgnoreCase(acc.department)) {
                return "An active Higher Official already exists for department: " + cleanDept;
            }
        }

        try (Connection conn = DatabaseConnection.getConnection()) {
            String checkDeptSql = "SELECT username FROM users WHERE role = 'HIGHER_OFFICIAL' AND is_active = 1 AND LOWER(department) = LOWER(?)";
            try (PreparedStatement ps = conn.prepareStatement(checkDeptSql)) {
                ps.setString(1, cleanDept);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return "An active Higher Official already exists for department: " + cleanDept;
                    }
                }
            }

            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = passwordHash(password, salt);

            String sql = "INSERT INTO users (username, role, full_name, department, is_active, salt, password_hash) VALUES (?, 'HIGHER_OFFICIAL', ?, ?, 1, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, fullName);
                ps.setString(3, cleanDept);
                ps.setString(4, Base64.getEncoder().encodeToString(salt));
                ps.setString(5, Base64.getEncoder().encodeToString(hash));
                ps.executeUpdate();
            }

            Account newAcc = new Account(username, "HIGHER_OFFICIAL", fullName, cleanDept, true, salt, hash);
            users.put(username, newAcc);
            users.put(username.toLowerCase(), newAcc);
            return null; // Success
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("uq_higher_official_dept")) {
                return "An active Higher Official already exists for department: " + cleanDept;
            }
            if (e.getErrorCode() == 1062 || "23000".equals(e.getSQLState())) {
                return "An active Higher Official already exists for department: " + cleanDept + " or username taken.";
            }
            return "Database error during provisioning: " + e.getMessage();
        } catch (Exception e) {
            return "Error during provisioning: " + e.getMessage();
        }
    }

    public synchronized Account getAccount(String username) {
        if (username == null) return null;
        Account acc = users.get(username);
        if (acc == null) acc = users.get(username.toLowerCase());
        return acc;
    }

    public synchronized LoginSession getSession(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String item : cookieHeader.split(";")) {
            String cookie = item.trim();
            if (cookie.startsWith("aram_session=")) {
                String token = cookie.substring("aram_session=".length());
                LoginSession session = sessions.get(token);
                if (session != null && System.currentTimeMillis() < session.expiresAt) return session;
                sessions.remove(token);
            }
        }
        return null;
    }

    public synchronized void logout(LoginSession session) {
        sessions.remove(session.token);
    }
}
