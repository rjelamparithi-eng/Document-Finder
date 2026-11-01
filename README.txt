ARAM OFFICE - E-GOVERNANCE DOCUMENT SYSTEM
REVIEW-2 PROJECT

Run the localhost website
1. Open this folder in VS Code (the folder containing src and web).
2. Open Terminal > New Terminal.
3. Enter: .\RUN_WEBSITE.bat
4. Open: http://127.0.0.1:8080
5. Keep the terminal running; Ctrl+C stops the server.

Java JDK 21 is required. No Node.js, XAMPP, or MySQL is needed.

Included folders
- src: Java source code (backend, search, versions, login, requests, audit log)
- web: HTML, CSS, JavaScript pages
- data: synthetic sample documents and locally saved app state
- tests: Java tests (run with .\RUN_TESTS.bat)

Accounts: use the existing demo usernames/passwords for this project.
The data/user_accounts.txt file stores password hashes, not readable passwords.
Do not delete or replace the data folder if you want to keep saved changes.

This is a localhost college prototype using local text-file storage, not a MySQL database.
