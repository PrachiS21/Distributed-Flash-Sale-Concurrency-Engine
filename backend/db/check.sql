-- Did we oversell? Run after a load test.
--   psql -U postgres -d bigB_days -f db/check.sql

SELECT status, COUNT(*) AS n
FROM tkt
GROUP BY status
ORDER BY status;

-- Distinct buyers who "won" a ticket. Under a correct system this is <= 100.
SELECT COUNT(*)          AS sold_rows,
       COUNT(DISTINCT user_id) AS distinct_winners
FROM tkt
WHERE status = 'SOLD';
