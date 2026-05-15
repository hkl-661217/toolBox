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

# 2. Run the bundled JDBC-to-JDBC migrator. Both H2 and MySQL drivers are
#    on the fat jar's classpath, so we just point Spring Boot's loader at
#    MigrateH2ToMysql#main with positional args.
MYSQL_URL=${MYSQL_URL:-"jdbc:mysql://127.0.0.1:3306/$DB_NAME?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"}
: "${MYSQL_USER:?must set MYSQL_USER}"
: "${MYSQL_PASS:?must set MYSQL_PASS}"

java -cp "$JAR_PATH" \
    -Dloader.main=com.example.myaiproject.shipping.migration.MigrateH2ToMysql \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    "jdbc:h2:file:$H2_FILE;MODE=MySQL;DATABASE_TO_UPPER=false;IFEXISTS=TRUE;ACCESS_MODE_DATA=r" \
    "$MYSQL_URL" \
    "$MYSQL_USER" \
    "$MYSQL_PASS"

# 3. Independent sanity check on the MySQL side.
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
