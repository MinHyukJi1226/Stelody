#!/bin/sh
set -eu
umask 077
# This is the public Supabase root CA, not a client key or password.
: "${DB_CA_CERTIFICATE_BASE64:?Set the base64-encoded Supabase root certificate}"
ca_file=$(mktemp /tmp/stelody-db-ca.XXXXXX)
trap 'rm -f "$ca_file"' EXIT
printf '%s' "$DB_CA_CERTIFICATE_BASE64" | base64 -d > "$ca_file"
mv -f "$ca_file" /tmp/stelody-db-ca.crt
trap - EXIT
unset DB_CA_CERTIFICATE_BASE64
exec java -XX:MaxRAMPercentage=55 -XX:MaxDirectMemorySize=32m \
  -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 \
  -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError -jar /opt/stelody/app.jar
