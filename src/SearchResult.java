// One document returned by a keyword search, with the match count.
public class SearchResult {
    private GovernmentDocument document;
    private int matchCount;

    public SearchResult(GovernmentDocument document, int matchCount) {
        this.document = document;
        this.matchCount = matchCount;
    }

    public GovernmentDocument getDocument() { return document; }
    public int getMatchCount() { return matchCount; }
}
