import java.util.ArrayList;

// Converts a search input into unique, lowercase search keywords.
public class SearchQuery {
    private ArrayList<String> words;

    public SearchQuery(String text) {
        words = new ArrayList<String>();
        String[] parts = text.toLowerCase().split("[^a-z0-9]+");
        for (String word : parts) {
            if (!word.isEmpty() && !words.contains(word)) {
                words.add(word);
            }
        }
    }

    public ArrayList<String> getWords() { return words; }
}
