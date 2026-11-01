// One saved version of a document. No setters, so old versions stay unchanged.
public class DocumentVersion {
    private final int versionNumber;
    private final String title;
    private final String keywords;
    private final String content;

    public DocumentVersion(int versionNumber, String title, String keywords, String content) {
        this.versionNumber = versionNumber;
        this.title = title;
        this.keywords = keywords;
        this.content = content;
    }

    public int getVersionNumber() { return versionNumber; }
    public String getTitle() { return title; }
    public String getKeywords() { return keywords; }
    public String getContent() { return content; }
}
