#!/bin/sh
# Redis: refill the list
redis-cli DEL flash:tickets:available flash:tickets:owner
for i in $(seq 1 100); do redis-cli RPUSH flash:tickets:available "$(printf 'TICK-%03d' $i)"; done

# Postgres: back to AVAILABLE
psql -U postgres -d bigB_days -f backend/db/reset.sql
