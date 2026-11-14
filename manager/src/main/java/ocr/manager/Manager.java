package ocr.manager;

import java.util.List;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.Messages;
import ocr.common.Messages.NewTask;
import software.amazon.awssdk.services.sqs.model.Message;

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
    private static void handle(Messages.Message m) 
    {
        
    }

    private static void dispatch(Message currMsg, Aws aws, String inputQueueUrl, String tasksQueue)
    {   Messages.Message parsed;
        try 
        {
           parsed = Messages.parse(currMsg.body());    
        } 
        catch (Exception e)
        {
            aws.deleteMessage(inputQueueUrl, currMsg);
            System.err.println("Broken json");
            return;
        }
        
        if(parsed.type == Messages.Type.NEW_TASK)
        {
            handleNewTask((Messages.NewTask)parsed, tasksQueue, aws);
        }
        else if(parsed.type == Messages.Type.TERMINATE)
        {
            handleTerminate();
        }

        aws.deleteMessage(inputQueueUrl, currMsg);
    }
    private static void handleNewTask(Messages.NewTask parsed, String tasksQueue, Aws aws) 
    {
        if(aws.parsed.jobId
    }

    private static void receiveLoop(String inputQueue, Aws aws, String tasksQueue)
    {
        while (true)
        {
            List<Message> messages;
            try
            {
                messages = aws.receive(inputQueue, 10, 0);
            }
            catch (Exception e)
            {
                System.err.println("receive failed: " + e.getMessage());
                continue;
            }
            for (Message message : messages)
            {
                try
                {
                    dispatch(message, aws, inputQueue, tasksQueue);
                }
                catch (Exception e)
                {
                    System.err.println("failed to handle message " + message.messageId() + ": " + e.getMessage());
                }
            }
        }
    }
    
}
