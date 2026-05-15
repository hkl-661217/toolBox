#!/usr/bin/env bash
# One-shot migration of the H2 file at
#   /opt/toolBox/msc-shipping-tracking/data/shipping-tracking/shipping-tracking.mv.db
# into the MySQL database `msc_shipping_tracking`. Idempotent in the sense
# that re-running it on a fresh, empty MySQL database will repopulate it
# from the H2 backup taken at step 1.
#
# Assumes:
#   - msc-shipping-tracking.service is stopped
#   - MySQL is reachable as root on 127.0.0.1:3306
#   - The new MySQL database + user already exist (see ENV defaults below)
#   - The app.jar at $JAR_PATH bundles H2 (it does — h2 is a runtime dep)
#
# Usage:
#   sudo bash migrate_h2_to_mysql.sh
set -euo pipefail

APP_DIR=${APP_DIR:-/opt/toolBox/msc-shipping-tracking}
H2_DIR="$APP_DIR/data/shipping-tracking"
H2_FILE="$H2_DIR/shipping-tracking"   # H2 wants the base name, no .mv.db suffix
JAR_PATH=${JAR_PATH:-/opt/msc-shipping-tracking/app.jar}
DB_NAME=${DB_NAME:-msc_shipping_tracking}
BACKUP_DIR=${BACKUP_DIR:-/root/msc-h2-backups}
TS=$(date +%Y%m%d%H%M%S)

mkdir -p "$BACKUP_DIR"

# 1. Backup the live H2 file.
cp -f "$H2_DIR/shipping-tracking.mv.db" "$BACKUP_DIR/shipping-tracking.mv.db.$TS"
echo "Backed up H2 to $BACKUP_DIR/shipping-tracking.mv.db.$TS"

# 2. Dump everything from H2 to SQL using H2's own Script tool. The fat app
#    jar already contains org.h2.tools.Script.
DUMP="/tmp/msc-h2-dump-$TS.sql"
java -cp "$JAR_PATH" -Dloader.main=org.h2.tools.Script org.springframework.boot.loader.launch.PropertiesLauncher \
    -url "jdbc:h2:file:$H2_FILE;MODE=MySQL;DATABASE_TO_UPPER=false;IFEXISTS=TRUE" \
    -user sa \
    -script "$DUMP" \
    || java -cp "$JAR_PATH" org.h2.tools.Script \
        -url "jdbc:h2:file:$H2_FILE;MODE=MySQL;DATABASE_TO_UPPER=false;IFEXISTS=TRUE" \
        -user sa \
        -script "$DUMP"
echo "Dumped H2 to $DUMP"

# 3. Keep only the INSERTs that target our four tables. H2's dump also
#    includes CREATE TABLE / ALTER and other statements we don't want — the
#    MySQL schema is already in place via schema-mysql.sql on app startup.
INSERTS="/tmp/msc-mysql-inserts-$TS.sql"
grep -iE '^INSERT INTO "?(SHIPPING_TRACKING_BINDING|SHIPPING_TRACKING_SNAPSHOT|SHIPPING_TRACKING_CHANGE_LOG|SHIPPING_TRACKING_NOTIFICATION_ACCOUNT)"?' "$DUMP" \
    > "$INSERTS"
# H2 quotes identifiers with double quotes; MySQL needs backticks. Lowercase
# them while we're here to match the MySQL schema's lowercase table names.
sed -i -E 's/"([A-Z_]+)"/`\L\1`/g' "$INSERTS"
echo "Extracted $(wc -l < "$INSERTS") INSERT statements into $INSERTS"

# 4. Load into MySQL. FK constraints make the order matter (parent before
#    child) — H2 dumps in alphabetical-ish order which is fine since
#    binding < change_log < notification_account < snapshot would break.
#    Disable FK checks during the bulk load and re-enable after.
mysql "$DB_NAME" <<SQL
SET foreign_key_checks = 0;
SOURCE $INSERTS;
SET foreign_key_checks = 1;
SQL

# 5. Reset auto_increment to MAX(id)+1 on each table so future inserts
#    don't collide with the imported ids.
for t in shipping_tracking_binding shipping_tracking_snapshot shipping_tracking_change_log shipping_tracking_notification_account; do
    next=$(mysql -N -e "SELECT IFNULL(MAX(id), 0) + 1 FROM $DB_NAME.$t;")
    mysql -e "ALTER TABLE $DB_NAME.$t AUTO_INCREMENT = $next;"
    echo "  $t: auto_increment -> $next"
done

# 6. Sanity counts.
echo "--- MySQL row counts ---"
mysql -N -e "
    SELECT 'binding',  COUNT(*) FROM $DB_NAME.shipping_tracking_binding;
    SELECT 'snapshot', COUNT(*) FROM $DB_NAME.shipping_tracking_snapshot;
    SELECT 'change',   COUNT(*) FROM $DB_NAME.shipping_tracking_change_log;
    SELECT 'account',  COUNT(*) FROM $DB_NAME.shipping_tracking_notification_account;
"

echo "Migration done. Now update /etc/toolbox/msc-shipping-tracking.env with"
echo "  SPRING_PROFILES_ACTIVE=prod"
echo "  SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/$DB_NAME?..."
echo "  SPRING_DATASOURCE_USERNAME=msc_shipping_app"
echo "  SPRING_DATASOURCE_PASSWORD=<the password from setup_mysql.sh>"
echo "and restart: systemctl restart msc-shipping-tracking"
