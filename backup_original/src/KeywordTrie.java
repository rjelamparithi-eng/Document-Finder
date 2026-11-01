import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

// Trie stores words letter by letter for prefix suggestions.
public class KeywordTrie {
    private static class Node {
        HashMap<Character, Node> children = new HashMap<Character, Node>();
        boolean endOfWord = false;
    }

    private Node root = new Node();

    public void insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            char letter = word.charAt(i);
            if (!node.children.containsKey(letter)) {
                node.children.put(letter, new Node());
            }
            node = node.children.get(letter);
        }
        node.endOfWord = true;
    }

    public ArrayList<String> suggest(String prefix) {
        ArrayList<String> suggestions = new ArrayList<String>();
        Node node = root;
        for (int i = 0; i < prefix.length(); i++) {
            char letter = prefix.charAt(i);
            if (!node.children.containsKey(letter)) {
                return suggestions;
            }
            node = node.children.get(letter);
        }
        collectWords(node, prefix, suggestions);
        Collections.sort(suggestions); // predictable alphabetical output
        return suggestions;
    }

    private void collectWords(Node node, String text, ArrayList<String> words) {
        if (node.endOfWord) {
            words.add(text);
        }
        for (char letter : node.children.keySet()) {
            collectWords(node.children.get(letter), text + letter, words);
        }
    }
}
