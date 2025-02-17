/**
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


package org.eclipse.hono.service;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.eclipse.hono.client.amqp.config.ClientConfigProperties;
import org.eclipse.hono.client.amqp.connection.HonoConnection;
import org.eclipse.hono.client.notification.amqp.ProtonBasedNotificationReceiver;
import org.eclipse.hono.client.notification.kafka.KafkaBasedNotificationReceiver;
import org.eclipse.hono.client.notification.kafka.NotificationKafkaConsumerConfigProperties;
import org.eclipse.hono.client.util.ServiceClient;
import org.eclipse.hono.notification.NotificationConstants;
import org.eclipse.hono.notification.NotificationEventBusSupport;
import org.eclipse.hono.notification.NotificationReceiver;
import org.eclipse.hono.service.util.ServiceClientAdapter;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentracing.Tracer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.impl.cpu.CpuCoreSensor;
import io.vertx.core.json.impl.JsonUtil;

/**
 * A base class for implementing Quarkus based services.
 *
 */
public abstract class AbstractServiceApplication implements ComponentNameProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractServiceApplication.class);

    /**
     * The vert.x instance managed by Quarkus.
     */
    @Inject
    protected Vertx vertx;

    /**
     * The tracer to use for tracking the processing of requests.
     */
    @Inject
    protected Tracer tracer;

    /**
     * The meter registry managed by Quarkus.
     */
    @Inject
    protected MeterRegistry meterRegistry;

    /**
     * The server to register Vert.x Health Checks with.
     */
    @Inject
    protected SmallRyeHealthCheckServer healthCheckServer;

    /**
     * The application configuration properties.
     */
    @Inject
    protected ApplicationConfigProperties appConfig;

    /**
     * The startup check to track deployment of Verticles with.
     */
    @Inject
    @Readiness
    protected DeploymentHealthCheck deploymentCheck;

    /**
     * Logs information about the JVM.
     */
    protected void logJvmDetails() {
        if (LOG.isInfoEnabled()) {

            final String base64Encoder = Base64.getEncoder() == JsonUtil.BASE64_ENCODER ? "legacy" : "URL safe";

            LOG.info("""
                    running on Java VM [version: {}, name: {}, vendor: {}, max memory: {}MiB, processors: {}] \
                    with vert.x using {} Base64 encoder
                    """,
                    System.getProperty("java.version"),
                    System.getProperty("java.vm.name"),
                    System.getProperty("java.vm.vendor"),
                    Runtime.getRuntime().maxMemory() >> 20,
                    CpuCoreSensor.availableProcessors(),
                    base64Encoder);
        }
    }

    /**
     * Registers additional health checks.
     *
     * @param provider The provider of the health checks to be registered (may be {@code null}).
     * @deprecated Consider implementing health checks according to the MicroProfile Health specification instead of
     *             Vert.x Health and register them as CDI beans as described in https://quarkus.io/guides/smallrye-health
     */
    @Deprecated
    protected final void registerHealthchecks(final HealthCheckProvider provider) {
        Optional.ofNullable(provider).ifPresent(p -> {
            LOG.debug("registering legacy health checks [provider: {}]", p.getClass().getName());
            healthCheckServer.registerHealthCheckResources(p);
        });
    }

    /**
     * Registers additional health checks.
     * <p>
     * Does nothing if the given object is not a {@link HealthCheckProvider}.
     * Otherwise, simply invokes {@link #registerHealthchecks(HealthCheckProvider)}.
     *
     * @param obj The provider of the health checks.
     * @deprecated Consider implementing health checks according to the MicroProfile Health specification instead of
     *             Vert.x Health and register them as CDI beans as described in https://quarkus.io/guides/smallrye-health
     */
    @Deprecated
    protected final void registerHealthCheckProvider(final Object obj) {
        if (obj instanceof HealthCheckProvider) {
            registerHealthchecks((HealthCheckProvider) obj);
        }
    }

    private void registerVertxCloseHook() {
        // register a close hook that will be notified when the Vertx instance is being closed
        if (vertx instanceof VertxInternal vertxInternal) {
            vertxInternal.addCloseHook(completion -> {
                final StringBuilder b = new StringBuilder("managed vert.x instance has been closed");
                if (LOG.isDebugEnabled()) {
                    final var stackTrace = Thread.currentThread().getStackTrace();
                    final String s = Arrays.stream(stackTrace)
                        .map(element -> "\tat %s.%s(%s:%d)".formatted(
                                element.getClassName(),
                                element.getMethodName(),
                                element.getFileName(),
                                element.getLineNumber()))
                        .collect(Collectors.joining(System.lineSeparator()));
                    b.append(System.lineSeparator()).append(s);
                }
                LOG.info(b.toString());
                completion.complete();
            });
        } else {
            LOG.debug("Vertx instance is not a VertxInternal, skipping close hook registration");
        }
    }

    /**
     * Starts this component.
     * <p>
     * This implementation
     * <ol>
     * <li>logs the VM details,</li>
     * <li>invokes {@link #doStart()}.</li>
     * </ol>
     *
     * @param ev The event indicating shutdown.
     */
    public void onStart(final @Observes StartupEvent ev) {

        logJvmDetails();
        registerVertxCloseHook();
        if (appConfig.isKafkaMessagingDisabled()) {
            LOG.info("Kafka based messaging has been disabled explicitly");
        }
        if (appConfig.isAmqpMessagingDisabled()) {
            LOG.info("AMQP 1.0 based messaging has been disabled explicitly");
        }
        doStart();
    }

    /**
     * Invoked during start up.
     * <p>
     * Subclasses should override this method in order to initialize
     * the component.
     */
    protected void doStart() {
        // do nothing
    }

    /**
     * Stops this component.
     *
     * @param ev The event indicating shutdown.
     */
    public void onStop(final @Observes ShutdownEvent ev) {
        LOG.info("shutting down {}", getComponentName());
        final CompletableFuture<Void> shutdown = new CompletableFuture<>();
        vertx.close(attempt -> {
            if (attempt.succeeded()) {
                shutdown.complete(null);
            } else {
                shutdown.completeExceptionally(attempt.cause());
            }
        });
        shutdown.join();
    }

    /**
     * Creates a notification receiver for configuration properties.
     *
     * @param kafkaNotificationConfig The Kafka connection properties.
     * @param amqpNotificationConfig The AMQP 1.0 connection properties.
     * @return the receiver.
     * @throws IllegalStateException if both AMQP and Kafka based messaging have been disabled explicitly.
     */
    protected NotificationReceiver notificationReceiver(
            final NotificationKafkaConsumerConfigProperties kafkaNotificationConfig,
            final ClientConfigProperties amqpNotificationConfig) {

        final NotificationReceiver notificationReceiver;
        if (!appConfig.isKafkaMessagingDisabled() && kafkaNotificationConfig.isConfigured()) {
            notificationReceiver = new KafkaBasedNotificationReceiver(vertx, kafkaNotificationConfig);
        } else if (!appConfig.isAmqpMessagingDisabled() && amqpNotificationConfig.isHostConfigured()) {
            final var notificationConfig = new ClientConfigProperties(amqpNotificationConfig);
            notificationConfig.setServerRole("Notification");
            notificationReceiver = new ProtonBasedNotificationReceiver(
                    HonoConnection.newConnection(vertx, notificationConfig, tracer));
        } else {
            throw new IllegalStateException("at least one of Kafka or AMQP messaging must be configured");
        }
        if (notificationReceiver instanceof ServiceClient serviceClient) {
            healthCheckServer.registerHealthCheckResources(ServiceClientAdapter.forClient(serviceClient));
        }
        final var notificationSender = NotificationEventBusSupport.getNotificationSender(vertx);
        NotificationConstants.DEVICE_REGISTRY_NOTIFICATION_TYPES.forEach(notificationType -> {
            notificationReceiver.registerConsumer(notificationType, notificationSender::handle);
        });
        return notificationReceiver;
    }

}
