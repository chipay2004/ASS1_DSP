#!/bin/bash
# OWNER: Partner A. Runs as root when a Manager instance boots.
# TODO(A): if the AMI already has Java, drop the install line.
# BUCKET is replaced by the launcher before sending.
yum install -y java-17-amazon-corretto-headless || apt-get install -y openjdk-17-jre-headless
mkdir -p /opt/ocr && cd /opt/ocr
aws s3 cp s3://BUCKET/jars/manager.jar manager.jar
OCR_BUCKET=BUCKET java -jar manager.jar > manager.log 2>&1
