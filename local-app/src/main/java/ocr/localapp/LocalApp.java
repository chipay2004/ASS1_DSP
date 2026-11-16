package ocr.localapp;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.ManagerLocator;
import ocr.common.Messages;
import ocr.common.Messages.ImageResult;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * OWNER: Partner B.
 * Usage: java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
 */
public class LocalApp {

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5 || (args.length == 5 && !args[4].equals("terminate"))) {
            System.err.println("usage: java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]");
            System.exit(1);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int managerClientsN = Integer.parseInt(args[2]);
        int workerFilesN = Integer.parseInt(args[3]);
        boolean terminate = args.length == 5;

        Aws aws = new Aws();
        String jobId = UUID.randomUUID().toString();

        // 1. Own reply queue
        String replyQueue = aws.queueUrl(Config.REPLY_QUEUE_PREFIX + jobId);

        // 2. Make sure a Manager is available (A's code)
        ManagerLocator locator = new ManagerLocator(aws);
        if (locator.findAvailableManager(managerClientsN).isEmpty()) {
            locator.launchManager();
        }

        // 3. Upload input + send NEW_TASK
        aws.ensureBucket();
        aws.uploadFile(Config.inputKey(jobId), input);
        String managerQueue = aws.queueUrl(Config.MANAGER_INPUT_QUEUE);
        aws.send(managerQueue, new Messages.NewTask(jobId, Config.inputKey(jobId), replyQueue, workerFilesN));

        // 4. Wait for JOB_DONE
        // Local App never talks to the Manager directly. It waits on its own queue.
        String summaryKey = null;
        while (summaryKey == null) {
            // receive() long-polls up to 20 s, so this loop waits, no busy spinning
            for (software.amazon.awssdk.services.sqs.model.Message m : aws.receive(replyQueue, 1, 0)) {
                Messages.Message msg = Messages.parse(m.body());
                if (msg instanceof Messages.JobDone done && done.jobId.equals(jobId)) {
                    summaryKey = done.summaryKey;   // the summary file's location on S3 
                }
                aws.deleteMessage(replyQueue, m);  
            }
        }
    

        // 5. Download summary + write HTML
        List<ImageResult> results = Messages.summaryFromJson(aws.downloadString(summaryKey));
        HtmlWriter.write(results, output);

        // 6. Terminate mode
        if (terminate) {
            aws.send(managerQueue, new Messages.Terminate());
        }

        // 7. Cleanup
        aws.deleteQueue(replyQueue);
    }
}
