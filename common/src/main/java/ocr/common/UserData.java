package ocr.common;

/**
 * OWNER: Partner A.
 * Boot scripts for EC2 instances (Ubuntu 24.04). Each runs once, as root, when the instance starts.
 * Credentials come from LabInstanceProfile, so no keys appear here.
 */
public final class UserData
{
	private UserData() {}

	public static String manager()
	{
		return """
				#!/bin/bash
				apt-get update -y
				apt-get install -y openjdk-17-jre-headless
				snap install aws-cli --classic
				mkdir -p /opt/ocr && cd /opt/ocr
				/snap/bin/aws s3 cp s3://{{BUCKET}}/jars/manager.jar manager.jar
				export OCR_BUCKET={{BUCKET}}
				java -jar manager.jar > manager.log 2>&1
				""".replace("{{BUCKET}}", Config.BUCKET);
	}

	public static String worker()
	{
		return """
				#!/bin/bash
				apt-get update -y
				apt-get install -y openjdk-17-jre-headless tesseract-ocr libtesseract-dev
				snap install aws-cli --classic
				mkdir -p /opt/ocr && cd /opt/ocr
				/snap/bin/aws s3 cp s3://{{BUCKET}}/jars/worker.jar worker.jar
				export TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata
				export OCR_BUCKET={{BUCKET}}
				java -jar worker.jar > worker.log 2>&1
				""".replace("{{BUCKET}}", Config.BUCKET);
	}
}
