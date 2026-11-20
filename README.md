# Assignment 1: OCR in the Cloud

Names:
Amit Frenkel 
ID: 212757991

Yanir Davidov
ID: 325913721

## Project layout

```
common/     shared contract: Config, Messages, Aws helper, ManagerLocator, UserData   (see CONTRACT.md)
local-app/  Local Application                                                         (Partner B)
manager/    Manager                                                                   (Partner A)
worker/     Worker + OCR                                                              (Partner B)
```

## Instances and run time

| | |
|---|---|
| AMI | `ami-08b275ac6cc5dbefb` (Ubuntu Server 24.04 LTS, x86, us-east-1) |
| Instance type | `t2.micro` for the Manager and the Workers |
| Sample input (24 images), n = 5 | about 3 minutes, from a cold start (5 Workers) |
| Sample input (24 images), n = 10 | 1 min 40 s, with the Manager already running (3 Workers) |

The time is measured on the Local App from start to the HTML file, with `Measure-Command`. Part of the sample input's URLs no longer exist on the internet, so the output shows an error line for them (FileNotFoundException / UnknownHostException), which is the required behavior.

## How to run

1. Java 17+ and Maven. AWS Academy credentials in `~/.aws/credentials` (never in the repo), region `us-east-1`.
2. Set the bucket name in `Config.BUCKET` (or `export OCR_BUCKET=<bucket>`), and create it once: `aws s3 mb s3://<bucket>`.
3. Build from scratch and upload the two jars that the EC2 instances download when they boot:
   ```
   mvn clean package
   aws s3 cp manager/target/manager.jar s3://<bucket>/jars/manager.jar
   aws s3 cp worker/target/worker.jar   s3://<bucket>/jars/worker.jar
   ```
   Always use `clean`: the instances run Java 17, and a class left over from a Java 21 build (for example by the IDE) makes the Manager crash at start with `UnsupportedClassVersionError`.
4. Run:
   ```
   java -jar local-app/target/local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
   ```
   The Local App starts the Manager if needed, and the Manager starts the Workers. Nothing has to be launched by hand.

## How the program works

Three kinds of processes, which only talk through SQS queues and S3:

```
Local App --NEW_TASK / TERMINATE--> ocr-manager-input --> Manager
Manager   --IMAGE_TASK (one per URL)--> ocr-worker-tasks --> Workers
Workers   --IMAGE_RESULT--> ocr-worker-results --> Manager
Manager   --JOB_DONE--> ocr-reply-<jobId> (one per Local App run) --> Local App
S3: inputs/<jobId>.txt, summaries/<jobId>.json, jars/manager.jar, jars/worker.jar
```

### Local App (Partner B)

Usage: `java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]`

Each run gets a random jobId (UUID) and creates its own reply queue named after it. The Local App then asks the Manager locator for a running Manager that serves fewer than manager-clients-n clients, and starts one if there is none. It uploads the input file to S3 (`inputs/<jobId>.txt`) and sends a NEW_TASK message to the Manager's input queue, with the S3 key, the reply queue URL and worker-files-n.

Then it waits on its reply queue with long polling. When the Manager's JOB_DONE message arrives, it carries only the S3 key of the summary. The Local App downloads the summary, writes the HTML file (one paragraph per image: the image, a line break, the recognized text; failed images show the URL and the error; all text is escaped), and deletes the message. If terminate was given, it sends a TERMINATE message to the Manager after writing the HTML. At the end it deletes its reply queue.

Why a private reply queue per run: several Local Apps can run at the same time, and each one only ever sees its own answer. We tested two in parallel and answered one of them: only that one finished, the other kept waiting. Why the queue carries only a key: the summary can be large, so it stays on S3 and the message points to it. The Local App never talks to the Manager directly, only through queues and S3, so either side can be restarted without the other knowing.

Limitation: if a Local App is killed before it finishes, its reply queue is not deleted. AWS Academy credentials also expire after a few hours, and a run that outlives them fails with a 403 on the next call.

### Finding or starting a Manager (Partner A)

`ManagerLocator` lists the instances tagged `Role=Manager` that are pending or running, and reads their `ActiveClients` tag. A Manager whose number is below manager-clients-n is available. A Manager that is shutting down has the tag value `Terminating`, so it is never chosen. If no Manager is available, the Local App launches a new one and sets its tag to 0 at once, so a client that arrives a few seconds later does not start another one.

Tags are used because the Local App and the Manager have no direct channel; the tag is a small notice board on the instance that the Manager writes and every Local App can read. As the assignment allows, we assume two Local Apps never try to start a Manager at the exact same moment.

### Manager (Partner A)

The Manager runs these threads:

- **Input thread.** Long-polls `ocr-manager-input`, up to 10 messages per call (it is the only reader, so it does not take work from anyone). It only parses each message and routes it: NEW_TASK goes to the thread pool, TERMINATE starts the shutdown. A broken message or a message of the wrong type is logged and deleted, because retrying it would never help.
- **Thread pool (4 threads) for NEW_TASK.** Each task downloads the input file from S3, drops empty lines, registers the job, sends one IMAGE_TASK per URL (jobId, index, URL) and makes sure there are enough Workers. The NEW_TASK message is deleted only after all of this succeeded; if something fails (S3 not answering), the message comes back after its visibility timeout (120 s for this queue) and is handled again.
- **Results thread.** Long-polls `ocr-worker-results`, adds each IMAGE_RESULT to its job, and finishes a job when all its results are in.
- **Shutdown thread.** Started by TERMINATE (see below).

**Job tracking.** Jobs live in a `ConcurrentHashMap` from jobId to `JobState`, because the pool threads add jobs while the results thread updates and removes them. `JobState` holds the number of images, the reply queue and an array of results indexed by `index`. A job is registered *before* its IMAGE_TASKs are sent, so a fast Worker can never return a result for a job the Manager does not know yet. The array makes duplicates easy to drop (the slot is already full), keeps the summary in input order, and a counter tells when the job is done without scanning the array. Its methods are `synchronized`.

**Finishing a job.** The Manager uploads `summaries/<jobId>.json` to S3 and only then sends JOB_DONE, so the Local App can always download the summary right after the message. Then the job is removed from the map and remembered in a set of finished jobs. If the client's reply queue no longer exists (the client left), the JOB_DONE is dropped and the job is closed anyway; otherwise the result message would come back forever. A file with no URLs is finished at once with an empty summary.

**Starting Workers.** A job needs ceil(images / n) Workers. The Manager counts the Workers it started itself (they carry an `Owner=<manager instance id>` tag) and launches only the missing ones (m - k), never going above 19 instances in total, Manager(s) included. This method is `synchronized`, so two jobs that arrive together do not both launch the same missing Workers. The Manager finds its own instance id from the EC2 instance metadata service (IMDSv2); when it runs on a laptop there is no id, and the EC2-only parts (tags, self-termination) are skipped, which is how we tested it locally.

**More than one Manager.** With a small manager-clients-n, a second Local App can start a second Manager. All Managers share the same queues, so a Manager may receive a result of another Manager's job. If the job is unknown, not in its finished set, and other Managers are running, it puts the message back on the queue (visibility 5 s) for the right Manager instead of deleting it.

**Duplicates (at-least-once).** SQS may deliver a message twice, and a Worker may die after sending a result but before deleting its task. The Manager ignores a NEW_TASK whose jobId it already knows and an IMAGE_RESULT whose index already arrived, so handling something twice is harmless.

**What the Manager does not do.** It never downloads an image, runs OCR or builds HTML. It only splits input files into tasks and collects results into summaries; the heavy work is on the Workers and the HTML on the Local App.

### Termination (Partner A)

When a TERMINATE message arrives, the Manager:

1. Stops accepting new jobs: it sets its tag to `Terminating` and leaves any later NEW_TASK in the queue, so the next Manager that a Local App starts will handle it and no client is lost.
2. Lets the thread pool finish the NEW_TASKs it already started, then waits until every open job has finished and its JOB_DONE was sent. So a client that is still running gets its answer even if another client asked to terminate.
3. Terminates the Workers it started (by the `Owner` tag), then terminates its own instance and exits.

A second TERMINATE is ignored. Tested with two Local Apps in parallel where only the second one sent terminate: both got their HTML, and only then all instances went to terminated.

### Visibility timeout and failures (Partner B)

When a Worker receives a task, SQS hides it from all other Workers for 60 s (the visibility timeout). The timeout is 60 s because one image normally takes a few seconds (download plus OCR), so 60 s is longer than normal processing, and short enough that the work of a dead Worker comes back in about a minute.

A slow image does not get stolen. While the Worker processes a message, a small background thread extends the timeout by another 60 s every 30 s (ChangeMessageVisibility). The message is deleted only after the result was sent to the results queue.

If a Worker dies (instance terminated, crash), its background thread dies with it, nothing extends the timeout any more, the message becomes visible again within 60 s, and another Worker takes it. Nothing is lost. The cost is that an image can be processed twice if the Worker dies after sending the result but before deleting the message. This is SQS at-least-once delivery, and the Manager removes duplicates using jobId and index, so a second result for the same image is harmless (idempotency).

Errors on a single image (404, dead host, not an image, timeout, OCR error) are not retried. They are reported as an IMAGE_RESULT with an error, because retrying a deterministic failure would loop for nothing. Downloads have a 10 s connect and 30 s read timeout, so a slow server cannot block a Worker for long.

Limitation: the heartbeat stops renewing a message after 5 minutes, so an image that hangs a Worker is handed to another Worker. The hung Worker itself stays stuck until its instance is terminated. We did not configure a dead-letter queue; one would stop an image that hangs or crashes every Worker from being redelivered forever.

Tests: during an 80-image job we killed the only running Worker with Ctrl+C. After the timeout we started a second Worker; it processed the remaining images, and the summary contained all 80 results. On EC2, we terminated one of the Workers in the middle of the sample input job: the other Worker took over its messages and the output had all 24 lines.

On EC2 we also ran a 480-image job on 2 Workers and terminated one of them in the middle of it. The other Worker took over its messages after the visibility timeout, and the summary had all 480 results; the job only took longer.

### Worker (Partner B)

The Worker is one loop. It waits on the tasks queue with long polling (up to 20 s per call, so an empty queue costs almost nothing), takes one message, downloads the image, runs Tesseract, sends an IMAGE_RESULT to the results queue, and only then deletes the task message.

If one image fails (404, dead host, not an image, OCR error), the Worker catches the exception and sends an IMAGE_RESULT with an error text instead of crashing, so one bad URL never stops the batch. A message that is not valid JSON is deleted and skipped. Redirects (including http to https) are followed by hand, up to 5 hops.

Why one message at a time: a message that a Worker has received is hidden from all other Workers until it is deleted. A Worker that took 10 messages would keep 9 of them hidden while it worked on the first, other Workers would sit idle, and if it died all 10 would wait for the timeout. One message at a time spreads the work evenly across the Workers and limits a crash to one image. (The Manager, in contrast, takes 10 at a time, because it is the only reader of its queues.)

Why no threads inside a Worker: the Tesseract object is not thread-safe, so each Worker runs one OCR at a time. Parallelism comes from running many Workers on many instances, which the Manager controls through n. The only extra thread is a small background thread that renews the message's visibility timeout (see the failures section).

### Bootstrapping (Partner A)

Every instance starts from a clean Ubuntu 24.04 AMI and receives a user-data script (`UserData.java`), which cloud-init runs once, as root, at first boot: it installs Java 17 (and Tesseract on Workers) and the AWS CLI, downloads the Manager or Worker jar from S3 and runs it. The Java output is copied to the console, so it can be read with EC2 "Get system log" without logging in to the instance. The scripts are Java strings and not files, because the Manager itself runs on EC2 and launches the Workers, and there is no project folder there.

We chose user-data over a custom AMI: an instance needs about a minute more to install packages, but a code change only needs a new jar on S3, never a new AMI. All instances start from the same AMI and script, so all Workers are identical and any of them can replace another.

## Threads: when they help and when not

They help where a process waits on the network for independent things: the Manager's input and results threads let new clients and finished images be handled at the same time, and the pool lets several NEW_TASKs be split in parallel, so one large input file never makes other clients wait. The pool is fixed (4 threads) and not a thread per request, so a burst of clients cannot exhaust the memory of a t2.micro; extra requests wait in the pool's queue, and more clients are absorbed by more Managers (manager-clients-n).

They do not help inside a Worker: OCR is CPU-bound and Tesseract is not thread-safe, so a Worker does one image at a time and we scale with more Workers instead. The Local App is single-threaded because it only waits for one answer.

## Security

There are no credentials in the code, the jars, the user-data scripts or the repository. The Local App uses the AWS credentials of the person who runs it (`~/.aws/credentials`, which `.gitignore` keeps out of Git). The EC2 instances get temporary credentials from the Lab instance profile (`LabInstanceProfile`), which the SDK and the AWS CLI pick up by themselves. This matters because user-data is not secret: anyone on the instance or in the EC2 console can read it, so it contains only the bucket name. The Manager reads its instance id through IMDSv2, which requires a session token. The S3 bucket is private: the Local App reads the summary through the SDK with its own credentials, so no file is made public.

Everything that comes from the internet is treated as untrusted. The URLs and the OCR text are HTML-escaped before they go into the output page, and a Worker only decodes the downloaded bytes as an image; it never runs anything it downloaded. Limitation: downloads have time limits but no size limit, so a very large file could use up the memory of a Worker.

## Scalability

Neither the Local App nor the Worker keeps state that another machine would need. Each Local App run has its own jobId and its own reply queue, so any number of Local Apps can run at the same time without seeing each other's messages. Workers take one message at a time from one shared queue, so more images means more Workers; the Manager starts them according to the number of images and n. Messages carry only a URL or an S3 key and never the data itself (the claim check pattern), so message size stays small however big the input is. SQS and S3 are managed services that grow with the load.

The Manager is the part that scales least, since it keeps its open jobs in memory. Its load is bounded per instance (a fixed thread pool and at most manager-clients-n clients), and more clients start more Managers. For a million clients the real limit is the account: at most 19 instances, so beyond that a huge load makes the Workers work longer instead of starting more of them.

## Persistence and failures

- **A Worker dies or stalls:** its message comes back after the visibility timeout and another Worker handles it (see above).
- **A message is delivered twice:** jobId and index make every step idempotent.
- **A bad URL or a broken message:** reported as an error line, or logged and deleted; it never stops a job.
- **The Manager fails while handling a NEW_TASK:** the message is not deleted, so it is handled again.
- **The client disappears:** its JOB_DONE is dropped and the job is closed.

Limitations we know of: open jobs live only in the Manager's memory, so if a Manager instance dies, its clients wait forever and its Workers keep running until terminated by hand. If one IMAGE_TASK send fails in the middle of a job, the retried NEW_TASK is skipped as a duplicate and that job never completes. If all Workers of a job die, no new Workers are started until the next NEW_TASK. Idle Workers are not stopped before TERMINATE. Fixes would be storing job state in S3 or DynamoDB, a heartbeat that restarts a dead Manager, and re-checking the Worker count periodically.

## Is anything waiting for something else?

No component waits for a specific other component. The Local App waits only on its own reply queue; the Manager's input thread never waits for a job, because the work is in the pool; the results thread handles results in whatever order they arrive; Workers never know which job or client an image belongs to, and any Worker can take any image. Even termination does not block new results: the shutdown thread waits on its own while the other threads keep working.