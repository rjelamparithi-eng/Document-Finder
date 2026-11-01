import java.util.ArrayList;

// Simple tests that do not need JUnit or any extra software.
public class TestProject {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean success) {
        if (success) {
            passed++;
            System.out.println("PASS - " + name);
        } else {
            failed++;
            System.out.println("FAIL - " + name);
        }
    }

    private static boolean containsId(ArrayList<SearchResult> results, String id) {
        for (SearchResult result : results) {
            if (result.getDocument().getDocumentId().equals(id)) { return true; }
        }
        return false;
    }

    public static void main(String[] args) {
        DocumentManager manager = new DocumentManager();
        SampleData.loadSampleDocuments(manager);

        check("48 starting documents", manager.getDocumentCount() == 48);
        check("ID search ignores case", manager.searchById("doc003") != null);
        check("ID search trims spaces", manager.searchById("  doc001  ") != null);
        check("Unknown ID gives null", manager.searchById("DOC999") == null);
        check("Title partial matching", manager.searchByTitle("certIFICATE").size() == 4);
        check("Empty title finds nothing", manager.searchByTitle(" ").isEmpty());
        check("Keyword search finds DOC001", containsId(manager.searchByKeywords("birth"), "DOC001"));
        check("Exact keyword does not match substrings", manager.searchByKeywords("cert").isEmpty());
        check("Keyword case insensitive", containsId(manager.searchByKeywords("BIRTH"), "DOC001"));
        check("Repeated query word counted once", manager.searchByKeywords("birth birth").get(0).getMatchCount() == 1);

        ArrayList<SearchResult> ranked = manager.searchByKeywords("birth certificate application");
        check("Ranking places strongest match first", ranked.get(0).getDocument().getDocumentId().equals("DOC001")
                && ranked.get(0).getMatchCount() == 3);
        check("Ranking importance and ID tie-breaker", manager.searchByKeywords("certificate application").get(0)
                .getDocument().getDocumentId().equals("DOC001"));
        check("Trie suggests application", manager.suggestKeywords("app").contains("application"));
        check("Trie suggests appointment", manager.suggestKeywords("app").contains("appointment"));
        check("Unknown prefix has no results", manager.suggestKeywords("zzzz").isEmpty());
        check("Empty prefix has no results", manager.suggestKeywords("").isEmpty());

        GovernmentDocument duplicate = new GovernmentDocument("doc001", "Duplicate", "Revenue",
                "Policy", 4, "duplicate", "Duplicate should be refused.");
        check("Duplicate ID refused", !manager.addDocument(duplicate));
        check("Count still 48", manager.getDocumentCount() == 48);
        GovernmentDocument empty = new GovernmentDocument("DOC050", "", "Revenue",
                "Policy", 4, "newword", "Empty title is not allowed.");
        check("Empty required title refused", !manager.addDocument(empty));
        GovernmentDocument badImportance = new GovernmentDocument("DOC050", "New", "Revenue",
                "Policy", 6, "newword", "Out of range importance.");
        check("Bad importance refused", !manager.addDocument(badImportance));
        GovernmentDocument invalidKeywords = new GovernmentDocument("DOC050", "New", "Revenue",
                "Policy", 4, "---", "Invalid keywords.");
        check("Invalid keywords refused", !manager.addDocument(invalidKeywords));
        GovernmentDocument newDoc = new GovernmentDocument("doc049", "Water Complaint Form", "Public Works",
                "Application Form", 4, "complaint water newword", "Water complaint registration instructions.");
        check("New document added", manager.addDocument(newDoc));
        check("Count becomes 49", manager.getDocumentCount() == 49);
        check("Added document searchable immediately", containsId(manager.searchByKeywords("newword"), "DOC049"));
        check("Added keyword available in Trie", manager.suggestKeywords("neww").contains("newword"));
        check("Duplicate of newly added document refused", !manager.addDocument(newDoc));

        GovernmentDocument original = manager.searchById("DOC001");
        String oldTitle = original.getTitle();
        check("New version saved", manager.addNewVersion("DOC001", "Digital Birth Record",
                "digital record specialword", "Version 2 of document content."));
        check("Version 2 is current", original.getCurrentVersionNumber() == 2);
        check("Old DOC001 keywords no longer indexed", !containsId(manager.searchByKeywords("identity"), "DOC001"));
        check("New DOC001 keywords indexed", containsId(manager.searchByKeywords("specialword"), "DOC001"));
        check("Trie updates on version change", manager.suggestKeywords("special").contains("specialword"));
        check("Title search uses current version", !manager.searchByTitle("Digital Birth Record").isEmpty());
        check("Unknown version rejected", !manager.switchVersion("DOC001", 99));
        check("Return to version 1", manager.switchVersion("DOC001", 1));
        check("Previous version preserved", original.getTitle().equals(oldTitle));
        check("Old keywords restored in index", containsId(manager.searchByKeywords("identity"), "DOC001"));
        check("Version 2 words removed from current index", !containsId(manager.searchByKeywords("specialword"), "DOC001"));
        check("Unused prefix removed after switch", manager.suggestKeywords("special").isEmpty());
        check("Version 2 remains available", manager.switchVersion("DOC001", 2)
                && original.getTitle().equals("Digital Birth Record"));
        check("Unrecognized ID cannot receive version", !manager.addNewVersion("DOC500", "Title", "word", "content"));
        check("Empty new-version keywords rejected", !manager.addNewVersion("DOC001", "Title", "---", "content"));

        System.out.println("\nRESULT: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) { System.exit(1); }
    }
}
