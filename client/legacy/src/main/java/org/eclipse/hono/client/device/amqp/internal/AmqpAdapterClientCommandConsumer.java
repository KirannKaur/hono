/*******************************************************************************
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.client.device.amqp.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.impl.CommandConsumer;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.ResourceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.noop.NoopTracer;
import io.vertx.core.Future;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;

/**
 * A wrapper around an AMQP receiver link for consuming commands from Hono's AMQP adapter. This implementation tries to
 * restore closed links by trying to create a new link each time the link is closed.
 */
public class AmqpAdapterClientCommandConsumer extends CommandConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpAdapterClientCommandConsumer.class);

    /**
     * Creates a consumer for a connection and a receiver link.
     *
     * @param connection The connection to the AMQP Messaging Network over which commands are received.
     * @param receiver The receiver link for command messages.
     */
    private AmqpAdapterClientCommandConsumer(final HonoConnection connection, final ProtonReceiver receiver) {
        super(connection, receiver);
    }

    /**
     * Creates a new command consumer for the given device.
     * <p>
     * The underlying receiver link will be created with its <em>autoAccept</em> property set to {@code true} and with
     * the connection's default pre-fetch size.
     *
     * @param con The connection to the server.
     * @param tenantId The tenant to consume commands from.
     * @param deviceId The device for which the commands should be consumed.
     * @param messageHandler The handler to invoke with every message received.
     * @return A future indicating the outcome of the creation attempt.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public static Future<MessageConsumer> create(
            final HonoConnection con,
            final String tenantId,
            final String deviceId,
            final BiConsumer<ProtonDelivery, Message> messageHandler) {

        Objects.requireNonNull(con);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(deviceId);
        Objects.requireNonNull(messageHandler);

        final ResourceIdentifier address = ResourceIdentifier
                .from(CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT, tenantId, deviceId);
        return createCommandConsumer(con, messageHandler, address);
    }

    /**
     * Creates a new command consumer.
     * <p>
     * The underlying receiver link will be created with its <em>autoAccept</em> property set to {@code true} and with
     * the connection's default pre-fetch size.
     *
     * @param con The connection to the server.
     * @param messageHandler The handler to invoke with every message received.
     * @return A future indicating the outcome of the creation attempt.
     * @throws NullPointerException if any of the parameters are {@code null}.
     */
    public static Future<MessageConsumer> create(
            final HonoConnection con,
            final BiConsumer<ProtonDelivery, Message> messageHandler) {

        Objects.requireNonNull(con);
        Objects.requireNonNull(messageHandler);

        final ResourceIdentifier address = ResourceIdentifier
                .from(CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT, null, null);
        return createCommandConsumer(con, messageHandler, address);
    }

    private static Future<MessageConsumer> createCommandConsumer(final HonoConnection con,
            final BiConsumer<ProtonDelivery, Message> messageHandler, final ResourceIdentifier address) {

        return con.isConnected(con.getConfig().getLinkEstablishmentTimeout())
                .compose(v -> createReceiver(con, messageHandler, address))
                .map(rec -> {
                    final AmqpAdapterClientCommandConsumer consumer = new AmqpAdapterClientCommandConsumer(con, rec);
                    con.addReconnectListener(
                            c -> createReceiver(con, messageHandler, address).onSuccess(consumer::setReceiver));
                    return consumer;
                });

    }

    private static Future<ProtonReceiver> createReceiver(final HonoConnection con,
            final BiConsumer<ProtonDelivery, Message> messageHandler, final ResourceIdentifier address) {
        return con.createReceiver(
                address.toString(),
                ProtonQoS.AT_LEAST_ONCE,
                (protonDelivery, message) -> {
                    traceCommand(con, address, message);
                    messageHandler.accept(protonDelivery, message);
                },
                // TODO maybe this could be handled by reopening the link?
                remote -> LOG.info("The remote [{}] closed the receiver link", remote));
    }

    private static void traceCommand(final HonoConnection con, final ResourceIdentifier address,
            final Message message) {
        final Tracer tracer = con.getTracer();
        if (tracer instanceof NoopTracer) {
            return;
        }

        // try to extract Span context from incoming message
        final SpanContext spanContext = TracingHelper.extractSpanContext(tracer, message);
        final Span currentSpan = createSpan("receive command", address.getTenantId(),
                address.getResourceId(), null, tracer, spanContext);
        final Object correlationId = message.getCorrelationId();
        if (correlationId == null || correlationId instanceof String) {
            final Map<String, String> items = new HashMap<>(5);
            items.put(Fields.EVENT, "received command message");
            TracingHelper.TAG_CORRELATION_ID.set(currentSpan, ((String) correlationId));
            items.put("to", message.getAddress());
            items.put("reply-to", message.getReplyTo());
            items.put("name", message.getSubject());
            items.put("content-type", message.getContentType());
            currentSpan.log(items);
        } else {
            TracingHelper.logError(currentSpan,
                    "received invalid command message. correlation-id is not of type string.");
        }
    }

    private void setReceiver(final ProtonReceiver protonReceiver) {
        receiver = protonReceiver;
    }

    // visible for testing
    ProtonReceiver getReceiver() {
        return receiver;
    }

}
