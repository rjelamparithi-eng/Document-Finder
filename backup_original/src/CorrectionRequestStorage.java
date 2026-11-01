import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;

// Keeps requests and logs in a separate local text file.
public class CorrectionRequestStorage {
    private static final Path FILE = Paths.get("data", "correction_workflow.txt");
    private static final String HEADER = "ARAM_CORRECTIONS_V1";

    public static void load(CorrectionRequestManager manager) throws IOException {
        if (!Files.exists(FILE)) {
            save(manager);
            return;
        }
        ArrayList<String> lines = new ArrayList<String>(
                Files.readAllLines(FILE, StandardCharsets.UTF_8));
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

    public static void save(CorrectionRequestManager manager) throws IOException {
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
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(String encoded) {
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
