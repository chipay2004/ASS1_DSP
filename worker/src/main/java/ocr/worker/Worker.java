package ocr.worker;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.Messages;
import ocr.common.Messages.ImageResult;
import ocr.common.Messages.ImageTask;
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
                ImageTask task = null;
                ImageResult result;
                try {
                    task = (ImageTask) Messages.parse(m.body());
                    String text = ocr.recognize(task.url);   // TODO(B): extend visibility for slow images
                    result = ImageResult.ok(task, text);
                } catch (Exception e) {
                    if (task == null) {           // unreadable message: drop it
                        aws.deleteMessage(tasksQueue, m);
                        continue;
                    }
                    result = ImageResult.failed(task, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                aws.send(resultsQueue, result);
                aws.deleteMessage(tasksQueue, m);  // only AFTER the result was sent
            }
        }
    }
}
