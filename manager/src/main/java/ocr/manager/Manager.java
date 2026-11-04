package ocr.manager;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.Messages;

/**
 * OWNER: Partner A.
 * Runs on EC2: java -jar manager.jar
 *
 * TODO(A):
 *  - Thread 1: read MANAGER_INPUT_QUEUE
 *      NEW_TASK  -> (in a thread pool) download input, send one ImageTask per line,
 *                   start workers: needed = ceil(lines / filesPerWorker), launch needed - running,
 *                   never above Config.MAX_INSTANCES; update ACTIVE_CLIENTS tag
 *      TERMINATE -> stop accepting NEW_TASK, finish running jobs, terminate workers, terminate self
 *  - Thread 2: read WORKER_RESULTS_QUEUE
 *      store each ImageResult per jobId (ignore duplicate index),
 *      when a job has all lines -> upload summary (Messages.summaryToJson, sorted by index),
 *      send JobDone to that job's replyQueueUrl
 */
public class Manager {

    public static void main(String[] args) {
        Aws aws = new Aws();
        String inputQueue = aws.queueUrl(Config.MANAGER_INPUT_QUEUE);
        String tasksQueue = aws.queueUrl(Config.WORKER_TASKS_QUEUE);
        String resultsQueue = aws.queueUrl(Config.WORKER_RESULTS_QUEUE);

        System.out.println("Manager up. Queues: " + inputQueue + ", " + tasksQueue + ", " + resultsQueue);
        // TODO(A): everything above
    }

    @SuppressWarnings("unused")
    private static void handle(Messages.Message m) {
        // TODO(A)
    }
}
