#!/bin/bash
# Build and upload the Manager and Worker jars so new instances can download them.
# Usage: OCR_BUCKET=<bucket> ./scripts/upload-jars.sh
set -e
: "${OCR_BUCKET:?set OCR_BUCKET}"
mvn -q package -DskipTests
aws s3 cp manager/target/manager.jar "s3://$OCR_BUCKET/jars/manager.jar"
aws s3 cp worker/target/worker.jar   "s3://$OCR_BUCKET/jars/worker.jar"
