/**
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.deviceregistry.app;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.hono.client.amqp.config.ClientConfigProperties;
import org.eclipse.hono.client.amqp.config.ClientOptions;
import org.eclipse.hono.client.kafka.CommonKafkaClientOptions;
import org.eclipse.hono.client.kafka.producer.KafkaProducerOptions;
import org.eclipse.hono.client.kafka.producer.MessagingKafkaProducerConfigProperties;
import org.eclipse.hono.client.notification.kafka.NotificationKafkaProducerConfigProperties;
import org.eclipse.hono.deviceregistry.service.device.AutoProvisionerConfigOptions;
import org.eclipse.hono.deviceregistry.service.device.AutoProvisionerConfigProperties;
import org.eclipse.hono.service.auth.delegating.AuthenticationServerClientConfigProperties;
import org.eclipse.hono.service.auth.delegating.AuthenticationServerClientOptions;

import io.smallrye.config.ConfigMapping;

/**
 * A producer of registry service configuration properties commonly used in registry implementations.
 *
 */
@ApplicationScoped
public class CommonConfigPropertiesProducer {

    @Produces
    @Singleton
    @Named("amqp-messaging-network")
    ClientConfigProperties downstreamSenderProperties(
            @ConfigMapping(prefix = "hono.messaging", namingStrategy = ConfigMapping.NamingStrategy.VERBATIM)
            final ClientOptions downstreamSenderOptions) {
        final var result = new ClientConfigProperties(downstreamSenderOptions);
        result.setServerRoleIfUnknown("AMQP Messaging Network");
        result.setNameIfNotSet("Hono Device Registry");
        return result;
    }

    @Produces
    @Singleton
    AuthenticationServerClientConfigProperties authenticationServerClientProperties(
            @ConfigMapping(prefix = "hono.auth")
            final AuthenticationServerClientOptions options) {
        final var props = new AuthenticationServerClientConfigProperties(options);
        props.setServerRoleIfUnknown("Authentication Server");
        return props;
    }

    @Produces
    @Singleton
    MessagingKafkaProducerConfigProperties eventKafkaProducerClientOptions(
            @ConfigMapping(prefix = "hono.kafka")
            final CommonKafkaClientOptions commonOptions,
            @ConfigMapping(prefix = "hono.kafka.event")
            final KafkaProducerOptions eventProducerOptions) {

        return new MessagingKafkaProducerConfigProperties(commonOptions, eventProducerOptions);
    }

    @Produces
    @Singleton
    NotificationKafkaProducerConfigProperties notificationKafkaClientOptions(
            @ConfigMapping(prefix = "hono.kafka")
            final CommonKafkaClientOptions commonOptions,
            @ConfigMapping(prefix = "hono.kafka.notification")
            final KafkaProducerOptions notificationOptions) {

        return new NotificationKafkaProducerConfigProperties(commonOptions, notificationOptions);
    }

    @Produces
    @Singleton
    AutoProvisionerConfigProperties autoProvisionerOptions(final AutoProvisionerConfigOptions options) {
        final var result = new AutoProvisionerConfigProperties();
        result.setRetryEventSendingDelay(options.retryEventSendingDelay());
        return result;
    }
}
