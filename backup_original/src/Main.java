import java.util.ArrayList;
import java.util.Scanner;

// Console menu. All inputs come through this file.
public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        DocumentManager manager = new DocumentManager();
        SampleData.loadSampleDocuments(manager);

        int choice = -1;
        while (choice != 0) {
            showMenu();
            choice = readNumber(scanner, "Enter your choice: ");
            switch (choice) {
                case 1: addDocument(scanner, manager); break;
                case 2: manager.viewAllDocuments(); break;
                case 3: searchById(scanner, manager); break;
                case 4: searchByTitle(scanner, manager); break;
                case 5: System.out.println("Total Documents: " + manager.getDocumentCount()); break;
                case 6: searchByKeywords(scanner, manager); break;
                case 7: suggestKeywords(scanner, manager); break;
                case 8: manager.viewCategoryTree(); break;
                case 9: addVersion(scanner, manager); break;
                case 10: viewHistory(scanner, manager); break;
                case 11: changeCurrentVersion(scanner, manager); break;
                case 12: manager.showKeywordIndex(); break;
                case 0: System.out.println("Program closed."); break;
                default: System.out.println("Invalid choice. Please try again.");
            }
        }
        scanner.close();
    }

    private static void showMenu() {
        System.out.println("\n========================================");
        System.out.println(" E-GOVERNANCE DOCUMENT SYSTEM - ARAM OFFICE");
        System.out.println("========================================");
        System.out.println(" 1. Add Document");
        System.out.println(" 2. View All Documents");
        System.out.println(" 3. Search by Document ID");
        System.out.println(" 4. Search by Title");
        System.out.println(" 5. Show Document Count");
        System.out.println(" 6. Search by Keyword (Ranked)");
        System.out.println(" 7. Prefix Keyword Suggestions (Trie)");
        System.out.println(" 8. View Department / Category Tree");
        System.out.println(" 9. Add New Document Version");
        System.out.println("10. View Version History");
        System.out.println("11. Change Current Version");
        System.out.println("12. View Keyword Index");
        System.out.println(" 0. Exit");
    }

    // Reading whole lines avoids Scanner input skipping problems.
    private static int readNumber(Scanner scanner, String message) {
        System.out.print(message);
        String input = scanner.nextLine().trim();
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException error) {
            System.out.println("Please enter a valid number.");
            return -1;
        }
    }

    private static void addDocument(Scanner scanner, DocumentManager manager) {
        System.out.println("\n===== ADD DOCUMENT =====");
        System.out.print("Document ID: ");
        String id = scanner.nextLine().trim();
        if (manager.searchById(id) != null) {
            System.out.println("Error: Document ID already exists.");
            return;
        }
        System.out.print("Title: ");
        String title = scanner.nextLine().trim();
        System.out.print("Department: ");
        String department = scanner.nextLine().trim();
        System.out.print("Category: ");
        String category = scanner.nextLine().trim();
        int importance = readNumber(scanner, "Importance (1-5): ");
        System.out.print("Keywords (separate with commas): ");
        String keywords = scanner.nextLine().trim();
        System.out.print("Short document content: ");
        String content = scanner.nextLine().trim();

        if (id.isEmpty() || title.isEmpty() || department.isEmpty()
                || category.isEmpty() || keywords.isEmpty() || content.isEmpty()) {
            System.out.println("Error: Fields cannot be empty.");
            return;
        }
        if (importance < 1 || importance > 5) {
            System.out.println("Error: Importance must be between 1 and 5.");
            return;
        }
        // The index uses English word keywords made of letters or numbers.
        if (new SearchQuery(keywords).getWords().isEmpty()) {
            System.out.println("Error: Enter at least one valid keyword.");
            return;
        }

        GovernmentDocument doc = new GovernmentDocument(id, title, department,
                category, importance, keywords, content);
        if (manager.addDocument(doc)) {
            System.out.println("Document added successfully.");
        } else {
            System.out.println("Could not add document. Check the details.");
        }
    }

    private static void searchById(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter Document ID: ");
        GovernmentDocument doc = manager.searchById(scanner.nextLine().trim());
        if (doc == null) { System.out.println("Document not found."); }
        else { doc.displayDocument(); }
    }

    private static void searchByTitle(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter title or part of title: ");
        ArrayList<GovernmentDocument> matches = manager.searchByTitle(scanner.nextLine());
        if (matches.isEmpty()) {
            System.out.println("No matching documents found.");
        } else {
            System.out.println("Matching Documents: " + matches.size());
            for (GovernmentDocument doc : matches) { doc.displayDocument(); }
        }
    }

    private static void searchByKeywords(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter keyword(s), separated by space or comma: ");
        String words = scanner.nextLine();
        if (new SearchQuery(words).getWords().isEmpty()) {
            System.out.println("Please enter a valid keyword.");
            return;
        }
        ArrayList<SearchResult> matches = manager.searchByKeywords(words);
        if (matches.isEmpty()) {
            System.out.println("No matching documents found.");
            return;
        }
        System.out.println("\nRanked Results:");
        for (int i = 0; i < matches.size(); i++) {
            SearchResult result = matches.get(i);
            GovernmentDocument doc = result.getDocument();
            System.out.println((i + 1) + ". " + doc.getDocumentId() + " | "
                    + doc.getTitle() + " | Matches: " + result.getMatchCount()
                    + " | Importance: " + doc.getImportance()
                    + " | Current V" + doc.getCurrentVersionNumber());
        }
    }

    private static void suggestKeywords(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter beginning of a keyword: ");
        String prefix = scanner.nextLine().trim();
        ArrayList<String> suggestions = manager.suggestKeywords(prefix);
        if (suggestions.isEmpty()) { System.out.println("No suggestions found."); }
        else {
            System.out.println("Suggestions:");
            for (String word : suggestions) { System.out.println("- " + word); }
        }
    }

    private static void addVersion(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter existing Document ID: ");
        String id = scanner.nextLine().trim();
        if (manager.searchById(id) == null) {
            System.out.println("Document not found.");
            return;
        }
        System.out.print("New title: ");
        String title = scanner.nextLine().trim();
        System.out.print("New keywords (commas or spaces): ");
        String keywords = scanner.nextLine().trim();
        System.out.print("New short content: ");
        String content = scanner.nextLine().trim();
        if (title.isEmpty() || content.isEmpty() || new SearchQuery(keywords).getWords().isEmpty()) {
            System.out.println("Error: Provide title, valid keywords and content.");
            return;
        }
        if (manager.addNewVersion(id, title, keywords, content)) {
            System.out.println("New version saved and marked current. Previous versions kept.");
        }
    }

    private static void viewHistory(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter Document ID: ");
        GovernmentDocument doc = manager.searchById(scanner.nextLine().trim());
        if (doc == null) { System.out.println("Document not found."); }
        else { doc.displayHistory(); }
    }

    private static void changeCurrentVersion(Scanner scanner, DocumentManager manager) {
        System.out.print("Enter Document ID: ");
        String id = scanner.nextLine().trim();
        GovernmentDocument doc = manager.searchById(id);
        if (doc == null) { System.out.println("Document not found."); return; }
        doc.displayHistory();
        int number = readNumber(scanner, "Enter version number to mark current: ");
        if (manager.switchVersion(id, number)) {
            System.out.println("Current version changed. Search index updated.");
        } else {
            System.out.println("Invalid version number.");
        }
    }
}
