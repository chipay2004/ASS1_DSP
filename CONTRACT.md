# Contract — what both partners share

Everything here lives in `common/` (`Config.java`, `Messages.java`, `Aws.java`).
**Change any of it only after telling your partner.**

## 1. Queues (SQS)

| Queue | From → To | Messages |
|---|---|---|
| `ocr-manager-input` | Local App → Manager | `NEW_TASK`, `TERMINATE` |
| `ocr-worker-tasks` | Manager → Workers | `IMAGE_TASK` |
| `ocr-worker-results` | Workers → Manager | `IMAGE_RESULT` |
| `ocr-reply-<jobId>` | Manager → one Local App | `JOB_DONE` |

Each Local App creates its own reply queue and deletes it at the end.
Worker visibility timeout: 60 s (`Config.WORKER_VISIBILITY_TIMEOUT_SEC`). Every receive uses 20 s long polling.

## 2. Messages (JSON, field `type`)

```json
{"type":"NEW_TASK","jobId":"…","inputKey":"inputs/<jobId>.txt","replyQueueUrl":"…","filesPerWorker":5}
{"type":"TERMINATE"}
{"type":"IMAGE_TASK","jobId":"…","index":3,"url":"http://…"}
{"type":"IMAGE_RESULT","jobId":"…","index":3,"url":"http://…","text":"…","error":null}
{"type":"JOB_DONE","jobId":"…","summaryKey":"summaries/<jobId>.json"}
```

- `index` = line number in the input file (from 0). The Manager uses `jobId + index` to drop duplicate results.
- `IMAGE_RESULT`: exactly one of `text` / `error` is set. `error` = short description of the exception.

## 3. S3 (one bucket, `OCR_BUCKET`)

| Key | Written by | Read by |
|---|---|---|
| `inputs/<jobId>.txt` | Local App | Manager |
| `summaries/<jobId>.json` | Manager | Local App |
| `jars/manager.jar`, `jars/worker.jar` | `scripts/upload-jars.sh` | instances at boot |

Summary file = JSON array of `IMAGE_RESULT`, sorted by `index`, one per input line.
Delete the whole bucket at the end to clean up.

## 4. EC2

- Tag `Role=Manager` / `Role=Worker` on every instance.
- The Manager keeps tag `ActiveClients=<n>` updated; the Local App reads it to decide if the Manager is available.
- At most 19 instances in total (Manager included).
- Instances get credentials from the IAM role `LabInstanceProfile`. **No keys in code or jars.**
- Region: `us-east-1`.

## 5. Who owns what

| Partner A | Partner B |
|---|---|
| `manager/` | `worker/` |
| `common/ManagerLocator.java` | `local-app/` |
| `scripts/*-userdata.sh`, AMI | Tesseract install commands → A |

Shared: `Config.java`, `Messages.java`, `Aws.java`, this file, README.
