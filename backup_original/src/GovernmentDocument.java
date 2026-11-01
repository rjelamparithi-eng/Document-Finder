import java.util.ArrayList;

// Stores one document and all its saved versions.
public class GovernmentDocument {
    private String documentId;
    private String department;
    private String category;
    private int importance;
    private ArrayList<DocumentVersion> versions;
    private int currentVersion; // version numbers start at 1

    public GovernmentDocument(String id, String title, String department,
                              String category, int importance,
                              String keywords, String content) {
        this.documentId = id.trim().toUpperCase();
        this.department = department.trim();
        this.category = category.trim();
        this.importance = importance;
        this.versions = new ArrayList<DocumentVersion>();
        addVersion(title, keywords, content);
    }

    public String getDocumentId() { return documentId; }
    public String getTitle() { return getCurrentVersion().getTitle(); }
    public String getDepartment() { return department; }
    public String getCategory() { return category; }
    public int getImportance() { return importance; }
    public String getKeywords() { return getCurrentVersion().getKeywords(); }
    public String getContent() { return getCurrentVersion().getContent(); }
    public int getCurrentVersionNumber() { return currentVersion; }

    // Used to show all past versions on the website.
    public ArrayList<DocumentVersion> getAllVersions() {
        return new ArrayList<DocumentVersion>(versions);
    }

    public DocumentVersion getCurrentVersion() {
        return versions.get(currentVersion - 1);
    }

    // Add a new version. The old objects remain in the list unchanged.
    public void addVersion(String title, String keywords, String content) {
        int newNumber = versions.size() + 1;
        DocumentVersion version = new DocumentVersion(newNumber, title.trim(),
                keywords.trim(), content.trim());
        versions.add(version);
        currentVersion = newNumber;
    }

    // Choose which already-saved version should appear in normal search.
    public boolean setCurrentVersion(int number) {
        if (number < 1 || number > versions.size()) {
            return false;
        }
        currentVersion = number;
        return true;
    }

    public void displayDocument() {
        System.out.println("----------------------------------------");
        System.out.println("Document ID : " + documentId);
        System.out.println("Title       : " + getTitle());
        System.out.println("Department  : " + department);
        System.out.println("Category    : " + category);
        System.out.println("Importance  : " + importance);
        System.out.println("Keywords    : " + getKeywords());
        System.out.println("Version     : " + currentVersion + " (Current)");
        System.out.println("Content     : " + getContent());
        System.out.println("----------------------------------------");
    }

    public void displayHistory() {
        System.out.println("\nVERSION HISTORY - " + documentId);
        for (DocumentVersion version : versions) {
            System.out.print("Version " + version.getVersionNumber());
            if (version.getVersionNumber() == currentVersion) {
                System.out.print(" [CURRENT]");
            }
            System.out.println();
            System.out.println("  Title    : " + version.getTitle());
            System.out.println("  Keywords : " + version.getKeywords());
            System.out.println("  Content  : " + version.getContent());
        }
    }
}
