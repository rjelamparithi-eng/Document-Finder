import java.util.ArrayList;

// Tree structure: Aram Office -> Department -> Category -> Document IDs.
public class CategoryNode {
    private String name;
    private ArrayList<CategoryNode> children;
    private ArrayList<String> documentIds;

    public CategoryNode(String name) {
        this.name = name;
        children = new ArrayList<CategoryNode>();
        documentIds = new ArrayList<String>();
    }

    public CategoryNode getOrAddChild(String childName) {
        for (CategoryNode child : children) {
            if (child.name.equalsIgnoreCase(childName)) {
                return child;
            }
        }
        CategoryNode child = new CategoryNode(childName);
        children.add(child);
        return child;
    }

    public void addDocumentId(String id) {
        if (!documentIds.contains(id)) {
            documentIds.add(id);
        }
    }

    public void display(int level) {
        String spaces = "";
        for (int i = 0; i < level; i++) {
            spaces += "  ";
        }
        System.out.println(spaces + "- " + name);
        for (String id : documentIds) {
            System.out.println(spaces + "  * " + id);
        }
        for (CategoryNode child : children) {
            child.display(level + 1);
        }
    }
}
