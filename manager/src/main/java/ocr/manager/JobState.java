package ocr.manager;

import ocr.common.Messages;

public class JobState
{
	public final String jobId;
	public final int numOfPics;
	public final String replyQueue;
	private final Messages.ImageResult[] results;
	private int received = 0;

	public JobState(String jobId, int numOfPics, String replyQueue)
	{
		this.jobId = jobId;
		this.numOfPics = numOfPics;
		this.replyQueue = replyQueue;
		this.results = new Messages.ImageResult[numOfPics];
	}

	public synchronized boolean addResult(Messages.ImageResult result)
	{
		if (result.index < 0 || result.index >= numOfPics)
		{
			return false;
		}
		if (results[result.index] != null)
		{
			return false;
		}
		results[result.index] = result;
		received++;
		return true;
	}

	public synchronized boolean isDone()
	{
		return received == numOfPics;
	}

	public synchronized Messages.ImageResult[] getResults()
	{
		return results.clone();
	}
}