#!/bin/bash
# OWNER: Partner A (Tesseract install commands come from Partner B).
# Runs as root when a Worker instance boots. BUCKET is replaced by the launcher before sending.
# TODO(B -> A): exact Tesseract install commands + tessdata path
yum install -y java-17-amazon-corretto-headless || apt-get install -y openjdk-17-jre-headless
mkdir -p /opt/ocr && cd /opt/ocr
aws s3 cp s3://BUCKET/jars/worker.jar worker.jar
OCR_BUCKET=BUCKET java -jar worker.jar > worker.log 2>&1
