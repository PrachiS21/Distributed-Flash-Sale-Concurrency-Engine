-- Reset all tickets back to AVAILABLE between runs.
--   psql -U postgres -d bigB_days -f db/reset.sql

UPDATE tkt
SET status = 'AVAILABLE', user_id = NULL, purchased_at = NULL;
