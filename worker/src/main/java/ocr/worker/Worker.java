package ocr.worker;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.Messages;
import ocr.common.Messages.ImageResult;
import ocr.common.Messages.ImageTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * OWNER: Partner B.
 * Runs on EC2: java -jar worker.jar
 * Takes ONE message at a time so no worker hoards work while others sit idle.
 */
public class Worker {

    public static void main(String[] args) {
        Aws aws = new Aws();
        String tasksQueue = aws.queueUrl(Config.WORKER_TASKS_QUEUE);
        String resultsQueue = aws.queueUrl(Config.WORKER_RESULTS_QUEUE);
        Ocr ocr = new Ocr();

        while (true) {
            for (Message m : aws.receive(tasksQueue, 1, Config.WORKER_VISIBILITY_TIMEOUT_SEC)) {
                // why: keep the message invisible while we work (Visibility Timeout), only while we're alive
                ScheduledFuture<?> hb = startHeartbeat(aws, tasksQueue, m);
                try {
                    ImageTask task = null;
                    ImageResult result;
                    try {
                        task = (ImageTask) Messages.parse(m.body());
                        String text = ocr.recognize(task.url);
                        result = ImageResult.ok(task, text);
                    } catch (Exception e) {
                        if (task == null) {           // unreadable message: drop it
                            aws.deleteMessage(tasksQueue, m);
                            continue;                 // finally below still cancels the heartbeat
                        }
                        result = ImageResult.failed(task, e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    aws.send(resultsQueue, result);
                    aws.deleteMessage(tasksQueue, m);  // only AFTER the result was sent
                } finally {
                    hb.cancel(false);                  // why: stop extending once we're done, no leaked timer
                }
            }
        }
    }

    // One background thread for every heartbeat. Daemon, so it never keeps the JVM alive.
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "visibility-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /** Every 30 s, tell SQS to keep hiding this message for another 60 s. */
    private static ScheduledFuture<?> startHeartbeat(Aws aws, String queueUrl, Message m) {
        return HEARTBEAT.scheduleAtFixedRate(() -> {
            try {
                aws.extendVisibility(queueUrl, m, Config.WORKER_VISIBILITY_TIMEOUT_SEC);
            } catch (Exception e) {
                // swallow so the schedule keeps running; the next tick retries
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
}