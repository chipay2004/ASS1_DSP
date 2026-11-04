package ocr.common;

import software.amazon.awssdk.regions.Region;

/**
 * Every name the components share. Owned by BOTH partners: change only after agreeing.
 */
public final class Config {
    private Config() {}

    // ---- AWS ----
    public static final Region REGION = Region.US_EAST_1;

    /** Bucket for everything this project puts on S3. Override with env var OCR_BUCKET. */
    public static final String BUCKET = env("OCR_BUCKET", "ocr-assignment-CHANGE-ME");

    // ---- SQS queues ----
    /** Local App -> Manager: NEW_TASK and TERMINATE. */
    public static final String MANAGER_INPUT_QUEUE = "ocr-manager-input";
    /** Manager -> Workers: one IMAGE_TASK per URL. */
    public static final String WORKER_TASKS_QUEUE = "ocr-worker-tasks";
    /** Workers -> Manager: one IMAGE_RESULT per URL. */
    public static final String WORKER_RESULTS_QUEUE = "ocr-worker-results";
    /** Manager -> one Local App: JOB_DONE. Each Local App creates its own: prefix + jobId. */
    public static final String REPLY_QUEUE_PREFIX = "ocr-reply-";

    /** Seconds a worker owns a message before it returns to the queue. */
    public static final int WORKER_VISIBILITY_TIMEOUT_SEC = 60;
    /** Long polling wait for every receive. */
    public static final int LONG_POLL_SEC = 20;

    // ---- S3 keys ----
    public static String inputKey(String jobId)   { return "inputs/" + jobId + ".txt"; }
    public static String summaryKey(String jobId) { return "summaries/" + jobId + ".json"; }
    public static final String MANAGER_JAR_KEY = "jars/manager.jar";
    public static final String WORKER_JAR_KEY  = "jars/worker.jar";

    // ---- EC2 ----
    public static final String ROLE_TAG = "Role";
    public static final String ROLE_MANAGER = "Manager";
    public static final String ROLE_WORKER = "Worker";
    /** Tag the Manager keeps updated with how many clients it serves now. */
    public static final String ACTIVE_CLIENTS_TAG = "ActiveClients";

    /** AWS blocks students above this. Manager + workers together. */
    public static final int MAX_INSTANCES = 19;

    public static final String AMI_ID = env("OCR_AMI", "ami-CHANGE-ME");
    public static final String INSTANCE_TYPE = "t2.micro";
    /** AWS Academy role: gives the instance credentials, so none go in the jar. */
    public static final String INSTANCE_PROFILE = "LabInstanceProfile";

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
