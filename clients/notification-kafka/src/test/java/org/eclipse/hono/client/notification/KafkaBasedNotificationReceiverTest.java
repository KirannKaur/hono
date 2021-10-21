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

package org.eclipse.hono.client.notification;

import static com.google.common.truth.Truth.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.hono.client.kafka.AbstractKafkaConfigProperties;
import org.eclipse.hono.client.kafka.HonoTopic;
import org.eclipse.hono.kafka.test.KafkaMockConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Verifies behavior of {@link KafkaBasedNotificationReceiver}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
public class KafkaBasedNotificationReceiverTest {

    private KafkaMockConsumer mockConsumer;
    private Map<String, String> consumerConfig;
    private Vertx vertx;

    /**
     *
     * Sets up fixture.
     *
     * @param vertx The vert.x instance to use.
     */
    @BeforeEach
    void setUp(final Vertx vertx) {
        this.vertx = vertx;

        mockConsumer = new KafkaMockConsumer(OffsetResetStrategy.EARLIEST);

        consumerConfig = Map.of(AbstractKafkaConfigProperties.PROPERTY_BOOTSTRAP_SERVERS,
                "kafka", "client.id", "application-test-consumer");

    }

    /**
     * Verifies that the consumer is successfully created by the receiver.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testCreateConsumer(final VertxTestContext ctx) {

        final var receiver = createReceiver();

        final Handler<Buffer> notificationHandler = json -> {
            final TestNotification testNotification = Json.decodeValue(json, TestNotification.class);
            // TODO add test that verifies the notification
        };
        final NotificationConsumer consumer = new NotificationConsumer(TestNotification.TYPE, TestNotification.ADDRESS,
                notificationHandler);
        receiver.addConsumer(consumer);

        receiver.start()
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    final Set<String> subscription = mockConsumer.subscription();
                    assertThat(subscription).isNotNull();
                    assertThat(subscription)
                            .contains(new HonoTopic(HonoTopic.Type.NOTIFICATION, TestNotification.ADDRESS).toString());
                    assertThat(mockConsumer.closed()).isFalse();
                    ctx.completeNow();
                })));
    }

    /**
     * Verifies that the underlying Kafka consumer is closed when the receiver is stopped.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testStopClosesConsumer(final VertxTestContext ctx) {

        final var receiver = createReceiver();

        final NotificationConsumer consumer = new NotificationConsumer(TestNotification.TYPE, TestNotification.ADDRESS,
                json -> {
                });
        receiver.addConsumer(consumer);

        receiver.start()
                .compose(v -> receiver.stop())
                .onComplete(ctx.succeeding(v -> ctx.verify(() -> {
                    assertThat(mockConsumer.closed()).isTrue();
                    ctx.completeNow();
                })));
    }

    private KafkaBasedNotificationReceiver createReceiver() {

        final String topic = new HonoTopic(HonoTopic.Type.NOTIFICATION, TestNotification.ADDRESS).toString();
        final TopicPartition topicPartition = new TopicPartition(topic, 0);
        mockConsumer.updateBeginningOffsets(Map.of(topicPartition, ((long) 0)));
        mockConsumer.updatePartitions(topicPartition, KafkaMockConsumer.DEFAULT_NODE);
        mockConsumer.setRebalancePartitionAssignmentAfterSubscribe(List.of(topicPartition));

        final KafkaBasedNotificationReceiver client = new KafkaBasedNotificationReceiver(vertx, consumerConfig);
        client.setKafkaConsumerFactory(() -> mockConsumer);

        return client;
    }

    static class TestNotification implements Notification {

        public static final String ADDRESS = "test-topic";

        public static final String TYPE = "type";
        public static final String SOURCE = "source";
        public static final Instant TIMESTAMP = Instant.parse("2020-08-11T11:38:00Z");

        @Override
        public String getType() {
            return TYPE;
        }

        @Override
        public String getAddress() {
            return ADDRESS;
        }

        @Override
        public String getSource() {
            return SOURCE;
        }

        @Override
        public Instant getTimestamp() {
            return TIMESTAMP;
        }
    }
}
