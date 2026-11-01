import java.util.LinkedHashMap;
import java.util.Map;

// One document correction request created by a Lower Official.
public class CorrectionRequest {
    public final int id;
    public final String documentId;
    public final String requestedBy;
    public final String reason;
    public final String suggestion;
    public final String createdAt;
    public String status;
    public String reviewedBy;
    public String remarks;
    public String reviewedAt;

    public CorrectionRequest(int id, String documentId, String requestedBy,
                             String reason, String suggestion, String createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.requestedBy = requestedBy;
        this.reason = reason;
        this.suggestion = suggestion;
        this.createdAt = createdAt;
        this.status = "PENDING";
        this.reviewedBy = "";
        this.remarks = "";
        this.reviewedAt = "";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("documentId", documentId);
        row.put("requestedBy", requestedBy);
        row.put("reason", reason);
        row.put("suggestion", suggestion);
        row.put("createdAt", createdAt);
        row.put("status", status);
        row.put("reviewedBy", reviewedBy);
        row.put("remarks", remarks);
        row.put("reviewedAt", reviewedAt);
        return row;
    }
}
