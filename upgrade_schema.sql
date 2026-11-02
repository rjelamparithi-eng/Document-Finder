-- ========================================================
-- Schema Migration: Multi-User Profile & Department System
-- ========================================================

-- Add profile fields safely if not already present
ALTER TABLE users ADD COLUMN IF NOT EXISTS full_name VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS department VARCHAR(100) NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;

-- Add virtual generated column to enforce at most ONE active Higher Official per department
-- Note: Lower officials evaluate to NULL which does not conflict in UNIQUE indexes
ALTER TABLE users ADD COLUMN IF NOT EXISTS higher_official_dept VARCHAR(100) 
    GENERATED ALWAYS AS (CASE WHEN role = 'HIGHER_OFFICIAL' AND is_active = 1 THEN department ELSE NULL END) VIRTUAL;

-- Add unique index on higher_official_dept (ignore if exists)
CREATE UNIQUE INDEX IF NOT EXISTS uq_higher_official_dept ON users (higher_official_dept);

-- Update existing accounts with department and full names if not set
UPDATE users SET full_name = 'Higher Official - Health', department = 'Health' 
WHERE username = 'higher' AND (department IS NULL OR department = '');

UPDATE users SET full_name = 'Public Viewer', department = NULL 
WHERE username = 'viewer' AND (full_name IS NULL OR full_name = '');
