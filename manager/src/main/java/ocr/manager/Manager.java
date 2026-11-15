package ocr.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.Messages;
import ocr.common.Messages.ImageResult;
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


    private static volatile boolean terminating = false;
	private static final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Aws aws = new Aws();
        String inputQueue = aws.queueUrl(Config.MANAGER_INPUT_QUEUE);
        String tasksQueue = aws.queueUrl(Config.WORKER_TASKS_QUEUE);
        String resultsQueue = aws.queueUrl(Config.WORKER_RESULTS_QUEUE);

        System.out.println("Manager up. Queues: " + inputQueue + ", " + tasksQueue + ", " + resultsQueue);
        new Thread(() -> resultsLoop(resultsQueue, aws)).start();
        receiveLoop(inputQueue, aws, tasksQueue);
        
    }

    @SuppressWarnings("unused")
    private static void handle(Messages.Message m) 
    {
        
    }

	private static void dispatch(Message currMsg, Aws aws, String inputQueueUrl, String tasksQueue)
	{
		Messages.Message parsed;
		try
		{
			parsed = Messages.parse(currMsg.body());
		}
		catch (Exception e)
		{
			System.err.println("[Manager] Broken message, deleting: " + currMsg.body());
			aws.deleteMessage(inputQueueUrl, currMsg);
			return;
		}

		if (parsed.type == Messages.Type.NEW_TASK)
		{
			Messages.NewTask task = (Messages.NewTask) parsed;
			if (terminating)
			{
				System.out.println("[Manager] Terminating - left NEW_TASK " + task.jobId + " in queue for the next Manager");
				return;
			}
			handleNewTask(task, tasksQueue, aws);
		}
		else if (parsed.type == Messages.Type.TERMINATE)
		{
			handleTerminate();
		}
		else
		{
			System.err.println("[Manager] Unexpected message type " + parsed.type + ", deleting");
		}

		aws.deleteMessage(inputQueueUrl, currMsg);
	}


    private static void handleTerminate() {
        terminating = true;
    }



	private static void handleNewTask(Messages.NewTask task, String tasksQueue, Aws aws)
	{
		if (jobs.containsKey(task.jobId))
		{
			System.out.println("[Manager] Job " + task.jobId + " already registered, skipping");
			return;
		}
		String file = aws.downloadString(task.inputKey);
		List<String> urls = new ArrayList<>();
		for (String line : file.split("\\R"))
		{
			String url = line.trim();
			if (!url.isEmpty())
			{
				urls.add(url);
			}
		}
		jobs.put(task.jobId, new JobState(task.jobId, urls.size(), task.replyQueueUrl));
		System.out.println("[Manager] Job " + task.jobId + " registered with " + urls.size() + " images");
        if (urls.isEmpty())
		{
			finishJob(jobs.get(task.jobId), aws);
			return;
		}
		for (int index = 0; index < urls.size(); index++)
		{
			aws.send(tasksQueue, new Messages.ImageTask(task.jobId, index, urls.get(index)));
		}
	}
    private static void resultsLoop(String resultsQueue, Aws aws)
	{
		while (true)
		{
			List<Message> messages;
			try
			{
				messages = aws.receive(resultsQueue, 10, 0);
			}
			catch (Exception e)
			{
				System.err.println("[Manager] results receive failed: " + e.getMessage());
				continue;
			}
			for (Message message : messages)
			{
				try
				{
					handleResult(message, aws, resultsQueue);
				}
				catch (Exception e)
				{
					System.err.println("[Manager] failed to handle result " + message.messageId() + ": " + e.getMessage());
				}
			}
		}
	}
    	private static void handleResult(Message message, Aws aws, String resultsQueue)
	{
		Messages.Message parsed;
		try
		{
			parsed = Messages.parse(message.body());
		}
		catch (Exception e)
		{
			System.err.println("[Manager] Broken result, deleting: " + message.body());
			aws.deleteMessage(resultsQueue, message);
			return;
		}

		if (parsed.type != Messages.Type.IMAGE_RESULT)
		{
			System.err.println("[Manager] Unexpected type in results queue " + parsed.type + ", deleting");
			aws.deleteMessage(resultsQueue, message);
			return;
		}

		Messages.ImageResult result = (Messages.ImageResult) parsed;
		JobState job = jobs.get(result.jobId);
		if (job == null)
		{
			System.out.println("[Manager] Result for unknown or finished job " + result.jobId + ", deleting");
			aws.deleteMessage(resultsQueue, message);
			return;
		}

		if (!job.addResult(result))
		{
			System.out.println("[Manager] Duplicate result " + result.jobId + " #" + result.index);
		}

		if (job.isDone())
		{
			finishJob(job, aws);
		}

		aws.deleteMessage(resultsQueue, message);
	}

	private static void finishJob(JobState job, Aws aws)
	{
        Messages.ImageResult[] results = job.getResults();
        List<Messages.ImageResult> newResults = Arrays.asList(results);
        String json = Messages.summaryToJson(newResults);
        String key = Config.summaryKey(job.jobId);
        aws.uploadString(key, json);
        aws.send(job.replyQueue, new Messages.JobDone(job.jobId, key));


		System.out.println("[Manager] Job " + job.jobId + " done");
		jobs.remove(job.jobId);
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
