/*******************************************************************************
 * Copyright (c) 2016, 2018 Contributors to the Eclipse Foundation
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
package org.eclipse.hono.adapter.amqp;

import java.net.HttpURLConnection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.auth.Device;
import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.client.Command;
import org.eclipse.hono.client.CommandContext;
import org.eclipse.hono.client.CommandResponse;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.MessageSender;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.config.ProtocolAdapterProperties;
import org.eclipse.hono.service.AbstractProtocolAdapterBase;
import org.eclipse.hono.util.CommandConstants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.EndpointType;
import org.eclipse.hono.util.HonoProtonHelper;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.ResourceIdentifier;
import org.eclipse.hono.util.Strings;
import org.eclipse.hono.util.TenantObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonLink;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import io.vertx.proton.ProtonServer;
import io.vertx.proton.ProtonServerOptions;
import io.vertx.proton.ProtonSession;
import io.vertx.proton.sasl.ProtonSaslAuthenticatorFactory;

/**
 * A Vert.x based Hono protocol adapter for publishing messages to Hono's Telemetry and Event APIs using AMQP.
 */
public final class VertxBasedAmqpProtocolAdapter extends AbstractProtocolAdapterBase<ProtocolAdapterProperties> {

    private static final Logger LOG = LoggerFactory.getLogger(VertxBasedAmqpProtocolAdapter.class);
    // These values should be made configurable.
    private static final int DEFAULT_MAX_FRAME_SIZE = 32 * 1024; // 32 KB
    private static final int DEFAULT_MAX_SESSION_WINDOW = 100 * DEFAULT_MAX_FRAME_SIZE;

    /**
     * The default insecure port that this adapter binds to for unencrypted connections.
     */
    private static final int DEFAULT_INSECURE_PORT = 5672;

    /**
     * The default secure port that this adapter binds to for TLS based secure connections.
     */
    private static final int DEFAULT_SECURE_PORT = 5671;

    /**
     * The AMQP server instance that maps to a secure port.
     */
    private ProtonServer secureServer;

    /**
     * The AMQP server instance that listens for incoming request from an insecure port.
     */
    private ProtonServer insecureServer;

    private AtomicBoolean secureListening = new AtomicBoolean(false);

    /**
     * This adapter's custom SASL authenticator factory for handling the authentication process for devices.
     */
    private ProtonSaslAuthenticatorFactory authenticatorFactory;

    // -----------------------------------------< AbstractProtocolAdapterBase >---
    /**
     * {@inheritDoc}
     */
    @Override
    protected String getTypeName() {
        return Constants.PROTOCOL_ADAPTER_TYPE_AMQP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doStart(final Future<Void> startFuture) {
        checkPortConfiguration()
                .compose(success -> {
                    if (authenticatorFactory == null && getConfig().isAuthenticationRequired()) {
                        authenticatorFactory = new AmqpAdapterSaslAuthenticatorFactory(getTenantServiceClient(), getCredentialsServiceClient(), getConfig());
                    }
                    return Future.succeededFuture();
                }).compose(succcess -> {
                    return CompositeFuture.all(bindSecureServer(), bindInsecureServer());
                }).compose(success -> {
                    startFuture.complete();
                }, startFuture);

    }

    @Override
    protected void doStop(final Future<Void> stopFuture) {
        CompositeFuture.all(stopSecureServer(), stopInsecureServer())
        .compose(ok -> stopFuture.complete(), stopFuture);
    }

    private Future<Void> stopInsecureServer() {
        final Future<Void> result = Future.future();
        if (insecureServer != null) {
            LOG.info("Shutting down insecure server");
            insecureServer.close(result.completer());
        } else {
            result.complete();
        }
        return result;
    }

    private Future<Void> stopSecureServer() {
        final Future<Void> result = Future.future();
        if (secureServer != null) {

            LOG.info("Shutting down secure server");
            secureListening.compareAndSet(Boolean.TRUE, Boolean.FALSE);
            secureServer.close(result.completer());

        } else {
            result.complete();
        }
        return result;
    }

    private Future<Void> bindInsecureServer() {
        if (isInsecurePortEnabled()) {
            final ProtonServerOptions options =
                    new ProtonServerOptions()
                    .setHost(getConfig().getInsecurePortBindAddress())
                    .setPort(determineInsecurePort());

            final Future<Void> result = Future.future();
            insecureServer = createServer(insecureServer, options);
            insecureServer.connectHandler(this::connectionRequestHandler).listen(ar -> {
                if (ar.succeeded()) {
                    LOG.info("insecure amqp server listening on [{}:{}]", getConfig().getInsecurePortBindAddress(), getActualInsecurePort());
                    result.complete();
                } else {
                    result.fail(ar.cause());
                }
            });
            return result;
        } else {
            return Future.succeededFuture();
        }
    }

    private Future<Void> bindSecureServer() {
        if (isSecurePortEnabled()) {
            final ProtonServerOptions options =
                    new ProtonServerOptions()
                    .setHost(getConfig().getBindAddress())
                    .setPort(determineSecurePort())
                    .setMaxFrameSize(DEFAULT_MAX_FRAME_SIZE);
            addTlsKeyCertOptions(options);
            addTlsTrustOptions(options);

            final Future<Void> result = Future.future();
            secureServer = createServer(secureServer, options);
            secureServer.connectHandler(this::connectionRequestHandler).listen(ar -> {
                if (ar.succeeded()) {
                    secureListening.getAndSet(Boolean.TRUE);
                    LOG.info("secure amqp server listening on {}:{}", getConfig().getBindAddress(), getActualPort());
                    result.complete();
                } else {
                    LOG.error("cannot bind to secure port", ar.cause());
                    result.fail(ar.cause());
                }
            });
            return result;
        } else {
            return Future.succeededFuture();
        }
    }

    private ProtonServer createServer(final ProtonServer server, final ProtonServerOptions options) {
        final ProtonServer createdServer = (server != null) ? server : ProtonServer.create(this.vertx, options);
        if (getConfig().isAuthenticationRequired()) {
            createdServer.saslAuthenticatorFactory(authenticatorFactory);
        } else {
            // use proton's default authenticator -> SASL ANONYMOUS
            createdServer.saslAuthenticatorFactory(null);
        }
        return createdServer;
    }

    private void connectionRequestHandler(final ProtonConnection connRequest) {

        LOG.debug("Received connection request from client");

        if (secureListening.get()) {
            connRequest.setContainer(String.format("%s-%s:%d", "secure-server", getBindAddress(), getActualPort()));
        } else {
            connRequest.setContainer(String.format("%s-%s:%d", "insecure-server", getInsecurePortBindAddress(), getActualInsecurePort()));
        }
        connRequest.disconnectHandler(conn -> {
            LOG.debug("client [container: {}] has disconnected", conn.getRemoteContainer());
        });
        connRequest.closeHandler(remoteClose -> handleRemoteConnectionClose(connRequest, remoteClose));

        // when a BEGIN frame is received
        connRequest.sessionOpenHandler(session -> {
            HonoProtonHelper.setDefaultCloseHandler(session);
            handleSessionOpen(connRequest, session);
        });
        // when an OPEN is received
        connRequest.openHandler(remoteOpen -> {
            final ProtonConnection conn = remoteOpen.result();
            conn.setContainer(getTypeName());
            conn.open();
        });
        // when an Attach frame is received
        connRequest.receiverOpenHandler(receiver -> {
            HonoProtonHelper.setDefaultCloseHandler(receiver);
            handleRemoteReceiverOpen(receiver, connRequest);
        });
        connRequest.senderOpenHandler(sender -> {
            final Device authenticatedDevice = connRequest.attachments()
                    .get(AmqpAdapterConstants.KEY_CLIENT_DEVICE, Device.class);
            handleRemoteSenderOpenForCommands(sender, authenticatedDevice);
        });
    }

    /**
     * Sets the AMQP server for handling insecure AMQP connections.
     * 
     * @param server The insecure server instance.
     * @throws NullPointerException If the server is {@code null}.
     */
    protected void setInsecureAmqpServer(final ProtonServer server) {
        Objects.requireNonNull(server);
        if (server.actualPort() > 0) {
            throw new IllegalArgumentException("AMQP Server should not be running");
        } else {
            this.insecureServer = server;
        }
    }

    /**
     * Sets the SASL authenticator factory to use for handling the authentication process of devices.
     * <p>
     * If not explicitly set using this method (and the adapter is enable for device authentication) a 
     * {@code AmqpAdapterSaslAuthenticatorFactory}, configured to use an auth provider based on a username
     * and password, will be created during startup.
     * 
     * @param authFactory The SASL authenticator factory.
     * @throws NullPointerException if the authFactory is {@code null}.
     */
    protected void setSaslAuthenticatorFactory(final ProtonSaslAuthenticatorFactory authFactory) {
        this.authenticatorFactory = Objects.requireNonNull(authFactory, "authFactory must not be null");
    }

    /**
     * This method is called when an AMQP BEGIN frame is received from a remote client. This method sets the incoming
     * capacity in its BEGIN Frame to be communicated to the remote peer
     *
     */
    private void handleSessionOpen(final ProtonConnection conn, final ProtonSession session) {
        LOG.debug("opening new session with client [container: {}]", conn.getRemoteContainer());
        session.setIncomingCapacity(DEFAULT_MAX_SESSION_WINDOW);
        session.open();
    }
    /**
     * Invoked when a client closes the connection with this server.
     * <p>
     * Copied from AmqpServiceBase#handleRemoteConnectionClose.
     * 
     * @param con The connection to close.
     * @param res The client's close frame.
     */
    private void handleRemoteConnectionClose(final ProtonConnection con, final AsyncResult<ProtonConnection> res) {
        if (res.succeeded()) {
            LOG.debug("client [container: {}] closed connection", con.getRemoteContainer());
        } else {
            LOG.debug("client [container: {}] closed connection with error", con.getRemoteContainer(), res.cause());
        }
        con.disconnectHandler(null);
        con.close();
        con.disconnect();
    }

    /**
     * This method is invoked when an AMQP Attach frame (in role = receiver) is received by this server. If the receiver link contains
     * a target address, this method simply closes the link, otherwise, it accept and open the link.
     * 
     * @param receiver The receiver link for receiving the data.
     * @param conn The connection through which the request is initiated.
     */
    protected void handleRemoteReceiverOpen(final ProtonReceiver receiver, final ProtonConnection conn) {
        if (receiver.getRemoteTarget() != null && receiver.getRemoteTarget().getAddress() != null) {
            if (!receiver.getRemoteTarget().getAddress().isEmpty()) {
                LOG.debug("Closing link due to the present of Target [address : {}]", receiver.getRemoteTarget().getAddress());
            }
            closeLinkWithError(receiver, new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                    "This adapter does not accept a target address on receiver links"));
        } else {
            final Device authenticatedDevice = conn.attachments().get(AmqpAdapterConstants.KEY_CLIENT_DEVICE,
                    Device.class);
            LOG.debug("Established receiver link at [address: {}]",
                    (receiver.getRemoteTarget() != null) ? receiver.getRemoteTarget().getAddress() : null);

            receiver.setTarget(receiver.getRemoteTarget());
            receiver.setSource(receiver.getRemoteSource());
            receiver.setQoS(receiver.getRemoteQoS());
            if (ProtonQoS.AT_LEAST_ONCE.equals(receiver.getRemoteQoS())) {
                // disable auto-accept for this transfer model.
                // in this case, the adapter will apply the required disposition
                receiver.setAutoAccept(false);
            }
            receiver.handler((delivery, message) -> {

                validateEndpoint(message.getAddress(), delivery)
                        .compose(address -> validateAddress(address, authenticatedDevice))
                        .compose(validAddress -> {
                            message.setAddress(validAddress.toString());
                            uploadMessage(new AmqpContext(delivery, message, authenticatedDevice));
                            return Future.succeededFuture();
                        })
                        .recover(t -> {
                            // invalid message address / endpoint
                            MessageHelper.rejected(delivery, AmqpContext.getErrorCondition(t));
                            return Future.failedFuture(t);
                        });
            });
            HonoProtonHelper.setCloseHandler(receiver, remoteDetach -> onLinkDetach(receiver));
            receiver.open();
        }
    }

    /**
     * This method is invoked when this server receives an AMQP Attach frame (in role = sender) to establish a link
     * for sending commands to devices.
     * 
     * The link for sending commands will be closed if:
     * <ul>
     *  <li> The device does not specify a source address for its receive link. </li>
     *  <li> The source address cannot be parsed or does not point to a command endpoint. </li>
     *  <li> The tenant that the device belongs to is disabled for the AMQP adapter. </li>
     * </ul>
     * 
     * @param sender The link to use for sending commands to devices.
     * @param authenticatedDevice The authenticated device or {@code null} if the device is not authenticated.
     */
    protected void handleRemoteSenderOpenForCommands(final ProtonSender sender, final Device authenticatedDevice) {
        if (sender.getRemoteSource() == null || Strings.isNullOrEmpty(sender.getRemoteSource().getAddress())) {
            // source address is required
            closeLinkWithError(sender,
                    new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Missing source address"));
        } else {
            try {
                final ResourceIdentifier address = ResourceIdentifier.fromString(sender.getRemoteSource().getAddress());
                validateAddress(address, authenticatedDevice).compose(validAddress -> {
                    if (!CommandConstants.isCommandEndpoint(validAddress.getEndpoint())) {
                        closeLinkWithError(sender, new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                                "Only the command endpoint is supported"));
                    } else {
                        openCommandSenderLink(sender, validAddress, authenticatedDevice);
                    }
                    return Future.succeededFuture(null);
                }).recover(t -> {
                    closeLinkWithError(sender,
                            new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid source address"));
                    return Future.failedFuture(t);
                });
            } catch (IllegalArgumentException e) {
                // could not parse source address
                closeLinkWithError(sender,
                        new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid source address"));
            }
        }
    }

    private void openCommandSenderLink(final ProtonSender sender, final ResourceIdentifier address, final Device authenticatedDevice) {
        createCommandConsumer(sender, address, authenticatedDevice).map(consumer -> {

            final String tenantId = address.getTenantId();
            final String deviceId = address.getResourceId();
            sender.setSource(sender.getRemoteSource());

            sender.setQoS(ProtonQoS.AT_LEAST_ONCE);
            HonoProtonHelper.setCloseHandler(sender, remoteDetach -> {
                sendDisconnectedTtdEvent(tenantId, deviceId, authenticatedDevice);
                closeCommandConsumer(tenantId, deviceId);
                onLinkDetach(sender);
            });
            sender.open();

            // At this point, the remote peer's receiver link is successfully opened and is ready to receive
            // commands. Send "device ready for command" notification downstream.
            LOG.debug("Established sender link at [address: {}] to send commands to a device", address);

            sendConnectedTtdEvent(tenantId, deviceId, authenticatedDevice);
            return Future.succeededFuture();
        }).otherwise(t -> {
            // Fail to create a CommandConsumer -> close remote peer's receiver link.
            closeLinkWithError(sender, new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR,
                    "Internal Server error (no command consumer exist)"));
            return Future.failedFuture(t);
        });
    }

    private Future<MessageConsumer> createCommandConsumer(final ProtonSender sender, final ResourceIdentifier sourceAddress, final Device authenticatedDevice) {
        return createCommandConsumer(
                sourceAddress.getTenantId(),
                sourceAddress.getResourceId(),
                commandContext -> {
                    final Command command = commandContext.getCommand();
                    if (!command.isValid()) {
                        commandContext.reject(new ErrorCondition(Constants.AMQP_BAD_REQUEST, "malformed command message"));
                    } else if (!sender.isOpen()) {
                        commandContext.release();
                    } else {
                        onCommandReceived(sender, commandContext);
                    }
                }, closeHandler -> {
                    LOG.debug("command receiver link remotely closed for [tenant-id: {}, device-id: {}]",
                            sourceAddress.getTenantId(), sourceAddress.getResourceId());
                    closeCommandConsumer(sourceAddress.getTenantId(), sourceAddress.getResourceId());
                    // close the command sender link
                    onLinkDetach(sender);
                });
    }

    /**
     * Deliver the given command to the device.
     *
     * @param sender The link for sending the command to the device.
     * @param commandContext The context in which the adapter receives the command message.
     */
    protected void onCommandReceived(final ProtonSender sender, final CommandContext commandContext) {

        final Command command = commandContext.getCommand();
        final Message request = command.getCommandMessage();
        request.setSubject(command.getName());
        Optional.ofNullable(command.getPayload())
                .ifPresent(value -> request.setBody(new Data(new Binary(value.getBytes()))));

        sender.send(request, delivery -> {
            // release the command message when the device either
            // rejects or does not settle the command request message.
            final DeliveryState remoteState = delivery.getRemoteState();
            if (delivery.remotelySettled()) {
                if (Accepted.class.isInstance(remoteState)) {
                    LOG.trace("Device accepted command message [command: {}, remote state: {}]", command.getName(),
                            remoteState);
                    commandContext.accept();
                } else if (Rejected.class.isInstance(remoteState)) {
                    LOG.debug("Device rejected command message [command: {}, remote state: {}]", command.getName(),
                            remoteState);
                    commandContext.release();
                }
            } else {
                LOG.warn("peer did not settle command message [command: {}, remote state: {}]", command.getName(),
                        remoteState);
                commandContext.release();
            }

            // in any case, flow one (1) credit for the application to (re)send another command
            commandContext.flow(1);
        });
    }

    /**
     * Closes the specified link using the given throwable to set the local ErrorCondition object for the link.
     *
     * @param link The link to close.
     * @param t The throwable to use to determine the error condition object.
     */
    private <T extends ProtonLink<T>> void closeLinkWithError(final ProtonLink<T> link, final Throwable t) {
        link.setCondition(AmqpContext.getErrorCondition(t));
        link.close();
    }

    /**
     * Forwards a message received from a device to downstream consumers.
     *
     * @param context The context that the message has been received in.
     */
    protected void uploadMessage(final AmqpContext context) {
        final Future<Void> contentTypeCheck = Future.future();
        final String contentType = context.getMessageContentType();

        if (!isPayloadOfIndicatedType(context.getMessagePayload(), contentType)) {
            contentTypeCheck.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                    String.format("Content-Type: [%s] does not match payload", contentType)));
        } else {
            contentTypeCheck.complete();
        }

        contentTypeCheck.compose(ok -> {
            switch (EndpointType.fromString(context.getEndpoint())) {
            case TELEMETRY:
                LOG.trace("Received request to upload telemetry data to endpoint [with name: {}]",
                        context.getEndpoint());
                return doUploadMessage(context, getTelemetrySender(context.getTenantId()));
            case EVENT:
                LOG.trace("Received request to upload events to endpoint [with name: {}]", context.getEndpoint());
                return doUploadMessage(context, getEventSender(context.getTenantId()));
            case CONTROL:
                LOG.trace("Received request to upload Command Response to endpoint [with name: {}]", context.getEndpoint());
                return doUploadCommandResponseMessage(context);
            default:
                return Future
                        .failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "unknown endpoint"));
            }
        }).recover(t -> {
            if (!context.isRemotelySettled()) {
                // client wants to be informed that the message cannot be processed.
                context.handleFailure(t);
            }
            return Future.failedFuture(t);
        });

    }

    private Future<Void> doUploadMessage(final AmqpContext context, final Future<MessageSender> senderFuture) {

        final Future<JsonObject> tokenFuture = getRegistrationAssertion(context.getTenantId(), context.getDeviceId(),
                context.getAuthenticatedDevice(), null);
        final Future<TenantObject> tenantConfigFuture = getTenantConfiguration(context.getTenantId(), null);

        return CompositeFuture.all(tenantConfigFuture, tokenFuture, senderFuture).compose(ok -> {
            final TenantObject tenantObject = tenantConfigFuture.result();
            if (tenantObject.isAdapterEnabled(getTypeName())) {

                final MessageSender sender = senderFuture.result();
                final Message downstreamMessage = newMessage(context.getResourceIdentifier(),
                        sender.isRegistrationAssertionRequired(),
                        context.getEndpoint(), context.getMessageContentType(), context.getMessagePayload(),
                        tokenFuture.result(), null);

                if (context.isRemotelySettled()) {
                    // client uses AT_MOST_ONCE delivery semantics -> fire and forget
                    return sender.send(downstreamMessage);
                } else {
                    // client uses AT_LEAST_ONCE delivery semantics
                    return sender.sendAndWaitForOutcome(downstreamMessage);
                }
            } else {
                // this adapter is not enabled for tenant
                return Future.failedFuture(
                        new ClientErrorException(HttpURLConnection.HTTP_FORBIDDEN,
                                String.format("This adapter is not enabled for tenant [tenantId: %s].",
                                        context.getTenantId())));
            }
        }).compose(downstreamDelivery -> {
            LOG.trace("Successfully process message for Device [deviceId: {}] with Tenant [tenantId: {}]",
                    context.getDeviceId(),
                    context.getTenantId());
            if (context.isRemotelySettled()) {
                // client uses AT_MOST_ONCE delivery semantics
                // accept & settle the message regardless of whether
                // the downstream peer accepted/rejected the message.
                context.accept();
            } else {
                // client uses AT_LEAST_ONCE delivery semantics
                // forward disposition received from downstream peer back to the client device.
                context.updateDelivery(downstreamDelivery);
            }
            return Future.<Void> succeededFuture();
        }).recover(t -> {
            LOG.debug("Cannot process message for Device [tenantId: {}, deviceId: {}, endpoint: {}]",
                    context.getTenantId(),
                    context.getDeviceId(),
                    context.getEndpoint(), t);
            return Future.failedFuture(t);
        });
    }

    private Future<Void> doUploadCommandResponseMessage(final AmqpContext context) {

        final String replyTo = context.getMessage().getAddress();
        final String correlationId = Optional.ofNullable(context.getMessage().getCorrelationId())
                .map(id -> {
                    if (id instanceof String) {
                        return (String) id;
                    } else {
                        return null;
                    }
                }).orElse(null);

        final String status = MessageHelper.getApplicationProperty(context.getMessage().getApplicationProperties(),
                MessageHelper.APP_PROPERTY_STATUS, String.class);

        try {
            final Integer statusCode = Integer.parseInt(status);
            final CommandResponse commandResponse = CommandResponse.from(context.getMessagePayload(),
                    context.getMessageContentType(), statusCode, correlationId, replyTo);

            if (commandResponse == null) {
                return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                        "malformed command response address"));
            } else {
                return sendCommandResponse(context.getTenantId(), commandResponse, null)
                        .map(delivery -> {
                            LOG.trace(
                                    "successfully forwarded command response from device [tenant-id: {}, device-id: {}]",
                                    context.getTenantId(), context.getDeviceId());
                            context.updateDelivery(delivery);
                            return (Void) null;
                        }).recover(t -> {
                            LOG.debug(
                                    "Failed to forward command response from Device [tenantId: {}, deviceId: {}, endpoint: {}]",
                                    context.getTenantId(),
                                    context.getDeviceId(),
                                    context.getEndpoint(), t);

                            return Future.failedFuture(t);
                        });
            }
        } catch (NumberFormatException e) {
            return Future
                    .failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "invalid status code"));
        }
    }

    /**
     * Closes the specified receiver link.
     * 
     * @param link The link to close.
     */
    private <T extends ProtonLink<T>> void onLinkDetach(final ProtonLink<T> link) {
        LOG.debug("closing link [{}]", link.getName());
        link.close();
    }

    /**
     * This method validates that a client tries to publish a message to a supported endpoint. If the endpoint is supported,
     * this method also validates that the quality service of the supported endpoint.
     * 
     * @param address The message address.
     * @param delivery The delivery through which this adapter receives the message.
     *
     * @return A future with the address upon success or a failed future.
     */
    Future<ResourceIdentifier> validateEndpoint(final String address, final ProtonDelivery delivery) {

        if (address == null || address.isEmpty()) {
            return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Message address cannot be null or empty"));
        }

        final Future<ResourceIdentifier> result = Future.future();
        final ResourceIdentifier resource = ResourceIdentifier.fromString(address);

        switch (EndpointType.fromString(resource.getEndpoint())) {
        case TELEMETRY:
            result.complete(resource);
            break;
        case EVENT:
            if (delivery.remotelySettled()) {
                result.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST,
                        "The Event endpoint only supports unsettled delivery for messages"));
            } else {
                result.complete(resource);
            }
            break;
        case CONTROL:
            // for publishing a response to a command
            result.complete(resource);
            break;
        default:
            LOG.error("Endpoint with [name: {}] is not supported by this adapter ",
                    resource.getEndpoint());
            result.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "unsupported endpoint"));
            break;
        }
        return result;
    }

    /**
     * Validates the address contained in an AMQP 1.0 message.
     * 
     * @param address The message address to validate.
     * @param authenticatedDevice The authenticated device.
     * 
     * @return A succeeded future with the valid message address or a failed future if the message address is not valid.
     */
    private Future<ResourceIdentifier> validateAddress(final ResourceIdentifier address, final Device authenticatedDevice) {
        final Future<ResourceIdentifier> result = Future.future();

        if (authenticatedDevice == null) {
            if (address.getTenantId() == null || address.getResourceId() == null) {
                throw new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid address for unauthenticated devices");
            } else {
                result.complete(address);
            }
        } else {
            if (address.getTenantId() != null && address.getResourceId() == null) {
                result.fail(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST, "address of authenticated message must not contain tenant ID only"));
            } else if (address.getTenantId() == null && address.getResourceId() == null) {
                final ResourceIdentifier resource = ResourceIdentifier.from(address.getEndpoint(),
                        authenticatedDevice.getTenantId(), authenticatedDevice.getDeviceId());
                result.complete(resource);
            } else {
                result.complete(address);
            }
        }
        return result;
    }

    // -------------------------------------------< AbstractServiceBase >---
    /**
     * {@inheritDoc}
     */
    @Override
    public int getPortDefaultValue() {
        return DEFAULT_SECURE_PORT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getInsecurePortDefaultValue() {
        return DEFAULT_INSECURE_PORT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getActualPort() {
        return secureServer != null ? secureServer.actualPort() : Constants.PORT_UNCONFIGURED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getActualInsecurePort() {
        return insecureServer != null ? insecureServer.actualPort() : Constants.PORT_UNCONFIGURED;
    }

}
