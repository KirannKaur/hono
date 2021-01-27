/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.eclipse.hono.client.kafka.consumer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.eclipse.hono.util.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.kafka.client.common.TopicPartition;
import io.vertx.kafka.client.consumer.KafkaConsumer;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;
import io.vertx.kafka.client.consumer.KafkaConsumerRecords;
import io.vertx.kafka.client.consumer.OffsetAndMetadata;

/**
 * A client to consume data from a Kafka cluster.
 * <p>
 * This consumer continuously polls for batches of messages from Kafka. Each message is passed to a message handler for
 * processing. When all messages of a batch are processed, this consumer commits the offset and polls for a new batch.
 * <p>
 * This consumer provides AT LEAST ONCE message processing. When the message handler completes without throwing an
 * exception, the message is considered to be processed and will be "acknowledged" with the following commit. If an
 * error occurs, the underlying Kafka consumer will be closed. In this case, other consumer instances in the same
 * consumer group will consume the messages from the last committed offset. Already processed messages will then be
 * again in the next batch and be passed to the message handler. If de-duplication of messages is required, it must be
 * handled by the message handler.
 * <p>
 * The consumer starts consuming when {@link #start()} is invoked. It needs to be closed by invoking {@link #stop()} to
 * release the resources.
 * <p>
 * </p>
 * ERROR CASES:
 * <p>
 * Errors can happen when polling, in message processing, and when committing the offset to Kafka.
 *
 * If a fatal error occurs, the underlying Kafka consumer will be closed and the close-handler invoked with an exception
 * indicating the cause. Therefore, the provided Kafka consumer must not be used anywhere else.
 * <p>
 * If {@link KafkaConsumer#poll(Duration, Handler)} fails during the start, {@link #start()} will return a failed
 * future. For subsequent {@code poll} operations, the Kafka consumer will be closed and the close handler will be
 * passed a {@link KafkaConsumerPollException}.
 *
 * <p>
 * If the message processing fails because either {@link #createMessage(KafkaConsumerRecord)} or the message handler
 * throws an exception, the Kafka consumer will be closed and the exception will be passed to the close handler.
 * <p>
 * If {@link KafkaConsumer#commit(Handler)} times out, the commit will be retried once. If the retry fails or the commit
 * fails with another exception, the Kafka consumer will be closed and the close handler will be passed a
 * {@link KafkaConsumerCommitException}. For example, commits could regularly fail during consumer rebalance.
 *
 * @param <T> The type of message to be created from the Kafka records.
 */
public abstract class AbstractAtLeastOnceKafkaConsumer<T> implements Lifecycle {

    // TODO add unit test

    private static final Logger LOG = LoggerFactory.getLogger(AbstractAtLeastOnceKafkaConsumer.class);

    private final KafkaConsumer<String, Buffer> kafkaConsumer;
    private final Set<String> topics;
    private final Pattern topicPattern;
    private final Handler<T> messageHandler;
    private final Handler<Throwable> closeHandler;
    private final Duration pollTimeout;

    /**
     * Creates a new consumer.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topic The Kafka topic to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @see #start()
     * @see #stop()
     */
    public AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer, final String topic,
            final Handler<T> messageHandler, final Handler<Throwable> closeHandler, final long pollTimeout) {

        this(kafkaConsumer, Set.of(Objects.requireNonNull(topic)), messageHandler, closeHandler, pollTimeout);
    }

    /**
     * Creates a new consumer.
     * <p>
     * It must be ensured that all given topics contain compatible data to be able to create the expected message type
     * in {@link #createMessage(KafkaConsumerRecord)}.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topics The Kafka topics to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @see #start()
     * @see #stop()
     */
    public AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer, final Set<String> topics,
            final Handler<T> messageHandler, final Handler<Throwable> closeHandler, final long pollTimeout) {

        this(kafkaConsumer, Objects.requireNonNull(topics), null, messageHandler, closeHandler, pollTimeout);
    }

    /**
     * Creates a new consumer.
     * <p>
     * It must be ensured that all Topics matching the specified Topic pattern contain compatible data to be able to
     * create the expected message type in {@link #createMessage(KafkaConsumerRecord)}.
     *
     * @param kafkaConsumer The Kafka consumer to be exclusively used by this instance to consume records.
     * @param topicPattern The pattern of Kafka topic names to consume records from.
     * @param messageHandler The handler to be invoked for each message created from a record.
     * @param closeHandler The handler to be invoked when the Kafka consumer has been closed due to an error.
     * @param pollTimeout The maximal number of milliseconds to wait for messages during a poll operation.
     * @throws NullPointerException if any of the parameters is {@code null}.
     * @see #start()
     * @see #stop()
     */
    public AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer,
            final Pattern topicPattern, final Handler<T> messageHandler, final Handler<Throwable> closeHandler,
            final long pollTimeout) {

        this(kafkaConsumer, null, Objects.requireNonNull(topicPattern), messageHandler, closeHandler, pollTimeout);
    }

    private AbstractAtLeastOnceKafkaConsumer(final KafkaConsumer<String, Buffer> kafkaConsumer,
            final Set<String> topics, final Pattern topicPattern, final Handler<T> messageHandler,
            final Handler<Throwable> closeHandler, final long pollTimeout) {

        Objects.requireNonNull(kafkaConsumer);
        Objects.requireNonNull(messageHandler);
        Objects.requireNonNull(closeHandler);

        this.kafkaConsumer = kafkaConsumer;
        this.messageHandler = messageHandler;
        this.closeHandler = closeHandler;

        this.topics = topics;
        this.topicPattern = topicPattern;

        this.pollTimeout = Duration.ofMillis(pollTimeout);
    }

    /**
     * Creates a message from the given Kafka consumer record. The message will be passed to the message handler
     * afterward.
     *
     * @param record The record.
     * @return The message.
     * @throws NullPointerException if the record is {@code null}.
     */
    protected abstract T createMessage(KafkaConsumerRecord<String, Buffer> record);

    /**
     * Starts the Kafka consumer.
     * <p>
     * The consumer subscribes and {@link KafkaConsumer#poll(Duration, Handler) polls} for the first batch of messages.
     * This method waits for the results of both operations but not for the processing of the messages), so that error
     * cases like invalid config properties or failing authentication are detected early.
     *
     * @return a future indicating the outcome. If the <em>subscribe</em> or the first <em>poll</em> operation fails,
     *         the future will be failed with the cause.
     */
    @Override
    public Future<Void> start() {

        final Promise<Void> promise = Promise.promise();
        if (topics != null) {
            kafkaConsumer.subscribe(topics, promise);
        } else {
            kafkaConsumer.subscribe(topicPattern, promise);
        }

        return promise.future()
                .compose(v -> {
                    final Promise<KafkaConsumerRecords<String, Buffer>> pollPromise = Promise.promise();
                    kafkaConsumer.poll(pollTimeout, pollPromise);
                    return pollPromise.future()
                            .onSuccess(this::handleBatch) // do not wait for the processing to finish
                            .recover(cause -> Future.failedFuture(new KafkaConsumerPollException(cause)))
                            .mapEmpty();
                });
    }

    /**
     * Closes the underlying Kafka consumer.
     * <p>
     * This does not invoke the close handler.
     *
     * @return A future that will complete when the Kafka consumer is closed.
     */
    @Override
    public Future<Void> stop() {
        final Promise<Void> promise = Promise.promise();
        kafkaConsumer.close(promise);
        return promise.future();
    }

    private void handleBatch(final KafkaConsumerRecords<String, Buffer> records) {

        LOG.debug("Polled {} records", records.size());
        CompositeFuture.all(processBatch(records))
                .compose(ok -> commit(true))
                .compose(ok -> poll())
                .onSuccess(this::handleBatch);

    }

    private Future<KafkaConsumerRecords<String, Buffer>> poll() {
        final Promise<KafkaConsumerRecords<String, Buffer>> pollPromise = Promise.promise();
        kafkaConsumer.poll(pollTimeout, pollPromise);
        return pollPromise.future()
                .recover(cause -> {
                    LOG.error("Error polling messages: " + cause);
                    final KafkaConsumerPollException exception = new KafkaConsumerPollException(cause);
                    closeWithError(exception);
                    return Future.failedFuture(exception);
                });
    }

    @SuppressWarnings("rawtypes")
    private List<Future> processBatch(final KafkaConsumerRecords<String, Buffer> records) {
        return IntStream.range(0, records.size())
                .mapToObj(records::recordAt)
                .map(this::processRecord)
                .collect(Collectors.toList());
    }

    private Future<Void> processRecord(final KafkaConsumerRecord<String, Buffer> record) {
        try {
            final T message = createMessage(record);
            messageHandler.handle(message);
            return Future.succeededFuture();
        } catch (final Exception ex) {
            LOG.error("Error handling record, closing the consumer: ", ex);
            commitCurrentOffset(record); // prevents that already processed records from the batch are consumed again
            stop();
            return Future.failedFuture(ex);
        }
    }

    private Future<Void> commit(final boolean retry) {
        final Promise<Void> commitPromise = Promise.promise();
        kafkaConsumer.commit(commitPromise);
        return commitPromise.future()
                .onSuccess(ok -> LOG.debug("Committed offsets"))
                .recover(cause -> {
                    LOG.error("Error committing offsets: " + cause);
                    if ((cause instanceof org.apache.kafka.common.errors.TimeoutException) && retry) {
                        LOG.debug("Committing offsets timed out. Maybe increase 'default.api.timeout.ms'?");
                        return commit(false); // retry once
                    } else {
                        final KafkaConsumerCommitException exception = new KafkaConsumerCommitException(cause);
                        closeWithError(exception);
                        return Future.failedFuture(exception);
                    }
                });
    }

    private void commitCurrentOffset(final KafkaConsumerRecord<String, Buffer> record) {
        final TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
        final OffsetAndMetadata offsetAndMetadata = new OffsetAndMetadata(record.offset() + 1, "");
        kafkaConsumer.commit(Map.of(topicPartition, offsetAndMetadata));
    }

    private void closeWithError(final Throwable exception) {
        LOG.error("Closing consumer with cause", exception);
        closeHandler.handle(exception);
        stop();
    }

}
