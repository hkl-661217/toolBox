#!/usr/bin/env bash
# One-shot: create the msc_shipping_tracking database and app user on the
# already-installed MySQL 8 instance. Idempotent — safe to re-run.
#
# Usage:
#   sudo bash setup_mysql.sh
# Or with a custom password:
#   DB_PASS='something-secure' sudo bash setup_mysql.sh
set -euo pipefail

DB_NAME=${DB_NAME:-msc_shipping_tracking}
DB_USER=${DB_USER:-msc_shipping_app}
DB_PASS=${DB_PASS:-$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)}

mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'%' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'%';
FLUSH PRIVILEGES;
SQL

echo "Database: $DB_NAME"
echo "User:     $DB_USER"
echo "Password: $DB_PASS"
echo ""
echo "Add to /etc/toolbox/msc-shipping-tracking.env:"
echo "  SPRING_PROFILES_ACTIVE=prod"
echo "  SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/$DB_NAME?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
echo "  SPRING_DATASOURCE_USERNAME=$DB_USER"
echo "  SPRING_DATASOURCE_PASSWORD=$DB_PASS"
