package ocr.manager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ocr.common.Aws;
import ocr.common.Config;
import ocr.common.Messages;
import ocr.common.UserData;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * OWNER: Partner A.
 * Runs on EC2: java -jar manager.jar
 *
 * Thread "input":   reads MANAGER_INPUT_QUEUE, routes NEW_TASK to a thread pool, handles TERMINATE.
 * Thread pool:      each NEW_TASK -> download input, register job, send IMAGE_TASKs, launch workers.
 * Thread "results": reads WORKER_RESULTS_QUEUE, stores results per job, finishes jobs
 *                   (summary to S3 first, then JOB_DONE).
 */
public class Manager
{
	private static final String OWNER_TAG = "Owner";
	private static final int INPUT_VISIBILITY_SEC = 120;

	private static volatile boolean terminating = false;
	private static final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();
	private static final Set<String> finishedJobs = ConcurrentHashMap.newKeySet();
	private static final ExecutorService pool = Executors.newFixedThreadPool(4);

	/** This Manager's EC2 instance id, or null when running locally. */
	private static String selfId;

	public static void main(String[] args)
	{
		Aws aws = new Aws();
		String inputQueue = aws.queueUrl(Config.MANAGER_INPUT_QUEUE);
		String tasksQueue = aws.queueUrl(Config.WORKER_TASKS_QUEUE);
		String resultsQueue = aws.queueUrl(Config.WORKER_RESULTS_QUEUE);

		selfId = Ec2Metadata.instanceId();
		System.out.println("[Manager] up as " + (selfId == null ? "local process" : selfId)
				+ ". Queues: " + inputQueue + ", " + tasksQueue + ", " + resultsQueue);
		updateClientsTag(aws);

		new Thread(() -> resultsLoop(resultsQueue, aws), "results").start();
		receiveLoop(inputQueue, aws, tasksQueue);
	}

	// ======================= input queue =======================

	private static void receiveLoop(String inputQueue, Aws aws, String tasksQueue)
	{
		while (true)
		{
			List<Message> messages;
			try
			{
				messages = aws.receive(inputQueue, 10, INPUT_VISIBILITY_SEC);
			}
			catch (Exception e)
			{
				System.err.println("[Manager] receive failed: " + e.getMessage());
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
					System.err.println("[Manager] failed to handle message " + message.messageId() + ": " + e.getMessage());
				}
			}
		}
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
			// Runs in the pool so a big input file never blocks other clients.
			// The message is deleted only after the task was fully handled.
			pool.submit(() ->
			{
				try
				{
					handleNewTask(task, tasksQueue, aws);
					aws.deleteMessage(inputQueueUrl, currMsg);
				}
				catch (Exception e)
				{
					System.err.println("[Manager] NEW_TASK " + task.jobId + " failed, will retry: " + e.getMessage());
				}
			});
			return;
		}
		else if (parsed.type == Messages.Type.TERMINATE)
		{
			handleTerminate(aws);
		}
		else
		{
			System.err.println("[Manager] Unexpected message type " + parsed.type + ", deleting");
		}

		aws.deleteMessage(inputQueueUrl, currMsg);
	}

	private static void handleNewTask(Messages.NewTask task, String tasksQueue, Aws aws)
	{
		if (jobs.containsKey(task.jobId) || finishedJobs.contains(task.jobId))
		{
			System.out.println("[Manager] Job " + task.jobId + " already known, skipping");
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
		updateClientsTag(aws);

		if (urls.isEmpty())
		{
			finishJob(jobs.get(task.jobId), aws);
			return;
		}
		for (int index = 0; index < urls.size(); index++)
		{
			aws.send(tasksQueue, new Messages.ImageTask(task.jobId, index, urls.get(index)));
		}

		try
		{
			ensureWorkers(aws, urls.size(), task.filesPerWorker);
		}
		catch (Exception e)
		{
			System.err.println("[Manager] Could not launch workers: " + e.getMessage());
		}
	}

	// ======================= workers =======================

	/**
	 * The job needs ceil(images / filesPerWorker) workers. If k of ours are running, launch the
	 * missing ones, never going above Config.MAX_INSTANCES instances in total.
	 * synchronized: two jobs arriving together must not both launch the same missing workers.
	 */
	private static synchronized void ensureWorkers(Aws aws, int images, int filesPerWorker)
	{
		int perWorker = Math.max(1, filesPerWorker);
		int needed = (images + perWorker - 1) / perWorker;

		List<Instance> allWorkers = aws.instancesWithRole(Config.ROLE_WORKER);
		int mine = myWorkers(allWorkers).size();
		int managers = aws.instancesWithRole(Config.ROLE_MANAGER).size();
		int room = Config.MAX_INSTANCES - managers - allWorkers.size();

		int toLaunch = Math.min(needed - mine, room);
		if (toLaunch <= 0)
		{
			System.out.println("[Manager] Workers: need " + needed + ", have " + mine + ", room " + room + " - launching none");
			return;
		}

		List<String> ids = aws.launch(Config.ROLE_WORKER, UserData.worker(), toLaunch);
		if (selfId != null)
		{
			for (String id : ids)
			{
				aws.setTag(id, OWNER_TAG, selfId);
			}
		}
		System.out.println("[Manager] Launched " + ids.size() + " workers (need " + needed + ", had " + mine + ")");
	}

	/** Workers this Manager launched. Running locally (no selfId) = all workers. */
	private static List<Instance> myWorkers(List<Instance> allWorkers)
	{
		if (selfId == null)
		{
			return allWorkers;
		}
		List<Instance> mine = new ArrayList<>();
		for (Instance i : allWorkers)
		{
			if (selfId.equals(Aws.tags(i).get(OWNER_TAG)))
			{
				mine.add(i);
			}
		}
		return mine;
	}

	// ======================= results queue =======================

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
			if (!finishedJobs.contains(result.jobId) && otherManagersRunning(aws))
			{
				// Belongs to another Manager's job: give it back to the queue.
				aws.extendVisibility(resultsQueue, message, 5);
				return;
			}
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

	private static boolean otherManagersRunning(Aws aws)
	{
		if (selfId == null)
		{
			return false;
		}
		for (Instance m : aws.instancesWithRole(Config.ROLE_MANAGER))
		{
			if (!selfId.equals(m.instanceId()))
			{
				return true;
			}
		}
		return false;
	}

	/** Summary to S3 first, then JOB_DONE, then forget the job. */
	private static void finishJob(JobState job, Aws aws)
	{
		List<Messages.ImageResult> results = Arrays.asList(job.getResults());
		String key = Config.summaryKey(job.jobId);
		aws.uploadString(key, Messages.summaryToJson(results));
		try
		{
			aws.send(job.replyQueue, new Messages.JobDone(job.jobId, key));
		}
		catch (QueueDoesNotExistException e)
		{
			System.err.println("[Manager] Reply queue for job " + job.jobId + " no longer exists, dropping JOB_DONE");
		}

		finishedJobs.add(job.jobId);
		jobs.remove(job.jobId);
		System.out.println("[Manager] Job " + job.jobId + " done");
		updateClientsTag(aws);
	}

	// ======================= clients tag =======================

	/** Tells Local Apps how busy this Manager is (read by ManagerLocator). */
	private static void updateClientsTag(Aws aws)
	{
		if (selfId == null)
		{
			return;
		}
		try
		{
			aws.setTag(selfId, Config.ACTIVE_CLIENTS_TAG, terminating ? "Terminating" : String.valueOf(jobs.size()));
		}
		catch (Exception e)
		{
			System.err.println("[Manager] Could not update tag: " + e.getMessage());
		}
	}

	// ======================= terminate =======================

	private static synchronized void handleTerminate(Aws aws)
	{
		if (terminating)
		{
			System.out.println("[Manager] Terminate already in progress");
			return;
		}
		terminating = true;
		System.out.println("[Manager] Terminate received - no new tasks will be accepted");
		updateClientsTag(aws);
		new Thread(() -> shutdownWhenDone(aws), "shutdown").start();
	}

	/** Waits for all running jobs, then terminates our workers, then this instance. */
	private static void shutdownWhenDone(Aws aws)
	{
		try
		{
			pool.shutdown();
			pool.awaitTermination(1, TimeUnit.HOURS);
			while (!jobs.isEmpty())
			{
				System.out.println("[Manager] Waiting for " + jobs.size() + " jobs before shutdown");
				Thread.sleep(5000);
			}

			List<String> workerIds = new ArrayList<>();
			for (Instance w : myWorkers(aws.instancesWithRole(Config.ROLE_WORKER)))
			{
				workerIds.add(w.instanceId());
			}
			aws.terminate(workerIds);
			System.out.println("[Manager] Terminated " + workerIds.size() + " workers");

			if (selfId != null)
			{
				System.out.println("[Manager] Terminating myself (" + selfId + ")");
				aws.terminate(List.of(selfId));
			}
		}
		catch (Exception e)
		{
			System.err.println("[Manager] Shutdown error: " + e.getMessage());
		}
		System.exit(0);
	}
}