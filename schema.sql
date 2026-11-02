-- ========================================================
-- ARAM OFFICE - E-GOVERNANCE DOCUMENT SYSTEM DATABASE SCHEMA
-- Compatible with MySQL 8.0+ and MariaDB 10.4+
-- ========================================================

CREATE DATABASE IF NOT EXISTS aram_office;
USE aram_office;

-- 1. User profiles and roles
CREATE TABLE IF NOT EXISTS users (
    username VARCHAR(50) PRIMARY KEY,
    role VARCHAR(50) NOT NULL,
    salt VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(100) NOT NULL DEFAULT '',
    department VARCHAR(100) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    higher_official_dept VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN role = 'HIGHER_OFFICIAL' AND is_active = 1 THEN department ELSE NULL END) VIRTUAL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uq_higher_official_dept (higher_official_dept)
);

-- 2. Documents and metadata
CREATE TABLE IF NOT EXISTS documents (
    document_id VARCHAR(50) PRIMARY KEY,
    department VARCHAR(100) NOT NULL,
    category VARCHAR(100) NOT NULL,
    importance INT NOT NULL,
    current_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 3. Document versions & Uploaded PDF files
CREATE TABLE IF NOT EXISTS document_versions (
    document_id VARCHAR(50) NOT NULL,
    version_number INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    keywords TEXT NOT NULL,
    content LONGTEXT NOT NULL,
    pdf_filename VARCHAR(255) NULL,
    pdf_data MEDIUMBLOB NULL,
    pdf_size INT DEFAULT 0,
    pdf_uploaded_at TIMESTAMP NULL,
    PRIMARY KEY (document_id, version_number),
    CONSTRAINT fk_document_versions_doc FOREIGN KEY (document_id) 
        REFERENCES documents(document_id) ON DELETE CASCADE
);

-- 4. Keywords index mapping
CREATE TABLE IF NOT EXISTS keywords (
    keyword VARCHAR(100) NOT NULL,
    document_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (keyword, document_id),
    CONSTRAINT fk_keywords_doc FOREIGN KEY (document_id) 
        REFERENCES documents(document_id) ON DELETE CASCADE
);

-- 5. Correction requests
CREATE TABLE IF NOT EXISTS correction_requests (
    id INT PRIMARY KEY,
    document_id VARCHAR(50) NOT NULL,
    requested_by VARCHAR(50) NOT NULL,
    reason TEXT NOT NULL,
    suggestion TEXT NOT NULL,
    created_at VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by VARCHAR(50) NULL,
    remarks TEXT NULL,
    reviewed_at VARCHAR(100) NULL,
    CONSTRAINT fk_correction_requests_doc FOREIGN KEY (document_id) 
        REFERENCES documents(document_id) ON DELETE CASCADE
);

-- 6. Activity logs
CREATE TABLE IF NOT EXISTS activity_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,
    date_time VARCHAR(100) NOT NULL,
    username VARCHAR(50) NOT NULL,
    action VARCHAR(100) NOT NULL,
    reference VARCHAR(100) NOT NULL,
    details TEXT NOT NULL
);
