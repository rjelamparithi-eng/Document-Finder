import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;

// All document operations are written here.
public class DocumentManager {
    private ArrayList<GovernmentDocument> documents = new ArrayList<GovernmentDocument>();
    private HashMap<String, GovernmentDocument> documentsById = new HashMap<String, GovernmentDocument>();
    private HashMap<String, HashSet<String>> keywordIndex = new HashMap<String, HashSet<String>>();
    private KeywordTrie trie = new KeywordTrie();
    private CategoryNode categoryRoot = new CategoryNode("Aram Office");

    public boolean addDocument(GovernmentDocument document) {
        if (document == null || document.getDocumentId().isEmpty()
                || document.getTitle().isEmpty() || document.getDepartment().isEmpty()
                || document.getCategory().isEmpty() || document.getKeywords().isEmpty()
                || document.getContent().isEmpty()
                || new SearchQuery(document.getKeywords()).getWords().isEmpty()
                || document.getImportance() < 1 || document.getImportance() > 5
                || searchById(document.getDocumentId()) != null) {
            return false;
        }
        documents.add(document);
        documentsById.put(document.getDocumentId(), document);
        rebuildIndexes();
        return true;
    }

    public GovernmentDocument searchById(String id) {
        if (id == null) { return null; }
        return documentsById.get(id.trim().toUpperCase());
    }

    public ArrayList<GovernmentDocument> searchByTitle(String title) {
        ArrayList<GovernmentDocument> matches = new ArrayList<GovernmentDocument>();
        if (title == null || title.trim().isEmpty()) { return matches; }
        for (GovernmentDocument doc : documents) {
            if (doc.getTitle().toLowerCase().contains(title.trim().toLowerCase())) {
                matches.add(doc);
            }
        }
        return matches;
    }

    // Exact keyword search. HashMap finds document IDs for each query word.
    public ArrayList<SearchResult> searchByKeywords(String text) {
        SearchQuery query = new SearchQuery(text);
        HashMap<String, Integer> scores = new HashMap<String, Integer>();
        for (String word : query.getWords()) {
            HashSet<String> ids = keywordIndex.get(word);
            if (ids != null) {
                for (String id : ids) {
                    int oldScore = 0;
                    if (scores.containsKey(id)) { oldScore = scores.get(id); }
                    scores.put(id, oldScore + 1);
                }
            }
        }

        ArrayList<SearchResult> results = new ArrayList<SearchResult>();
        for (String id : scores.keySet()) {
            results.add(new SearchResult(searchById(id), scores.get(id)));
        }
        // A simple selection sort: keyword matches, importance, document ID.
        for (int i = 0; i < results.size(); i++) {
            int best = i;
            for (int j = i + 1; j < results.size(); j++) {
                if (comesBefore(results.get(j), results.get(best))) {
                    best = j;
                }
            }
            SearchResult temp = results.get(i);
            results.set(i, results.get(best));
            results.set(best, temp);
        }
        return results;
    }

    private boolean comesBefore(SearchResult a, SearchResult b) {
        if (a.getMatchCount() != b.getMatchCount()) {
            return a.getMatchCount() > b.getMatchCount();
        }
        if (a.getDocument().getImportance() != b.getDocument().getImportance()) {
            return a.getDocument().getImportance() > b.getDocument().getImportance();
        }
        return a.getDocument().getDocumentId().compareTo(b.getDocument().getDocumentId()) < 0;
    }

    public ArrayList<String> suggestKeywords(String prefix) {
        if (prefix == null || !prefix.trim().toLowerCase().matches("[a-z0-9]+")) {
            return new ArrayList<String>();
        }
        return trie.suggest(prefix.trim().toLowerCase());
    }

    public void viewCategoryTree() { categoryRoot.display(0); }

    // The website reads copies of these lists; the original data stays in this manager.
    public ArrayList<GovernmentDocument> getAllDocuments() {
        return new ArrayList<GovernmentDocument>(documents);
    }

    public HashMap<String, HashSet<String>> getKeywordIndex() {
        HashMap<String, HashSet<String>> copy = new HashMap<String, HashSet<String>>();
        for (String word : keywordIndex.keySet()) {
            copy.put(word, new HashSet<String>(keywordIndex.get(word)));
        }
        return copy;
    }

    public int getDocumentCount() { return documents.size(); }

    public void viewAllDocuments() {
        System.out.println("\n===== ALL DOCUMENTS (CURRENT VERSIONS) =====");
        for (GovernmentDocument doc : documents) {
            System.out.println(doc.getDocumentId() + " | " + doc.getTitle()
                    + " | " + doc.getDepartment() + " | " + doc.getCategory()
                    + " | V" + doc.getCurrentVersionNumber());
        }
        System.out.println("Total Documents: " + documents.size());
    }

    public boolean addNewVersion(String id, String title, String keywords, String content) {
        return addNewVersion(id, title, keywords, content, null, 0);
    }

    public boolean addNewVersion(String id, String title, String keywords, String content, String pdfFilename, int pdfSize) {
        GovernmentDocument doc = searchById(id);
        if (doc == null || title == null || keywords == null || content == null
                || title.trim().isEmpty() || new SearchQuery(keywords).getWords().isEmpty()
                || content.trim().isEmpty()) { return false; }
        doc.addVersion(title, keywords, content, pdfFilename, pdfSize);
        rebuildIndexes();
        return true;
    }

    public boolean switchVersion(String id, int version) {
        GovernmentDocument doc = searchById(id);
        if (doc == null || !doc.setCurrentVersion(version)) { return false; }
        rebuildIndexes();
        return true;
    }

    // Rebuild all search information from CURRENT versions only.
    // Simple and safe for 40-60 sample documents.
    private void rebuildIndexes() {
        keywordIndex.clear();
        trie = new KeywordTrie();
        categoryRoot = new CategoryNode("Aram Office");
        for (GovernmentDocument doc : documents) {
            CategoryNode department = categoryRoot.getOrAddChild(doc.getDepartment());
            CategoryNode category = department.getOrAddChild(doc.getCategory());
            category.addDocumentId(doc.getDocumentId());

            SearchQuery words = new SearchQuery(doc.getKeywords());
            for (String word : words.getWords()) {
                if (!keywordIndex.containsKey(word)) {
                    keywordIndex.put(word, new HashSet<String>());
                }
                keywordIndex.get(word).add(doc.getDocumentId());
                trie.insert(word);
            }
        }
    }

    public void showKeywordIndex() {
        ArrayList<String> words = new ArrayList<String>(keywordIndex.keySet());
        Collections.sort(words);
        System.out.println("\n===== KEYWORD INDEX =====");
        for (String word : words) {
            ArrayList<String> ids = new ArrayList<String>(keywordIndex.get(word));
            Collections.sort(ids);
            System.out.println(word + " -> " + ids);
        }
    }
}
