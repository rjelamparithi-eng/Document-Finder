// One saved version of a document. No setters, so old versions stay unchanged.
public class DocumentVersion {
    private final int versionNumber;
    private final String title;
    private final String keywords;
    private final String content;
    private final String pdfFilename;
    private final int pdfSize;

    public DocumentVersion(int versionNumber, String title, String keywords, String content) {
        this(versionNumber, title, keywords, content, null, 0);
    }

    public DocumentVersion(int versionNumber, String title, String keywords, String content,
                           String pdfFilename, int pdfSize) {
        this.versionNumber = versionNumber;
        this.title = title;
        this.keywords = keywords;
        this.content = content;
        this.pdfFilename = pdfFilename;
        this.pdfSize = pdfSize;
    }

    public int getVersionNumber() { return versionNumber; }
    public String getTitle() { return title; }
    public String getKeywords() { return keywords; }
    public String getContent() { return content; }
    public String getPdfFilename() { return pdfFilename; }
    public int getPdfSize() { return pdfSize; }
    public boolean hasPdf() { return pdfFilename != null && !pdfFilename.trim().isEmpty() && pdfSize > 0; }
}
