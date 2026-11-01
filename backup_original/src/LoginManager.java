import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

// Simple login manager for the local college project.
// Passwords are never stored as readable text.
public class LoginManager {
    private static final Path USER_FILE = Paths.get("data", "user_accounts.txt");
    private static final int ROUNDS = 150000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final HashMap<String, Account> users = new HashMap<String, Account>();
    private final HashMap<String, LoginSession> sessions = new HashMap<String, LoginSession>();

    private static class Account {
        String name;
        String role;
        byte[] salt;
        byte[] hash;
        Account(String name, String role, byte[] salt, byte[] hash) {
            this.name = name;
            this.role = role;
            this.salt = salt;
            this.hash = hash;
        }
    }

    public static class LoginSession {
        public final String username;
        public final String role;
        public final String token;
        public final String csrf;
        public final long expiresAt;
        LoginSession(String username, String role, String token, String csrf) {
            this.username = username;
            this.role = role;
            this.token = token;
            this.csrf = csrf;
            this.expiresAt = System.currentTimeMillis() + 8L * 60 * 60 * 1000;
        }
    }

    public LoginManager() throws Exception {
        Files.createDirectories(USER_FILE.getParent());
        if (!Files.exists(USER_FILE)) {
            makeDemoAccounts();
        }
        loadAccounts();
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

    private void loadAccounts() throws IOException {
        for (String line : Files.readAllLines(USER_FILE, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\|", -1);
            if (parts.length != 4) throw new IOException("Invalid user account file.");
            if (!parts[1].equals("HIGHER_OFFICIAL") && !parts[1].equals("LOWER_OFFICIAL")) {
                throw new IOException("Invalid account role.");
            }
            if (users.containsKey(parts[0])) throw new IOException("Duplicate username in account file.");
            try {
                users.put(parts[0], new Account(parts[0], parts[1],
                    Base64.getDecoder().decode(parts[2]), Base64.getDecoder().decode(parts[3])));
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
        Account account = users.get(username);
        if (account == null || password == null) return null;
        try {
            byte[] supplied = passwordHash(password, account.salt);
            if (!MessageDigest.isEqual(account.hash, supplied)) return null;
        } catch (Exception error) {
            return null;
        }
        LoginSession session = new LoginSession(account.name, account.role, randomToken(), randomToken());
        sessions.put(session.token, session);
        return session;
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
