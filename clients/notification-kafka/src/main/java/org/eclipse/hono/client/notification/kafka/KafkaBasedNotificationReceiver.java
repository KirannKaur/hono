/*
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
 */

package org.eclipse.hono.client.notification.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.kafka.clients.consumer.Consumer;
import org.eclipse.hono.client.kafka.KafkaClientFactory;
import org.eclipse.hono.client.kafka.consumer.HonoKafkaConsumer;
import org.eclipse.hono.notification.AbstractNotification;
import org.eclipse.hono.notification.NotificationReceiver;
import org.eclipse.hono.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.kafka.client.consumer.KafkaConsumerRecord;

/**
 * A client for receiving notifications with Kafka.
 */
public class KafkaBasedNotificationReceiver implements NotificationReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaBasedNotificationReceiver.class);
    private static final String NAME = "notification";

    private final Vertx vertx;
    private final NotificationKafkaConsumerConfigProperties consumerConfig;
    private final Map<Class<? extends AbstractNotification>, Handler<? extends AbstractNotification>> handlerPerType = new HashMap<>();
    private final Set<String> topics = new HashSet<>();

    private Supplier<Consumer<String, Buffer>> kafkaConsumerSupplier;
    private HonoKafkaConsumer honoKafkaConsumer;
    private boolean started = false;

    /**
     * Creates a client for consuming notifications.
     *
     * @param vertx The vert.x instance to be used.
     * @param consumerConfig The configuration for the Kafka consumer.
     *
     * @throws NullPointerException if any parameter is {@code null}.
     * @throws IllegalArgumentException if consumerConfig does not contain a valid configuration (at least a bootstrap
     *             server).
     */
    public KafkaBasedNotificationReceiver(final Vertx vertx,
            final NotificationKafkaConsumerConfigProperties consumerConfig) {

        Objects.requireNonNull(vertx);
        Objects.requireNonNull(consumerConfig);

        if (!consumerConfig.isConfigured()) {
            throw new IllegalArgumentException("No Kafka configuration found!");
        }

        this.vertx = vertx;
        this.consumerConfig = consumerConfig;

    }

    // visible for testing
    void setKafkaConsumerFactory(final Supplier<Consumer<String, Buffer>> kafkaConsumerSupplier) {
        this.kafkaConsumerSupplier = Objects.requireNonNull(kafkaConsumerSupplier);
    }

    @Override
    public Future<Void> start() {
        honoKafkaConsumer = new HonoKafkaConsumer(vertx, topics, getRecordHandler(), consumerConfig.getConsumerConfig(NAME));
        honoKafkaConsumer.setPollTimeout(Duration.ofMillis(consumerConfig.getPollTimeout()));
        honoKafkaConsumer.setConsumerCreationRetriesTimeout(KafkaClientFactory.UNLIMITED_RETRIES_DURATION);
        Optional.ofNullable(kafkaConsumerSupplier).ifPresent(honoKafkaConsumer::setKafkaConsumerSupplier);
        return honoKafkaConsumer
                .start()
                .onSuccess(ok -> started = true);
    }

    @Override
    public <T extends AbstractNotification> void registerConsumer(final NotificationType<T> notificationType,
            final Handler<T> consumer) {

        if (started) {
            throw new IllegalStateException("consumers cannot be added when receiver is already started.");
        }

        topics.add(NotificationTopicHelper.getTopicName(notificationType));
        handlerPerType.put(notificationType.getClazz(), consumer);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Handler<KafkaConsumerRecord<String, Buffer>> getRecordHandler() {
        return record -> {
            final JsonObject json = record.value().toJsonObject();
            if (LOG.isTraceEnabled()) {
                LOG.trace("received notification:{}{}", System.lineSeparator(), json.encodePrettily());
            }

            final AbstractNotification notification = json.mapTo(AbstractNotification.class);
            final Handler handler = handlerPerType.get(notification.getClass());
            if (handler != null) {
                handler.handle(notification);
            }
        };
    }

    @Override
    public Future<Void> stop() {
        return stopKafkaConsumer()
                .onComplete(v -> topics.clear())
                .onComplete(v -> handlerPerType.clear())
                .onComplete(v -> started = false)
                .mapEmpty();
    }

    private Future<Void> stopKafkaConsumer() {
        if (honoKafkaConsumer != null) {
            return honoKafkaConsumer.stop();
        } else {
            return Future.succeededFuture();
        }
    }

}
