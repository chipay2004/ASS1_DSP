package ocr.common;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Thin wrappers over S3, SQS and EC2. Owned by BOTH partners.
 * Credentials come from the default chain: ~/.aws/credentials locally, the instance role on EC2.
 * Never put keys in code.
 */
public final class Aws {
    public final S3Client s3;
    public final SqsClient sqs;
    public final Ec2Client ec2;

    public Aws() {
        this.s3 = S3Client.builder().region(Config.REGION).build();
        this.sqs = SqsClient.builder().region(Config.REGION).build();
        this.ec2 = Ec2Client.builder().region(Config.REGION).build();
    }

    // ================= S3 =================

    public void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(Config.BUCKET).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(Config.BUCKET).build());
        }
    }

    public void uploadFile(String key, Path file) {
        s3.putObject(PutObjectRequest.builder().bucket(Config.BUCKET).key(key).build(), RequestBody.fromFile(file));
    }

    public void uploadString(String key, String content) {
        s3.putObject(PutObjectRequest.builder().bucket(Config.BUCKET).key(key).build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    public String downloadString(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(Config.BUCKET).key(key).build())
                .asString(StandardCharsets.UTF_8);
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(Config.BUCKET).key(key).build());
    }

    // ================= SQS =================

    /** Creates the queue if missing; returns its URL either way. */
    public String queueUrl(String name) {
        return sqs.createQueue(CreateQueueRequest.builder().queueName(name).build()).queueUrl();
    }

    public void send(String queueUrl, Messages.Message msg) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(msg.toJson()).build());
    }

    /** Long-polls up to max messages (1..10). visibilitySec <= 0 = use the queue default. */
    public List<Message> receive(String queueUrl, int max, int visibilitySec) {
        ReceiveMessageRequest.Builder b = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(max)
                .waitTimeSeconds(Config.LONG_POLL_SEC);
        if (visibilitySec > 0) b.visibilityTimeout(visibilitySec);
        return sqs.receiveMessage(b.build()).messages();
    }

    public void deleteMessage(String queueUrl, Message m) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(m.receiptHandle()).build());
    }

    /** Keep a message hidden longer while still working on it. */
    public void extendVisibility(String queueUrl, Message m, int seconds) {
        sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl).receiptHandle(m.receiptHandle()).visibilityTimeout(seconds).build());
    }

    public void deleteQueue(String queueUrl) {
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }

    // ================= EC2 =================

    /** Running or pending instances with Role=<role>. */
    public List<Instance> instancesWithRole(String role) {
        DescribeInstancesRequest req = DescribeInstancesRequest.builder().filters(
                Filter.builder().name("tag:" + Config.ROLE_TAG).values(role).build(),
                Filter.builder().name("instance-state-name").values("pending", "running").build()
        ).build();
        return ec2.describeInstances(req).reservations().stream()
                .flatMap(r -> r.instances().stream()).toList();
    }

    /** Launch count instances tagged Role=<role>, running userDataScript (plain text, encoded here). */
    public List<String> launch(String role, String userDataScript, int count) {
        RunInstancesRequest req = RunInstancesRequest.builder()
                .imageId(Config.AMI_ID)
                .instanceType(Config.INSTANCE_TYPE)
                .minCount(count).maxCount(count)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name(Config.INSTANCE_PROFILE).build())
                .userData(Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8)))
                .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.INSTANCE)
                        .tags(Tag.builder().key(Config.ROLE_TAG).value(role).build())
                        .build())
                .build();
        return ec2.runInstances(req).instances().stream().map(Instance::instanceId).toList();
    }

    public void setTag(String instanceId, String key, String value) {
        ec2.createTags(CreateTagsRequest.builder().resources(instanceId)
                .tags(Tag.builder().key(key).value(value).build()).build());
    }

    public void terminate(List<String> instanceIds) {
        if (instanceIds.isEmpty()) return;
        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceIds).build());
    }

    public static Map<String, String> tags(Instance i) {
        return i.tags().stream().collect(java.util.stream.Collectors.toMap(Tag::key, Tag::value, (a, b) -> a));
    }
}
