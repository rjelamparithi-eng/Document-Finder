import java.util.LinkedHashMap;
import java.util.Map;

// Simple log of an action taken by a signed-in official.
public class ActivityLogEntry {
    public final String dateTime;
    public final String username;
    public final String action;
    public final String reference;
    public final String details;

    public ActivityLogEntry(String dateTime, String username, String action,
                            String reference, String details) {
        this.dateTime = dateTime;
        this.username = username;
        this.action = action;
        this.reference = reference;
        this.details = details;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("dateTime", dateTime);
        row.put("username", username);
        row.put("action", action);
        row.put("reference", reference);
        row.put("details", details);
        return row;
    }
}
