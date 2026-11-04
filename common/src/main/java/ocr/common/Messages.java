package ocr.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The 5 message types that travel over SQS, as JSON with a "type" field.
 * Owned by BOTH partners: change only after agreeing.
 *
 *   Local App -> Manager : NewTask, Terminate      (queue: MANAGER_INPUT_QUEUE)
 *   Manager   -> Workers : ImageTask               (queue: WORKER_TASKS_QUEUE)
 *   Workers   -> Manager : ImageResult             (queue: WORKER_RESULTS_QUEUE)
 *   Manager   -> Local   : JobDone                 (queue: the client's reply queue)
 */
public final class Messages {
    private Messages() {}

    private static final Gson GSON = new Gson();

    public enum Type { NEW_TASK, TERMINATE, IMAGE_TASK, IMAGE_RESULT, JOB_DONE }

    public abstract static class Message {
        public final Type type;
        protected Message(Type type) { this.type = type; }
        public String toJson() { return GSON.toJson(this); }
    }

    /** A Local App asks the Manager to process an input file. */
    public static final class NewTask extends Message {
        public String jobId;
        public String inputKey;        // S3 key of the URL list
        public String replyQueueUrl;   // where to send JobDone
        public int filesPerWorker;     // worker-files-n
        public NewTask() { super(Type.NEW_TASK); }
        public NewTask(String jobId, String inputKey, String replyQueueUrl, int filesPerWorker) {
            this();
            this.jobId = jobId; this.inputKey = inputKey;
            this.replyQueueUrl = replyQueueUrl; this.filesPerWorker = filesPerWorker;
        }
    }

    /** A Local App tells the Manager to finish current jobs and shut down. */
    public static final class Terminate extends Message {
        public Terminate() { super(Type.TERMINATE); }
    }

    /** One image for a worker. index = line number in the input file (0-based), used to detect duplicates. */
    public static final class ImageTask extends Message {
        public String jobId;
        public int index;
        public String url;
        public ImageTask() { super(Type.IMAGE_TASK); }
        public ImageTask(String jobId, int index, String url) {
            this(); this.jobId = jobId; this.index = index; this.url = url;
        }
    }

    /** A worker's answer for one image. Exactly one of text / error is non-null. */
    public static final class ImageResult extends Message {
        public String jobId;
        public int index;
        public String url;
        public String text;
        public String error;
        public ImageResult() { super(Type.IMAGE_RESULT); }
        public static ImageResult ok(ImageTask t, String text) {
            ImageResult r = base(t); r.text = text; return r;
        }
        public static ImageResult failed(ImageTask t, String error) {
            ImageResult r = base(t); r.error = error; return r;
        }
        private static ImageResult base(ImageTask t) {
            ImageResult r = new ImageResult(); r.jobId = t.jobId; r.index = t.index; r.url = t.url; return r;
        }
    }

    /** The Manager tells one Local App that its summary is on S3. */
    public static final class JobDone extends Message {
        public String jobId;
        public String summaryKey;
        public JobDone() { super(Type.JOB_DONE); }
        public JobDone(String jobId, String summaryKey) { this(); this.jobId = jobId; this.summaryKey = summaryKey; }
    }

    /** Parse any message body into its concrete class. */
    public static Message parse(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        Type type = Type.valueOf(obj.get("type").getAsString());
        Class<? extends Message> cls = switch (type) {
            case NEW_TASK -> NewTask.class;
            case TERMINATE -> Terminate.class;
            case IMAGE_TASK -> ImageTask.class;
            case IMAGE_RESULT -> ImageResult.class;
            case JOB_DONE -> JobDone.class;
        };
        return GSON.fromJson(obj, cls);
    }

    // ---- Summary file (S3, written by Manager, read by Local App) ----

    /** JSON array of ImageResult, sorted by index, one per input line. */
    public static String summaryToJson(java.util.List<ImageResult> results) {
        return GSON.toJson(results);
    }

    public static java.util.List<ImageResult> summaryFromJson(String json) {
        return java.util.Arrays.asList(GSON.fromJson(json, ImageResult[].class));
    }
}
