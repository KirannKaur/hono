/*******************************************************************************
 * Copyright (c) 2021, 2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.hono.tests.client;

import static com.google.common.truth.Truth.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.eclipse.hono.client.kafka.consumer.HonoKafkaConsumer;
import org.eclipse.hono.tests.AssumeMessagingSystem;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.util.MessagingType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.kafka.admin.KafkaAdminClient;
import io.vertx.kafka.admin.NewTopic;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.KafkaProducerRecord;
import io.vertx.kafka.client.producer.RecordMetadata;

/**
 * Test cases verifying the behavior of {@link HonoKafkaConsumer}.
 * <p>
 * To run this on a specific Kafka cluster instance, set the
 * {@value IntegrationTestSupport#PROPERTY_DOWNSTREAM_BOOTSTRAP_SERVERS} system property,
 * e.g. <code>-Ddownstream.bootstrap.servers="PLAINTEXT://localhost:9092"</code>.
 */
@ExtendWith(VertxExtension.class)
@AssumeMessagingSystem(type = MessagingType.kafka)
public class HonoKafkaConsumerIT {

    private static final Logger LOG = LoggerFactory.getLogger(HonoKafkaConsumerIT.class);

    private static final short REPLICATION_FACTOR = 1;
    private static final String SMALL_TOPIC_SEGMENT_SIZE_BYTES = "120";

    private static Vertx vertx;
    private static KafkaAdminClient adminClient;
    private static KafkaProducer<String, Buffer> kafkaProducer;
    private static List<String> topicsToDeleteAfterTests;

    private HonoKafkaConsumer<Buffer> kafkaConsumer;

    private static Stream<String> partitionAssignmentStrategies() {
        return Stream.of(null, CooperativeStickyAssignor.class.getName());
    }

    /**
     * Sets up fixture.
     */
    @BeforeAll
    public static void init() {
        vertx = Vertx.vertx();
        topicsToDeleteAfterTests = new ArrayList<>();

        final Map<String, String> adminClientConfig = IntegrationTestSupport.getKafkaAdminClientConfig()
                .getAdminClientConfig("test");
        adminClient = KafkaAdminClient.create(vertx, adminClientConfig);
        final Map<String, String> producerConfig = IntegrationTestSupport.getKafkaProducerConfig()
                .getProducerConfig("test");
        producerConfig.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, SMALL_TOPIC_SEGMENT_SIZE_BYTES);
        producerConfig.put(ProducerConfig.BATCH_SIZE_CONFIG, SMALL_TOPIC_SEGMENT_SIZE_BYTES);
        kafkaProducer = KafkaProducer.create(vertx, producerConfig);
    }

    /**
     * Cleans up fixture.
     *
     * @param ctx The vert.x test context.
     */
    @AfterAll
    public static void shutDown(final VertxTestContext ctx) {
        final Promise<Void> producerClosePromise = Promise.promise();
        kafkaProducer.close(producerClosePromise);

        final Promise<Void> topicsDeletedPromise = Promise.promise();
        adminClient.deleteTopics(topicsToDeleteAfterTests, topicsDeletedPromise);
        topicsDeletedPromise.future()
                .recover(thr -> {
                    LOG.info("error deleting topics", thr);
                    return Future.succeededFuture();
                })
                .compose(ar -> producerClosePromise.future())
                .onComplete(ar -> {
                    topicsToDeleteAfterTests.clear();
                    topicsToDeleteAfterTests = null;
                    adminClient.close();
                    adminClient = null;
                    kafkaProducer = null;
                    vertx.close();
                    vertx = null;
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    /**
     * Closes a Kafka consumer created during the test.
     *
     * @param ctx The vert.x test context.
     */
    @AfterEach
    void closeConsumer(final VertxTestContext ctx) {
        if (kafkaConsumer != null) {
            kafkaConsumer.stop().onComplete(ctx.succeedingThenComplete());
        }
    }

    /**
     * Verifies that a HonoKafkaConsumer configured with "latest" as offset reset strategy only receives
     * records published after the consumer <em>start()</em> method has completed.
     *
     * @param partitionAssignmentStrategy The partition assignment strategy to use for the consumer.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @ParameterizedTest
    @MethodSource("partitionAssignmentStrategies")
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testConsumerReadsLatestRecordsPublishedAfterStart(
            final String partitionAssignmentStrategy,
            final VertxTestContext ctx) throws InterruptedException {
        final int numTopics = 2;
        final int numPartitions = 5;
        final int numTestRecordsPerTopic = 20;

        final Set<String> topics = IntStream.range(0, numTopics)
                .mapToObj(i -> "test_" + i + "_" + UUID.randomUUID())
                .collect(Collectors.toSet());
        final String publishTestTopic = topics.iterator().next();

        final VertxTestContext setup = new VertxTestContext();
        createTopics(topics, numPartitions)
                .compose(v -> publishRecords(numTestRecordsPerTopic, "key_", topics))
                .onComplete(setup.succeedingThenComplete());

        assertThat(setup.awaitCompletion(IntegrationTestSupport.getTestSetupTimeout(), TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
            return;
        }
        LOG.debug("topics created and (to be ignored) test records published");

        // prepare consumer
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("test");
        applyPartitionAssignmentStrategy(consumerConfig, partitionAssignmentStrategy);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        final String publishedAfterStartRecordKey = "publishedAfterStartKey";
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            // verify received record
            ctx.verify(() -> assertThat(record.key()).isEqualTo(publishedAfterStartRecordKey));
            ctx.completeNow();
        };
        kafkaConsumer = new HonoKafkaConsumer<>(vertx, topics, recordHandler, consumerConfig);
        // start consumer
        final Promise<Void> readyTracker = Promise.promise();
        kafkaConsumer.addOnKafkaConsumerReadyHandler(readyTracker);
        kafkaConsumer.start()
            .compose(ok -> readyTracker.future())
            .onComplete(ctx.succeeding(v -> {
                LOG.debug("consumer started, publish record to be received by the consumer");
                publish(publishTestTopic, publishedAfterStartRecordKey, Buffer.buffer("testPayload"));
            }));

        if (!ctx.awaitCompletion(9, TimeUnit.SECONDS)) {
            ctx.failNow(new IllegalStateException("timeout waiting for record to be received"));
        }
    }

    /**
     * Verifies that a HonoKafkaConsumer configured with "latest" as offset reset strategy will receive
     * all still available records after the committed offset position has gone out of range
     * (because records have been deleted according to the retention config) and the consumer is restarted.
     *
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testConsumerReadsLatestRecordsPublishedAfterOutOfRangeOffsetReset(final VertxTestContext ctx)
            throws InterruptedException {

        final int numTopics = 1;
        final int numTestRecordsPerTopicPerRound = 20;
        final int numPartitions = 1; // has to be 1 here because we expect partition 0 to contain *all* the records published for a topic

        // prepare topics
        final Set<String> topics = IntStream.range(0, numTopics)
                .mapToObj(i -> "test_" + i + "_" + UUID.randomUUID())
                .collect(Collectors.toSet());
        final String publishTestTopic = topics.iterator().next();

        final VertxTestContext setup = new VertxTestContext();
        final Map<String, String> topicsConfig = Map.of(
                TopicConfig.RETENTION_MS_CONFIG, "300",
                TopicConfig.SEGMENT_BYTES_CONFIG, SMALL_TOPIC_SEGMENT_SIZE_BYTES);
        createTopics(topics, numPartitions, topicsConfig)
                .onComplete(setup.succeedingThenComplete());

        assertThat(setup.awaitCompletion(IntegrationTestSupport.getTestSetupTimeout(), TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
            return;
        }

        // prepare consumer
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("test");
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        final VertxTestContext firstConsumerInstanceStartedAndStopped = new VertxTestContext();
        final List<KafkaConsumerRecord<String, Buffer>> receivedRecords = new ArrayList<>();

        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            receivedRecords.add(record);
            if (receivedRecords.size() == numTestRecordsPerTopicPerRound * topics.size()) {
                LOG.trace("first round of records received; stop consumer; committed offset afterwards shall be {}", numTestRecordsPerTopicPerRound);
                kafkaConsumer.stop()
                        .onFailure(ctx::failNow)
                        .onSuccess(v2 -> {
                            LOG.trace("publish 2nd round of records (shall be deleted before the to-be-restarted consumer is able to receive them)");
                            publishRecords(numTestRecordsPerTopicPerRound, "round2_", topics)
                                    .onFailure(ctx::failNow)
                                    .onSuccess(v3 -> {
                                        LOG.trace("wait until records of first two rounds have been deleted according to the retention policy (committed offset will be out-of-range then)");
                                        final int beginningOffsetToWaitFor = numTestRecordsPerTopicPerRound * 2;
                                        waitForLogDeletion(new TopicPartition(publishTestTopic, 0), beginningOffsetToWaitFor, Duration.ofSeconds(5))
                                                .onComplete(firstConsumerInstanceStartedAndStopped
                                                        .succeedingThenComplete());
                                    });
                        });
            }
        };

        kafkaConsumer = new HonoKafkaConsumer<>(vertx, topics, recordHandler, consumerConfig);
        // first start of consumer, letting it commit offsets
        final Promise<Void> readyTracker = Promise.promise();
        kafkaConsumer.addOnKafkaConsumerReadyHandler(readyTracker);
        kafkaConsumer.start()
            .compose(ok -> readyTracker.future())
            .onComplete(ctx.succeeding(v -> {
                LOG.trace("consumer started, publish first round of records to be received by the consumer (so that it has offsets to commit)");
                publishRecords(numTestRecordsPerTopicPerRound, "round1_", topics);
            }));

        assertThat(firstConsumerInstanceStartedAndStopped.awaitCompletion(
                IntegrationTestSupport.getTestSetupTimeout(),
                TimeUnit.SECONDS))
            .isTrue();
        if (firstConsumerInstanceStartedAndStopped.failed()) {
            ctx.failNow(firstConsumerInstanceStartedAndStopped.causeOfFailure());
            return;
        }

        // preparation done, now start same consumer again and verify it reads all still available records - even though committed offset is out-of-range now
        receivedRecords.clear();

        final String lastRecordKey = "lastKey";
        // restarted consumer is expected to receive 3rd round of records + one extra record published after consumer start
        final int expectedNumberOfRecords = (numTestRecordsPerTopicPerRound * topics.size()) + 1;
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler2 = record -> {
            receivedRecords.add(record);
            if (receivedRecords.size() == expectedNumberOfRecords) {
                ctx.verify(() -> {
                    assertThat(receivedRecords.get(0).key()).startsWith("round3");
                    assertThat(receivedRecords.get(receivedRecords.size() - 1).key()).isEqualTo(lastRecordKey);
                });
                ctx.completeNow();
            }
        };
        LOG.trace("publish 3nd round of records (shall be received by to-be-restarted consumer)");
        publishRecords(numTestRecordsPerTopicPerRound, "round3_", topics)
                .onFailure(ctx::failNow)
                .onSuccess(v -> {
                    final Promise<Void> newReadyTracker = Promise.promise();
                    kafkaConsumer = new HonoKafkaConsumer<>(vertx, topics, recordHandler2, consumerConfig);
                    kafkaConsumer.addOnKafkaConsumerReadyHandler(newReadyTracker);
                    kafkaConsumer.start()
                        .compose(ok -> newReadyTracker.future())
                        .onComplete(ctx.succeeding(v2 -> {
                            LOG.debug("consumer started, publish another record to be received by the consumer");
                            publish(publishTestTopic, lastRecordKey, Buffer.buffer("testPayload"));
                        }));
                });

        if (!ctx.awaitCompletion(9, TimeUnit.SECONDS)) {
            ctx.failNow(new IllegalStateException(String.format(
                    "timeout waiting for expected number of records (%d) to be received; received records: %d",
                    expectedNumberOfRecords, receivedRecords.size())));
        }
    }

    private Future<Void> waitForLogDeletion(
            final TopicPartition topicPartition,
            final long beginningOffsetToWaitFor,
            final Duration maxWaitingTime) {

        final Instant deadline = Instant.now().plus(maxWaitingTime);
        final KafkaConsumer<String, Buffer> deletionCheckKafkaConsumer = KafkaConsumer.create(
                vertx, IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("deletionCheck"));
        final Promise<Void> resultPromise = Promise.promise();
        doRepeatedBeginningOffsetCheck(deletionCheckKafkaConsumer, topicPartition, beginningOffsetToWaitFor,
                deadline, resultPromise);
        return resultPromise.future()
                .onComplete(ignore -> deletionCheckKafkaConsumer.close());
    }

    private void doRepeatedBeginningOffsetCheck(
            final KafkaConsumer<String, Buffer> kafkaConsumer,
            final TopicPartition topicPartition,
            final long beginningOffsetToWaitFor,
            final Instant deadline,
            final Promise<Void> resultPromise) {

        kafkaConsumer.beginningOffsets(topicPartition)
                .onFailure(resultPromise::tryFail)
                .onSuccess(beginningOffset -> {
                    final int nextCheckDelayMillis = 300;
                    if (beginningOffset >= beginningOffsetToWaitFor) {
                        LOG.debug("done waiting for log deletion; beginningOffset of [{}] is now {}",
                                topicPartition, beginningOffset);
                        resultPromise.complete();
                    } else if (Instant.now().minus(Duration.ofMillis(nextCheckDelayMillis)).isAfter(deadline)) {
                        resultPromise.tryFail("""
                                timeout checking for any deleted records; make sure the topic log retention \
                                and the broker 'log.retention.check.interval.ms' is configured according to the \
                                test requirements
                                """);
                    } else {
                        vertx.setTimer(nextCheckDelayMillis, tid -> {
                            LOG.debug("continue waiting for log deletion; beginning offset ({}) hasn't reached {} yet",
                                    beginningOffset, beginningOffsetToWaitFor);
                            doRepeatedBeginningOffsetCheck(kafkaConsumer, topicPartition, beginningOffsetToWaitFor,
                                    deadline, resultPromise);
                        });
                    }
                });
    }

    /**
     * Verifies that a HonoKafkaConsumer configured with "latest" as offset reset strategy and a topic pattern
     * subscription only receives records published after the consumer <em>start()</em> method has completed.
     * <p>
     * Also verifies that all records published after the consumer <em>ensureTopicIsAmongSubscribedTopicPatternTopics()</em>
     * method has completed are received by the consumer, also if the topic was only created after the consumer
     * <em>start</em> method has completed.
     *
     * @param partitionAssignmentStrategy The partition assignment strategy to use for the consumer.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @ParameterizedTest
    @MethodSource("partitionAssignmentStrategies")
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testConsumerReadsLatestRecordsPublishedAfterTopicSubscriptionConfirmed(
            final String partitionAssignmentStrategy, final VertxTestContext ctx) throws InterruptedException {
        final String patternPrefix = "test_" + UUID.randomUUID() + "_";
        final int numTopics = 2;
        final Pattern topicPattern = Pattern.compile(Pattern.quote(patternPrefix) + ".*");
        final int numPartitions = 5;
        final int numTestRecordsPerTopic = 20;

        final Set<String> topics = IntStream.range(0, numTopics)
                .mapToObj(i -> patternPrefix + i)
                .collect(Collectors.toSet());

        final VertxTestContext setup = new VertxTestContext();
        createTopics(topics, numPartitions)
                .compose(v -> publishRecords(numTestRecordsPerTopic, "key_", topics))
                .onComplete(setup.succeedingThenComplete());

        assertThat(setup.awaitCompletion(IntegrationTestSupport.getTestSetupTimeout(), TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
            return;
        }
        LOG.debug("topics created and (to be ignored) test records published");

        // prepare consumer
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("test");
        applyPartitionAssignmentStrategy(consumerConfig, partitionAssignmentStrategy);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        final AtomicReference<Promise<Void>> nextRecordReceivedPromiseRef = new AtomicReference<>();
        final List<KafkaConsumerRecord<String, Buffer>> receivedRecords = new ArrayList<>();
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            receivedRecords.add(record);
            Optional.ofNullable(nextRecordReceivedPromiseRef.get())
                    .ifPresent(Promise::complete);
        };
        kafkaConsumer = new HonoKafkaConsumer<>(vertx, topicPattern, recordHandler, consumerConfig);
        // start consumer
        final Promise<Void> readyTracker = Promise.promise();
        kafkaConsumer.addOnKafkaConsumerReadyHandler(readyTracker);
        kafkaConsumer.start()
            .compose(ok -> readyTracker.future())
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    assertThat(receivedRecords.size()).isEqualTo(0);
                });
                final Promise<Void> nextRecordReceivedPromise = Promise.promise();
                nextRecordReceivedPromiseRef.set(nextRecordReceivedPromise);

                LOG.debug("consumer started, create new topic implicitly by invoking ensureTopicIsAmongSubscribedTopicPatternTopics()");
                final String newTopic = patternPrefix + "new";
                final String recordKey = "addedAfterStartKey";
                kafkaConsumer.ensureTopicIsAmongSubscribedTopicPatternTopics(newTopic)
                        .onComplete(ctx.succeeding(v2 -> {
                            LOG.debug("publish record to be received by the consumer");
                            publish(newTopic, recordKey, Buffer.buffer("testPayload"));
                        }));

                nextRecordReceivedPromise.future().onComplete(ar -> {
                    ctx.verify(() -> {
                        assertThat(receivedRecords.size()).isEqualTo(1);
                        assertThat(receivedRecords.get(0).key()).isEqualTo(recordKey);
                    });
                    ctx.completeNow();
                });
            }));
    }

    /**
     * Verifies that a HonoKafkaConsumer configured with "latest" as offset reset strategy and a topic pattern
     * subscription receives records published after multiple <em>ensureTopicIsAmongSubscribedTopicPatternTopics()</em>
     * invocations have been completed.
     *
     * @param partitionAssignmentStrategy The partition assignment strategy to use for the consumer.
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @ParameterizedTest
    @MethodSource("partitionAssignmentStrategies")
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testConsumerReadsAllRecordsForDynamicallyCreatedTopics(
            final String partitionAssignmentStrategy, final VertxTestContext ctx) throws InterruptedException {
        final String patternPrefix = "test_" + UUID.randomUUID() + "_";
        final int numTopicsAndRecords = 3;
        final Pattern topicPattern = Pattern.compile(Pattern.quote(patternPrefix) + ".*");

        // prepare consumer
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("test");
        applyPartitionAssignmentStrategy(consumerConfig, partitionAssignmentStrategy);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        final Promise<Void> allRecordsReceivedPromise = Promise.promise();
        final List<KafkaConsumerRecord<String, Buffer>> receivedRecords = new ArrayList<>();
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            receivedRecords.add(record);
            if (receivedRecords.size() == numTopicsAndRecords) {
                allRecordsReceivedPromise.complete();
            }
        };

        kafkaConsumer = new HonoKafkaConsumer<>(vertx, topicPattern, recordHandler, consumerConfig);
        // start consumer
        final Promise<Void> readyTracker = Promise.promise();
        kafkaConsumer.addOnKafkaConsumerReadyHandler(readyTracker);
        kafkaConsumer.start()
            .compose(ok -> readyTracker.future())
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    assertThat(receivedRecords.size()).isEqualTo(0);
                });
                LOG.debug("consumer started, create new topics implicitly by invoking ensureTopicIsAmongSubscribedTopicPatternTopics()");
                final String recordKey = "addedAfterStartKey";
                for (int i = 0; i < numTopicsAndRecords; i++) {
                    final String topic = patternPrefix + i;
                    kafkaConsumer.ensureTopicIsAmongSubscribedTopicPatternTopics(topic)
                            .onComplete(ctx.succeeding(v2 -> {
                                LOG.debug("publish record to be received by the consumer");
                                publish(topic, recordKey, Buffer.buffer("testPayload"));
                            }));
                }
                allRecordsReceivedPromise.future().onComplete(ar -> {
                    ctx.verify(() -> {
                        assertThat(receivedRecords.size()).isEqualTo(numTopicsAndRecords);
                        receivedRecords.forEach(record -> assertThat(record.key()).isEqualTo(recordKey));
                    });
                    ctx.completeNow();
                });
            }));
        if (!ctx.awaitCompletion(9, TimeUnit.SECONDS)) {
            ctx.failNow(new IllegalStateException(String.format(
                    "timeout waiting for expected number of records (%d) to be received; received records: %d",
                    numTopicsAndRecords, receivedRecords.size())));
        }
    }

    /**
     * Verifies that a HonoKafkaConsumer configured with "earliest" as offset reset strategy receives all
     * current records after the consumer <em>start()</em> method has completed.
     *
     * @param ctx The vert.x test context.
     * @throws InterruptedException if test execution gets interrupted.
     */
    @Test
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testConsumerReadsAllRecordsAfterStart(final VertxTestContext ctx) throws InterruptedException {
        final int numTopics = 2;
        final int numPartitions = 5;
        final int numTestRecordsPerTopic = 20;

        final Set<String> topics = IntStream.range(0, numTopics)
                .mapToObj(i -> "test_" + i + "_" + UUID.randomUUID())
                .collect(Collectors.toSet());

        final VertxTestContext setup = new VertxTestContext();
        createTopics(topics, numPartitions)
                .compose(v -> publishRecords(numTestRecordsPerTopic, "key_", topics))
                .onComplete(setup.succeedingThenComplete());

        assertThat(setup.awaitCompletion(IntegrationTestSupport.getTestSetupTimeout(), TimeUnit.SECONDS)).isTrue();
        if (setup.failed()) {
            ctx.failNow(setup.causeOfFailure());
            return;
        }
        LOG.debug("topics created and test records published");

        // prepare consumer
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("test");
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        final Promise<Void> allRecordsReceivedPromise = Promise.promise();
        final List<KafkaConsumerRecord<String, Buffer>> receivedRecords = new ArrayList<>();
        final int totalExpectedMessages = numTopics * numTestRecordsPerTopic;
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            receivedRecords.add(record);
            if (receivedRecords.size() == totalExpectedMessages) {
                allRecordsReceivedPromise.complete();
            }
        };
        kafkaConsumer = new HonoKafkaConsumer<>(vertx, topics, recordHandler, consumerConfig);
        // start consumer
        final Promise<Void> readyTracker = Promise.promise();
        kafkaConsumer.addOnKafkaConsumerReadyHandler(readyTracker);
        kafkaConsumer.start()
            .compose(ok -> readyTracker.future())
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    assertThat(receivedRecords.size()).isEqualTo(0);
                });
                allRecordsReceivedPromise.future().onComplete(ar -> {
                    ctx.verify(() -> {
                        assertThat(receivedRecords.size()).isEqualTo(totalExpectedMessages);
                    });
                    ctx.completeNow();
                });
            }));
    }

    /**
     * Verifies that a HonoKafkaConsumer that is using a not yet existing topic and that is configured with
     * "latest" as offset reset strategy, only receives records on the auto-created topic published after the consumer
     * <em>start()</em> method has completed.
     *
     * @param partitionAssignmentStrategy The partition assignment strategy to use for the consumer.
     * @param ctx The vert.x test context.
     */
    @ParameterizedTest
    @MethodSource("partitionAssignmentStrategies")
    @Timeout(value = 10, timeUnit = TimeUnit.SECONDS)
    public void testConsumerAutoCreatesTopicAndReadsLatestRecordsPublishedAfterStart(
            final String partitionAssignmentStrategy, final VertxTestContext ctx) {

        // prepare consumer
        final Map<String, String> consumerConfig = IntegrationTestSupport.getKafkaConsumerConfig().getConsumerConfig("test");
        applyPartitionAssignmentStrategy(consumerConfig, partitionAssignmentStrategy);
        consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        final AtomicReference<Promise<Void>> nextRecordReceivedPromiseRef = new AtomicReference<>();
        final List<KafkaConsumerRecord<String, Buffer>> receivedRecords = new ArrayList<>();
        final Handler<KafkaConsumerRecord<String, Buffer>> recordHandler = record -> {
            receivedRecords.add(record);
            Optional.ofNullable(nextRecordReceivedPromiseRef.get())
                    .ifPresent(Promise::complete);
        };
        final String topic = "test_" + UUID.randomUUID();
        topicsToDeleteAfterTests.add(topic);
        kafkaConsumer = new HonoKafkaConsumer<>(vertx, Set.of(topic), recordHandler, consumerConfig);
        // start consumer
        final Promise<Void> readyTracker = Promise.promise();
        kafkaConsumer.addOnKafkaConsumerReadyHandler(readyTracker);
        kafkaConsumer.start()
            .compose(ok -> readyTracker.future())
            .onComplete(ctx.succeeding(v -> {
                ctx.verify(() -> {
                    assertThat(receivedRecords.size()).isEqualTo(0);
                });
                final Promise<Void> nextRecordReceivedPromise = Promise.promise();
                nextRecordReceivedPromiseRef.set(nextRecordReceivedPromise);

                LOG.debug("consumer started, publish record to be received by the consumer");
                final String recordKey = "addedAfterStartKey";
                publish(topic, recordKey, Buffer.buffer("testPayload"));

                nextRecordReceivedPromise.future().onComplete(ar -> {
                    ctx.verify(() -> {
                        assertThat(receivedRecords.size()).isEqualTo(1);
                        assertThat(receivedRecords.get(0).key()).isEqualTo(recordKey);
                    });
                    ctx.completeNow();
                });
            }));
    }

    private static Future<Void> createTopics(final Collection<String> topicNames, final int numPartitions) {
        return createTopics(topicNames, numPartitions, Map.of());
    }

    private static Future<Void> createTopics(final Collection<String> topicNames, final int numPartitions,
            final Map<String, String> topicConfig) {
        topicsToDeleteAfterTests.addAll(topicNames);
        final Promise<Void> resultPromise = Promise.promise();
        final List<NewTopic> topics = topicNames.stream()
                .map(t -> new NewTopic(t, numPartitions, REPLICATION_FACTOR).setConfig(topicConfig))
                .collect(Collectors.toList());
        adminClient.createTopics(topics, resultPromise);
        return resultPromise.future();
    }

    private Future<Void> publishRecords(final int numTestRecordsPerTopic, final String keyPrefix, final Set<String> topics) {
        @SuppressWarnings("rawtypes")
        final List<Future> resultFutures = new ArrayList<>();
        topics.forEach(topic -> {
            resultFutures.add(publishRecords(numTestRecordsPerTopic, keyPrefix, topic));
        });
        return CompositeFuture.all(resultFutures).map((Void) null);
    }

    private Future<Void> publishRecords(final int numRecords, final String keyPrefix, final String topic) {
        @SuppressWarnings("rawtypes")
        final List<Future> resultFutures = new ArrayList<>();
        IntStream.range(0, numRecords).forEach(i -> {
            resultFutures.add(publish(topic, keyPrefix + i, Buffer.buffer("testPayload")).mapEmpty());
        });
        return CompositeFuture.all(resultFutures).map((Void) null);
    }

    private static Future<RecordMetadata> publish(final String topic, final String recordKey, final Buffer recordPayload) {
        final Promise<RecordMetadata> resultPromise = Promise.promise();
        final KafkaProducerRecord<String, Buffer> record = KafkaProducerRecord.create(topic, recordKey, recordPayload);
        kafkaProducer.send(record, resultPromise);
        return resultPromise.future();
    }

    private void applyPartitionAssignmentStrategy(final Map<String, String> consumerConfig,
            final String partitionAssignmentStrategy) {
        Optional.ofNullable(partitionAssignmentStrategy)
                .ifPresent(s -> consumerConfig.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, s));
    }
}

