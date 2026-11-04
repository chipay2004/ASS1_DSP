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
