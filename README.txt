================================================================================
ARAM OFFICE - E-GOVERNANCE DOCUMENT FINDER
College Mini-Project Review
================================================================================

1. PROJECT OVERVIEW
Aram Office is a Java 21 document finder and record management application
developed as a college mini-project for demonstration purposes. It manages
fictional government documents across four departments: Revenue, Health,
Education, and Public Works.

The system provides fast search using core computer science data structures
(Inverted Index and Trie), document version management, PDF document storage,
departmental access control, correction requests, and user profile management.
All persistent data is stored in a MySQL database.


2. SOFTWARE REQUIREMENTS
- Java Development Kit (JDK) 21 or higher
- MySQL 8.0 or MariaDB (local via XAMPP or remote cloud database like Aiven)
- Modern web browser (Chrome, Edge, Firefox)
- Optional: Docker (for containerized cloud deployment)


3. TECHNOLOGIES USED
- Backend: Java 21 (built using core Java libraries, com.sun.net.httpserver)
- Database: MySQL 8 with Official JDBC Driver (mysql-connector-j-8.4.0.jar)
- Frontend: HTML5, CSS3, and JavaScript (vanilla, no external frameworks)
- Search & Indexing: Inverted Index (HashMap + HashSet) and Trie prefix tree
- Security: PBKDF2 with HMAC-SHA256 password hashing with random salt;
  CSRF tokens and HTTP session cookies
- Deployment: Docker container on Render / Railway with Aiven MySQL


4. HOW TO RUN
A. Running Locally on Windows
1. Start MySQL (for example using the XAMPP Control Panel on port 3306).
2. Open the project root directory in a terminal.
3. Run the website batch file:
   .\RUN_WEBSITE.bat
4. Open your browser and visit:
   http://127.0.0.1:8080
5. To stop the server, press Ctrl+C in the terminal.

B. Local Administrative Provisioning
To create an active Higher Official account for an unassigned department:
1. Run:
   .\PROVISION_HIGHER_OFFICIAL.bat
2. Enter the official username, password (at least 12 characters), full name,
   and choose an unoccupied department (Revenue, Health, Education, Public Works).


5. MAIN FEATURES
- Department-Based Profile System:
  * Higher Official: Can upload documents, create versions, and review
    correction requests strictly within their assigned department. Only one
    active Higher Official is permitted per department.
  * Viewer (Public User): Can register, log in, search, view details, and
    download authorized PDF documents across all departments (read-only).
- PDF Storage & Retrieval:
  * Upload, view in browser (inline), and download (attachment).
  * PDFs are stored securely in MySQL (MEDIUMBLOB) and associated with
    document versions.
- Recent Documents Feature:
  * "Last 3 Months" filter displays documents created within the last 3 calendar
    months based on documents.created_at.
- Advanced Search:
  * Keyword Search using an Inverted Index (HashMap + HashSet) with ranking
    by keyword match frequency and document importance.
  * Autocomplete Trie suggestions for keyword prefixes.
  * Exact document ID lookup and title search.
- Version History:
  * Multi-version tracking. Historical copies and previous PDFs remain intact
    when a new version is created.
- Correction Requests & Audit Log:
  * Viewers can report errors on records.
  * Higher Officials approve or reject requests for their department.
  * Activity logging records key administrative actions.


6. DATABASE DETAILS
The system uses 6 tables in the MySQL database:
1. users: Stores usernames, roles, salt, password hash, full name, department,
   active status, and unique constraint uq_higher_official_dept.
2. documents: Stores document ID, department, category, importance, current
   version number, and created_at timestamp.
3. document_versions: Stores version numbers, title, keywords, content,
   attached PDF filename, and MEDIUMBLOB PDF data.
4. document_keywords: Stores keyword-to-document inverted index mappings.
5. correction_requests: Stores error reports submitted by viewers and review
   decisions by Higher Officials.
6. activity_logs: Stores chronological audit logs of administrative actions.

Database connection settings can be passed using environment variables
(MYSQL_URL, or MYSQLHOST, MYSQLPORT, MYSQLUSER, MYSQLPASSWORD, MYSQLDATABASE).
If none are set, the application defaults to local MySQL on 127.0.0.1:3306.


7. TESTING
The project includes three automated test suites in the tests/ directory:
- TestProject.java: Tests Inverted Index, Trie prefix search, ranking, and
  version switching data structures (41 tests).
- TestReview2Upgrades.java: Tests MySQL JDBC tables, persistence, and PDF
  upload/view/download operations (12 tests).
- TestProfileUpgrade.java: Tests multi-user authentication, 12-character password
  enforcement, department access boundaries, 3-month date filter, and session
  expiration (12 tests).

To execute all tests locally:
Run:
.\RUN_TESTS.bat


8. DEPLOYMENT
The application is deployed using Docker with Java 21 and Aiven MySQL:
- Render: Web Service connected to GitHub repository with Docker runtime.
- Aiven MySQL: Cloud MySQL instance configured with SSL/TLS.
- Dockerfile: Packages compiled Java classes, web assets, and the MySQL driver.
For complete deployment steps, refer to DEPLOYMENT_STEPS.txt.
================================================================================
