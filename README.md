# Assignment 1: OCR in the Cloud

Names / IDs: TODO

## Project layout

```
common/     shared contract: Config, Messages, Aws helper, ManagerLocator   (see CONTRACT.md)
local-app/  Local Application                                               (Partner B)
manager/    Manager                                                         (Partner A)
worker/     Worker + OCR                                                    (Partner B)
scripts/    user-data scripts, jar upload                                   (Partner A)
```

## Setup (each partner, once)

1. Java 17+ and Maven.
2. AWS Academy credentials in `~/.aws/credentials` (never in the repo). Region `us-east-1`.
3. Pick a bucket name and export it: `export OCR_BUCKET=<your-bucket>` (each partner can use their own while developing).

## Build

```
mvn package
```

Produces `local-app/target/local-app.jar`, `manager/target/manager.jar`, `worker/target/worker.jar`.

## Run

```
java -jar local-app/target/local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
```

## Git workflow

- Work on your own branch (`a-manager`, `b-worker-localapp`), merge to `main` via pull request.
- Anything in `common/` → tell your partner before merging.

## Required README sections (fill before submitting)

- How the program works
- Instance type + AMI: TODO
- Run time on the sample input: TODO, with n = TODO
- Security, scalability, persistence / failures, threads, termination

## How the program works

### Visibility timeout and failures (Partner B)

When a Worker receives a task, SQS hides it from all other Workers for 60 s (the visibility timeout). The timeout is 60 s because one image normally takes a few seconds (download plus OCR), so 60 s is longer than normal processing, and short enough that the work of a dead Worker comes back in about a minute.

A slow image does not get stolen. While the Worker processes a message, a small background thread extends the timeout by another 60 s every 30 s (ChangeMessageVisibility). The message is deleted only after the result was sent to the results queue.

If a Worker dies (instance terminated, crash), its background thread dies with it, nothing extends the timeout any more, the message becomes visible again within 60 s, and another Worker takes it. Nothing is lost. The cost is that an image can be processed twice if the Worker dies after sending the result but before deleting the message. This is SQS at-least-once delivery, and the Manager removes duplicates using jobId and index, so a second result for the same image is harmless (idempotency).

Errors on a single image (404, dead host, not an image, timeout, OCR error) are not retried. They are reported as an IMAGE_RESULT with an error, because retrying a deterministic failure would loop for nothing. Downloads have a 10 s connect and 30 s read timeout, so a slow server cannot block a Worker for long.

Limitation: the heartbeat does not know whether the OCR call itself is stuck. A Worker that hangs inside Tesseract would keep renewing its message. We did not add a cap because it did not occur in our tests. We also did not configure a dead-letter queue; one would stop an image that crashes every Worker from being redelivered forever.

### Worker (Partner B)

The Worker is one loop. It waits on the tasks queue with long polling (up to 20 s per call, so an empty queue costs almost nothing), takes one message, downloads the image, runs Tesseract, sends an IMAGE_RESULT to the results queue, and only then deletes the task message.

If one image fails (404, dead host, not an image, OCR error), the Worker catches the exception and sends an IMAGE_RESULT with an error text instead of crashing, so one bad URL never stops the batch. A message that is not valid JSON is deleted and skipped. Redirects (including http to https) are followed by hand, up to 5 hops.

Why one message at a time: a message that a Worker has received is hidden from all other Workers until it is deleted. A Worker that took 10 messages would keep 9 of them hidden while it worked on the first, other Workers would sit idle, and if it died all 10 would wait for the timeout. One message at a time spreads the work evenly across the Workers and limits a crash to one image.

Why no threads inside a Worker: the Tesseract object is not thread-safe, so each Worker runs one OCR at a time. Parallelism comes from running many Workers on many instances, which the Manager controls through n. The only extra thread is a small background thread that renews the message's visibility timeout (see the failures section).
