# 1. reset tickets first (last run left the table oversold)
psql -U postgres -d bigB_days -f backend/db/reset.sql

# 2. make sure the service is running (separate terminal)
cd backend && DB_PASSWORD=... mvn spring-boot:run

# 3. flood it
cd loadtest
./.venv/bin/python flood.py --total 50000 --concurrency 1000

# 4. confirm the damage in the DB
psql -U postgres -d bigB_days -f backend/db/check.sql


