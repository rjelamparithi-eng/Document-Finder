import java.time.Instant;
import java.util.ArrayList;

// Stores requests and activity logs. No database needed for this college prototype.
public class CorrectionRequestManager {
    private final ArrayList<CorrectionRequest> requests = new ArrayList<CorrectionRequest>();
    private final ArrayList<ActivityLogEntry> activities = new ArrayList<ActivityLogEntry>();
    private int nextRequestId = 1;

    public CorrectionRequest create(String documentId, String username,
                                    String reason, String suggestion) {
        CorrectionRequest request = new CorrectionRequest(nextRequestId,
                documentId, username, reason, suggestion, Instant.now().toString());
        nextRequestId++;
        requests.add(request);
        addActivity(username, "REQUEST_CREATED", documentId,
                "Request #" + request.id + " submitted");
        return request;
    }

    public boolean review(int id, String decision, String remarks, String reviewer) {
        for (CorrectionRequest request : requests) {
            if (request.id == id) {
                if (!request.status.equals("PENDING")) return false;
                if (!decision.equals("APPROVED") && !decision.equals("REJECTED")) return false;
                request.status = decision;
                request.remarks = remarks;
                request.reviewedBy = reviewer;
                request.reviewedAt = Instant.now().toString();
                addActivity(reviewer, "REQUEST_" + decision, request.documentId,
                        "Request #" + id + ": " + remarks);
                return true;
            }
        }
        return false;
    }

    public CorrectionRequest getRequest(int id) {
        for (CorrectionRequest request : requests) {
            if (request.id == id) {
                return request;
            }
        }
        return null;
    }

    public ArrayList<CorrectionRequest> requestsFor(String username, boolean higherOfficial) {
        ArrayList<CorrectionRequest> result = new ArrayList<CorrectionRequest>();
        for (CorrectionRequest request : requests) {
            if (higherOfficial || request.requestedBy.equals(username)) result.add(request);
        }
        return result;
    }

    public ArrayList<ActivityLogEntry> getActivities() {
        return new ArrayList<ActivityLogEntry>(activities);
    }

    public void addActivity(String username, String action, String reference, String details) {
        activities.add(new ActivityLogEntry(Instant.now().toString(),
                username, action, reference, details));
    }

    // Called only when reading the saved file.
    public void loadRequest(CorrectionRequest request) {
        requests.add(request);
        if (request.id >= nextRequestId) nextRequestId = request.id + 1;
    }

    public void loadActivity(ActivityLogEntry activity) {
        activities.add(activity);
    }
}
