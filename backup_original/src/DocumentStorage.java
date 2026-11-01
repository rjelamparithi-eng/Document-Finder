import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;

public class DocumentStorage {

    private static final Path FILE =
        Paths.get("data", "saved_documents.txt");

    private static final String HEADER = "ARAM_OFFICE_V1";

    public static void loadOrCreate(DocumentManager manager)
            throws IOException {

        if (!Files.exists(FILE)) {
            SampleData.loadSampleDocuments(manager);
            save(manager);
            System.out.println("Created data file: " + FILE);
            return;
        }

        ArrayList<String> lines = new ArrayList<String>(
            Files.readAllLines(FILE, StandardCharsets.UTF_8)
        );

        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IOException("Invalid saved documents file.");
        }

        try {
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

                if (count < 1 || count > 1000) {
                    throw new IllegalArgumentException("Invalid version count");
                }

                GovernmentDocument doc = null;

                for (int i = 0; i < count; i++) {
                    if (pos >= lines.size()) {
                        throw new IllegalArgumentException("Missing version");
                    }

                    String[] row = lines.get(pos++).split("\\t", -1);

                    if (row.length != 4 || !row[0].equals("V")) {
                        throw new IllegalArgumentException("Invalid version");
                    }

                    String title = decode(row[1]);
                    String keywords = decode(row[2]);
                    String content = decode(row[3]);

                    if (doc == null) {
                        doc = new GovernmentDocument(
                            id, title, dept, category,
                            importance, keywords, content
                        );
                    } else {
                        doc.addVersion(title, keywords, content);
                    }
                }

                if (doc == null ||
                    !doc.setCurrentVersion(current) ||
                    !manager.addDocument(doc)) {

                    throw new IllegalArgumentException(
                        "Invalid saved document"
                    );
                }
            }

            System.out.println("Loaded saved documents: " +
                manager.getDocumentCount());

        } catch (RuntimeException error) {
            throw new IOException(
                "Saved data is invalid. Check the backup file.",
                error
            );
        }
    }

    public static void save(DocumentManager manager)
            throws IOException {

        Files.createDirectories(FILE.getParent());

        ArrayList<String> output = new ArrayList<String>();
        output.add(HEADER);

        for (GovernmentDocument doc : manager.getAllDocuments()) {

            ArrayList<DocumentVersion> versions =
                doc.getAllVersions();

            output.add(
                "D\t" +
                encode(doc.getDocumentId()) + "\t" +
                encode(doc.getDepartment()) + "\t" +
                encode(doc.getCategory()) + "\t" +
                doc.getImportance() + "\t" +
                doc.getCurrentVersionNumber() + "\t" +
                versions.size()
            );

            for (DocumentVersion version : versions) {
                output.add(
                    "V\t" +
                    encode(version.getTitle()) + "\t" +
                    encode(version.getKeywords()) + "\t" +
                    encode(version.getContent())
                );
            }
        }

        Path temp = FILE.resolveSibling(
            FILE.getFileName().toString() + ".tmp"
        );

        Files.write(temp, output, StandardCharsets.UTF_8);

        if (Files.exists(FILE)) {
            Path backup = FILE.resolveSibling(
                FILE.getFileName().toString() + ".bak"
            );

            Files.copy(
                FILE, backup,
                StandardCopyOption.REPLACE_EXISTING
            );
        }

        try {
            Files.move(
                temp, FILE,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(
                temp, FILE,
                StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(
            text.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String decode(String data) {
        return new String(
            Base64.getDecoder().decode(data),
            StandardCharsets.UTF_8
        );
    }
}
