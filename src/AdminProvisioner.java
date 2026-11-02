import java.util.Scanner;

// Simple, secure administrative provisioning utility for Higher Official accounts.
// Allows provisioning an official account for an unassigned department.
// Enforces database and backend constraint: at most one active Higher Official per department.
public class AdminProvisioner {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("  ARAM OFFICE — HIGHER OFFICIAL PROVISIONER");
        System.out.println("==================================================");

        try {
            LoginManager logins = new LoginManager();
            String username;
            String password;
            String fullName;
            String department;

            if (args.length >= 4) {
                username = args[0].trim();
                password = args[1].trim();
                fullName = args[2].trim();
                department = args[3].trim();
            } else {
                Scanner scanner = new Scanner(System.in);
                System.out.println("Available departments: Revenue, Health, Education, Public Works\n");

                System.out.print("Enter department: ");
                department = scanner.nextLine().trim();

                System.out.print("Enter full name of official: ");
                fullName = scanner.nextLine().trim();

                System.out.print("Enter username: ");
                username = scanner.nextLine().trim();

                System.out.print("Enter password (at least 12 characters): ");
                password = scanner.nextLine().trim();
            }

            System.out.println("\nProvisioning Higher Official for department: " + department + "...");
            String error = logins.provisionHigherOfficial(username, password, fullName, department);

            if (error != null) {
                System.out.println("\n[ERROR] " + error);
                System.exit(1);
            } else {
                System.out.println("\n[SUCCESS] Higher Official account created successfully!");
                System.out.println("  Username  : " + username);
                System.out.println("  Full Name : " + fullName);
                System.out.println("  Department: " + LoginManager.normalizeDepartment(department));
                System.out.println("  Role      : HIGHER_OFFICIAL");
            }
        } catch (Exception e) {
            System.out.println("\nProvisioning failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
