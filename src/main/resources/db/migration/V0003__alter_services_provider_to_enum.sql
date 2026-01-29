-- Convert provider column to support enum values with a default
-- The column already exists as VARCHAR(50), but now it will store enum values as strings
-- and have a default value of 'UNKNOWN'

-- Set default value for new rows
ALTER TABLE services ALTER COLUMN provider SET DEFAULT 'UNKNOWN';

-- Update existing NULL values to 'UNKNOWN'
UPDATE services SET provider = 'UNKNOWN' WHERE provider IS NULL;

-- Make the column NOT NULL now that all NULLs are replaced
ALTER TABLE services ALTER COLUMN provider SET NOT NULL;