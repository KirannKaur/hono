/**
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


package org.eclipse.hono.tests.coap.lwm2m;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.ClientHandshaker;
import org.eclipse.californium.scandium.dtls.DTLSSession;
import org.eclipse.californium.scandium.dtls.HandshakeException;
import org.eclipse.californium.scandium.dtls.Handshaker;
import org.eclipse.californium.scandium.dtls.ResumingClientHandshaker;
import org.eclipse.californium.scandium.dtls.ResumingServerHandshaker;
import org.eclipse.californium.scandium.dtls.ServerHandshaker;
import org.eclipse.californium.scandium.dtls.SessionAdapter;
import org.eclipse.californium.scandium.dtls.SessionId;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.hono.service.management.tenant.Tenant;
import org.eclipse.hono.tests.IntegrationTestSupport;
import org.eclipse.hono.tests.coap.CoapTestBase;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.engine.DefaultRegistrationEngineFactory;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.client.resource.listener.ObjectsListenerAdapter;
import org.eclipse.leshan.client.servers.ServerIdentity;
import org.eclipse.leshan.core.LwM2mId;
import org.eclipse.leshan.core.californium.DefaultEndpointFactory;
import org.eclipse.leshan.core.model.LwM2mModel;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeEncoder;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.request.RegisterRequest;
import org.eclipse.leshan.core.request.UpdateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.pointer.JsonPointer;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Integration tests for sending commands to a device connected to the CoAP adapter.
 *
 */
@ExtendWith(VertxExtension.class)
public class LwM2mIT extends CoapTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(LwM2mIT.class);

    // Initialize model
    private final List<ObjectModel> models = ObjectLoader.loadDefault();
    private final LwM2mModel model = new StaticModel(models);
    private final LwM2mNodeEncoder nodeEncoder = new DefaultLwM2mNodeEncoder();
    private final LwM2mNodeDecoder nodeDecoder = new DefaultLwM2mNodeDecoder();

    private LeshanClient client;
    private ExampleDevice deviceObject;
    private ExampleFirmware firmwareObject;

    @BeforeEach
    void createClientObjects() {
        deviceObject = new ExampleDevice();
        firmwareObject = new ExampleFirmware();
    }

    @AfterEach
    void stopClient() {
        if (client != null) {
            client.destroy(true);
        }
    }

    /**
     * Verifies that a LwM2M device using binding mode <em>U</em> can successfully register, update its
     * registration and unregister using the CoAP adapter's resource directory resource.
     * <p>
     * Also verifies that registration, updating the registration and de-registration trigger empty downstream
     * notifications with a TTD reflecting the device's lifetime and that the adapter establishes an
     * observation on the device's LwM2M <em>Device</em> object.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    public void testNonQueueModeRegistration(final VertxTestContext ctx) {

        final Checkpoint deviceAcceptsCommands = ctx.checkpoint(2);
        final Checkpoint deviceNoLongerAcceptsCommands = ctx.checkpoint();
        final Promise<Void> deviceNotificationReceived = Promise.promise();

        final int lifetime = 24 * 60 * 60; // 24h
        final int communicationPeriod = 300_000; // update registration every 5m
        final String endpoint = "test-device";

        helper.registry.addPskDeviceForTenant(tenantId, new Tenant(), deviceId, SECRET)
            .compose(ok -> helper.applicationClient.createEventConsumer(
                    tenantId,
                    msg -> {
                        final String origAddress = msg.getProperties().getProperty(MessageHelper.APP_PROPERTY_ORIG_ADDRESS, String.class);
                        logger.info("received event [content-type: {}, orig_address: {}]", msg.getContentType(), origAddress);
                        Optional.ofNullable(msg.getTimeTillDisconnect())
                            .ifPresent(ttd -> {
                                if (ttd == lifetime) {
                                    deviceAcceptsCommands.flag();
                                } else if (ttd == 0) {
                                    deviceNoLongerAcceptsCommands.flag();
                                }
                            });
                    },
                    remoteClose -> {}))
            .compose(eventConsumer -> helper.applicationClient.createTelemetryConsumer(
                    tenantId,
                    msg -> {
                        final String origAddress = msg.getProperties().getProperty(MessageHelper.APP_PROPERTY_ORIG_ADDRESS, String.class);
                        logger.info("received telemetry message [content-type: {}, orig_address: {}]", msg.getContentType(), origAddress);
                        if (msg.getContentType().startsWith("application/vnd.oma.lwm2m")) {
                            Optional.ofNullable(origAddress)
                                .filter(s -> s.equals("/3/0"))
                                .ifPresent(s -> deviceNotificationReceived.complete());
                        }
                    },
                    remoteClose -> {}))
            .compose(eventConsumer -> {
                final Promise<LeshanClient> result = Promise.promise();
                try {
                    final var leshanClient = createLeshanClient(endpoint, BindingMode.U, lifetime, communicationPeriod);
                    result.complete(leshanClient);
                } catch (final CertificateEncodingException e) {
                    result.fail(e);
                }
                return result.future();
            })
            .compose(leshanClient -> {
                client = leshanClient;
                final Promise<Void> registered = Promise.promise();
                client.addObserver(new LeshanClientObserver() {
                    @Override
                    public void onRegistrationSuccess(
                            final ServerIdentity server,
                            final RegisterRequest request,
                            final String registrationID) {
                        registered.complete();
                    }
                });
                client.start();
                return CompositeFuture.all(
                        registered.future(),
                        deviceNotificationReceived.future());
            })
            .compose(ok -> {
                final Promise<Void> updated = Promise.promise();
                client.addObserver(new LeshanClientObserver() {
                    @Override
                    public void onUpdateSuccess(
                            final ServerIdentity server,
                            final UpdateRequest request) {
                        updated.complete();
                    }
                });
                client.triggerRegistrationUpdate();
                return updated.future();
            })
            .onSuccess(ok -> client.stop(true));
    }

    /**
     * Verifies that a LwM2M device using binding mode <em>U</em> can successfully register, update its
     * registration and unregister using the CoAP adapter's resource directory resource.
     * <p>
     * Also verifies that registration, updating the registration and de-registration trigger empty downstream
     * notifications with a TTD reflecting the device's lifetime and that the adapter establishes observations
     * for resources configured for the device.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    @Timeout(value = 5, timeUnit = TimeUnit.SECONDS)
    public void testFirmwarePackageUriUpdate(final VertxTestContext ctx) {

        final int lifetime = 24 * 60 * 60; // 24h
        final int communicationPeriod = 300_000; // update registration every 5m
        final String endpoint = "test-device";
        final Promise<Void> stateDownloadingReached = Promise.promise();
        final Promise<Void> updateTriggered = Promise.promise();
        final Promise<Void> stateInstallingReached = Promise.promise();
        final var stateFinishedReached = ctx.checkpoint();
        final Promise<String> packageUriSet = Promise.promise();
        final AtomicReference<String> currentStatus = new AtomicReference<>();
        final var uri = "https://firmware.eclipse.org/tenant/device";

        helper.registry.addPskDeviceForTenant(tenantId, new Tenant(), deviceId, SECRET)
            .compose(ok -> helper.applicationClient.createEventConsumer(
                    tenantId,
                    msg -> {
                        final String origAddress = msg.getProperties().getProperty(MessageHelper.APP_PROPERTY_ORIG_ADDRESS, String.class);
                        logger.info("received event [content-type: {}, orig_address: {}]", msg.getContentType(), origAddress);
                        if (msg.getContentType().equals("application/vnd.eclipse.ditto+json")) {
                            ctx.verify(() -> {
                                final var dittoMessage = msg.getPayload().toJsonObject();
                                assertThat(dittoMessage.getString("topic"))
                                    .isEqualTo(String.format("test/%s:%s/things/twin/commands/merge", tenantId, deviceId));
                                final String newStatus = (String) JsonPointer.from("/value/lastOperation/status").queryJson(dittoMessage);
                                if (newStatus.equals(currentStatus.get())) {
                                    LOG.info("ignoring notification with unchanged status");
                                } else {
                                    LOG.info("received status update notification from device [current status: {}, new status: {}]",
                                            currentStatus.get(), newStatus);
                                    if (currentStatus.compareAndSet(null, newStatus)) {
                                        assertThat(newStatus).isEqualTo("STARTED");
                                        LOG.info("firmware update process has been initiated");
                                    } else if (currentStatus.get().equals("STARTED")) {
                                        assertThat(newStatus).isEqualTo("DOWNLOADING");
                                        currentStatus.set(newStatus);
                                        LOG.info("device has started downloading firmware");
                                        stateDownloadingReached.complete();
                                    } else if (currentStatus.get().equals("DOWNLOADING")) {
                                        assertThat(newStatus).isEqualTo("DOWNLOADED");
                                        currentStatus.set(newStatus);
                                        LOG.info("device has successfully downloaded firmware, now sending install command");
                                        final JsonObject cmd = newFirmwareCommand(
                                                "install",
                                                new JsonObject()
                                                    .put("correlationId", "firmware-update")
                                                    .put("softwareModules", newSoftwareModules(uri)));
                                        helper.applicationClient.sendOneWayCommand(
                                                tenantId,
                                                deviceId,
                                                "install firmware",
                                                "application/vnd.eclipse.ditto+json",
                                                cmd.toBuffer(),
                                                null,
                                                null);
                                    } else if (currentStatus.get().equals("DOWNLOADED")) {
                                        assertThat(newStatus).isEqualTo("INSTALLING");
                                        currentStatus.set(newStatus);
                                        LOG.info("device has started to install firmware");
                                        stateInstallingReached.complete();
                                    } else if (currentStatus.get().equals("INSTALLING")) {
                                        assertThat(newStatus).isEqualTo("FINISHED_SUCCESS");
                                        currentStatus.set(newStatus);
                                        LOG.info("device has successfully installed new firmware");
                                        stateFinishedReached.flag();
                                    } else {
                                        LOG.info("failed to transition state");
                                    }
                                    LOG.info("current status: {}", currentStatus.get());
                                }
                            });
                        }
//                        if (origAddress != null) {
//                            final LwM2mPath path = new LwM2mPath(origAddress);
//                            if (path.isObjectInstance() && path.getObjectId() == 5
//                                    && msg.getContentType().equals(ContentFormat.TLV.getMediaType())) {
//                                ctx.verify(() -> {
//                                    final LwM2mObjectInstance obj = nodeDecoder.decode(
//                                            msg.getPayload().getBytes(),
//                                            ContentFormat.fromMediaType(msg.getContentType()),
//                                            path,
//                                            model,
//                                            LwM2mObjectInstance.class);
//                                    if (firmware.compareAndSet(null, obj)) {
//                                        // first notification
//                                        assertThat(obj.getResource(3).getValue())
//                                            .as("\"state\" is \"idle\"")
//                                            .isEqualTo(0L);
//                                    } else {
//                                        // second notification
//                                        assertThat(obj.getResource(3).getValue())
//                                            .as("\"state\" is \"downloading\"")
//                                            .isEqualTo(1L);
//                                        LOG.info("device has started downloading firmware");
//                                    }
//                                });
//                                stateDownloadingReached.flag();
//                            }
//                        }
                    },
                    remoteClose -> {}))
            .compose(eventConsumer -> helper.applicationClient.createTelemetryConsumer(
                    tenantId,
                    msg -> {
                        final String origAddress = msg.getProperties().getProperty(MessageHelper.APP_PROPERTY_ORIG_ADDRESS, String.class);
                        logger.info("received telemetry message [content-type: {}, orig_address: {}]", msg.getContentType(), origAddress);
                    },
                    remoteClose -> {}))
            .compose(telemetryConsumer -> {
                final Promise<LeshanClient> result = Promise.promise();
                try {
                    final var leshanClient = createLeshanClient(endpoint, BindingMode.U, lifetime, communicationPeriod);
                    result.complete(leshanClient);
                } catch (final CertificateEncodingException e) {
                    result.fail(e);
                }
                return result.future();
            })
            .compose(leshanClient -> {
                client = leshanClient;
                final Promise<Void> registered = Promise.promise();
                client.addObserver(new LeshanClientObserver() {
                    @Override
                    public void onRegistrationSuccess(
                            final ServerIdentity server,
                            final RegisterRequest request,
                            final String registrationID) {
                        registered.complete();
                    }
                });
                client.start();
                return registered.future();
            })
            // WHEN an application sends a command to set the device's Firmware package URI
            .compose(registered -> {
                firmwareObject.setPackageUriHandler(s -> {
                    packageUriSet.complete(uri);
                });
                firmwareObject.setUpdateHandler(start -> {
                    updateTriggered.complete();
                });
                final JsonObject cmd = newFirmwareCommand(
                        "download",
                        new JsonObject()
                            .put("correlationId", "firmware-update")
                            .put("softwareModules", newSoftwareModules(uri)));
                return CompositeFuture.all(
                        packageUriSet.future(),
                        helper.applicationClient.sendOneWayCommand(
                            tenantId,
                            deviceId,
                            "download firmware",
                            "application/vnd.eclipse.ditto+json",
                            cmd.toBuffer(),
                            null,
                            null));
            })
            .compose(ok -> {
                // set state to "downloading"
                firmwareObject.setState(1);
                // and trigger notification
                firmwareObject.fireResourcesChange(3);
                return stateDownloadingReached.future();
            })
            .compose(ok -> {
                // set state to "downloaded"
                firmwareObject.setState(2);
                // and trigger notification
                firmwareObject.fireResourcesChange(3);
                return updateTriggered.future();
            }).compose(ok -> {
                // set state to "updating"
                firmwareObject.setState(3);
                // and trigger notification
                firmwareObject.fireResourcesChange(3);
                return stateInstallingReached.future();
            }).onSuccess(ok -> {
                // set state to "idle"
                firmwareObject.setState(0);
                // set result to"succeeded"
                firmwareObject.setUpdateResult(1);
                // and trigger notification
                firmwareObject.fireResourcesChange(3, 5);
            });
    }

    private JsonObject newFirmwareCommand(
            final String command,
            final JsonObject value) {

        final JsonObject commandPayload = new JsonObject()
                .put("topic",  String.format("test/%s:%s/things/twins/events/modified", tenantId, deviceId))
                .put("headers", new JsonObject())
                .put("path", "features/softwareUpdateable/properties/" + command)
                .put("value", value);
        return commandPayload;
    }

    private JsonArray newSoftwareModules(final String uri) {
        return new JsonArray().add(new JsonObject()
                .put("softwareModule", new JsonObject()
                        .put("name", "bumlux-fw")
                        .put("version", "v1.5.3"))
                .put("artifacts", new JsonArray().add(new JsonObject()
                        .put("download", new JsonObject()
                                .put("HTTPS", new JsonObject().put("url", uri))))));
    }

    private LeshanClient createLeshanClient(
            final String endpoint,
            final BindingMode bindingMode,
            final int lifetime,
            final Integer communicationPeriod) throws CertificateEncodingException {

        final var serverURI = getCoapsRequestUri(null);

        return createLeshanClient(
                endpoint,
                bindingMode,
                null,
                lifetime,
                communicationPeriod,
                serverURI.toString(),
                IntegrationTestSupport.getUsername(deviceId, tenantId).getBytes(StandardCharsets.UTF_8),
                SECRET.getBytes(StandardCharsets.UTF_8),
                null,
                null,
                null,
                null,
                null,
                false, // do not support deprecated ciphers
                false, // do not perform new DTLS handshake before updating registration
                false, // try to resume DTLS session
                null); // use all supported ciphers
    }

    private LeshanClient createLeshanClient(
            final String endpoint,
            final BindingMode bindingMode,
            final Map<String, String> additionalAttributes,
            final int lifetime,
            final Integer communicationPeriod,
            final String serverURI,
            final byte[] pskIdentity,
            final byte[] pskKey,
            final PrivateKey clientPrivateKey,
            final PublicKey clientPublicKey,
            final PublicKey serverPublicKey,
            final X509Certificate clientCertificate,
            final X509Certificate serverCertificate,
            final boolean supportDeprecatedCiphers,
            final boolean reconnectOnUpdate,
            final boolean forceFullhandshake,
            final List<CipherSuite> ciphers) throws CertificateEncodingException {


        // Initialize object list
        final ObjectsInitializer initializer = new ObjectsInitializer(model);
        if (pskIdentity != null) {
            initializer.setInstancesForObject(LwM2mId.SECURITY, Security.psk(serverURI, 123, pskIdentity, pskKey));
            initializer.setInstancesForObject(LwM2mId.SERVER, new Server(123, lifetime, bindingMode, false));
        } else if (clientPublicKey != null) {
            initializer.setInstancesForObject(LwM2mId.SECURITY, Security.rpk(serverURI, 123, clientPublicKey.getEncoded(),
                    clientPrivateKey.getEncoded(), serverPublicKey.getEncoded()));
            initializer.setInstancesForObject(LwM2mId.SERVER, new Server(123, lifetime, bindingMode, false));
        } else if (clientCertificate != null) {
            initializer.setInstancesForObject(LwM2mId.SECURITY, Security.x509(serverURI, 123, clientCertificate.getEncoded(),
                    clientPrivateKey.getEncoded(), serverCertificate.getEncoded()));
            initializer.setInstancesForObject(LwM2mId.SERVER, new Server(123, lifetime, bindingMode, false));
        } else {
            initializer.setInstancesForObject(LwM2mId.SECURITY, Security.noSec(serverURI, 123));
            initializer.setInstancesForObject(LwM2mId.SERVER, new Server(123, lifetime, bindingMode, false));
        }
        initializer.setInstancesForObject(LwM2mId.DEVICE, deviceObject);
        initializer.setInstancesForObject(LwM2mId.FIRMWARE, firmwareObject);

        final List<LwM2mObjectEnabler> enablers = initializer.createAll();

        // Create CoAP Config
        final NetworkConfig coapConfig = LeshanClientBuilder.createDefaultNetworkConfig();

        // Create DTLS Config
        final DtlsConnectorConfig.Builder dtlsConfig = new DtlsConnectorConfig.Builder();
        dtlsConfig.setRecommendedCipherSuitesOnly(!supportDeprecatedCiphers);
        if (ciphers != null) {
            dtlsConfig.setSupportedCipherSuites(ciphers);
        }

        // Configure Registration Engine
        final DefaultRegistrationEngineFactory engineFactory = new DefaultRegistrationEngineFactory();
        engineFactory.setCommunicationPeriod(communicationPeriod);
        engineFactory.setReconnectOnUpdate(reconnectOnUpdate);
        engineFactory.setResumeOnConnect(!forceFullhandshake);

        // configure EndpointFactory
        final DefaultEndpointFactory endpointFactory = new DefaultEndpointFactory("LWM2M CLIENT") {
            @Override
            protected Connector createSecuredConnector(final DtlsConnectorConfig dtlsConfig) {

                return new DTLSConnector(dtlsConfig) {
                    @Override
                    protected void onInitializeHandshaker(final Handshaker handshaker) {
                        handshaker.addSessionListener(new SessionAdapter() {

                            private SessionId sessionIdentifier = null;

                            @Override
                            public void handshakeStarted(final Handshaker handshaker) throws HandshakeException {
                                if (handshaker instanceof ResumingServerHandshaker) {
                                    LOG.info("DTLS abbreviated Handshake initiated by server : STARTED ...");
                                } else if (handshaker instanceof ServerHandshaker) {
                                    LOG.info("DTLS Full Handshake initiated by server : STARTED ...");
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    sessionIdentifier = handshaker.getSession().getSessionIdentifier();
                                    LOG.info("DTLS abbreviated Handshake initiated by client : STARTED ...");
                                } else if (handshaker instanceof ClientHandshaker) {
                                    LOG.info("DTLS Full Handshake initiated by client : STARTED ...");
                                }
                            }

                            @Override
                            public void sessionEstablished(final Handshaker handshaker, final DTLSSession establishedSession)
                                    throws HandshakeException {
                                if (handshaker instanceof ResumingServerHandshaker) {
                                    LOG.info("DTLS abbreviated Handshake initiated by server : SUCCEED");
                                } else if (handshaker instanceof ServerHandshaker) {
                                    LOG.info("DTLS Full Handshake initiated by server : SUCCEED");
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    if (sessionIdentifier != null && sessionIdentifier
                                            .equals(handshaker.getSession().getSessionIdentifier())) {
                                        LOG.info("DTLS abbreviated Handshake initiated by client : SUCCEED");
                                    } else {
                                        LOG.info(
                                                "DTLS abbreviated turns into Full Handshake initiated by client : SUCCEED");
                                    }
                                } else if (handshaker instanceof ClientHandshaker) {
                                    LOG.info("DTLS Full Handshake initiated by client : SUCCEED");
                                }
                            }

                            @Override
                            public void handshakeFailed(final Handshaker handshaker, final Throwable error) {
                                // get cause
                                final String cause;
                                if (error != null) {
                                    if (error.getMessage() != null) {
                                        cause = error.getMessage();
                                    } else {
                                        cause = error.getClass().getName();
                                    }
                                } else {
                                    cause = "unknown cause";
                                }

                                if (handshaker instanceof ResumingServerHandshaker) {
                                    LOG.info("DTLS abbreviated Handshake initiated by server : FAILED ({})", cause);
                                } else if (handshaker instanceof ServerHandshaker) {
                                    LOG.info("DTLS Full Handshake initiated by server : FAILED ({})", cause);
                                } else if (handshaker instanceof ResumingClientHandshaker) {
                                    LOG.info("DTLS abbreviated Handshake initiated by client : FAILED ({})", cause);
                                } else if (handshaker instanceof ClientHandshaker) {
                                    LOG.info("DTLS Full Handshake initiated by client : FAILED ({})", cause);
                                }
                            }
                        });
                    }
                };
            }
        };

        // Create client
        final LeshanClientBuilder builder = new LeshanClientBuilder(endpoint);
        builder.setObjects(enablers);
        builder.setCoapConfig(coapConfig);
        builder.setDtlsConfig(dtlsConfig);
        builder.setRegistrationEngineFactory(engineFactory);
        builder.setEndpointFactory(endpointFactory);
        builder.setDecoder(nodeDecoder);
        builder.setEncoder(nodeEncoder);
        builder.setAdditionalAttributes(additionalAttributes);
        final LeshanClient client = builder.build();
        client.getObjectTree().addListener(new ObjectsListenerAdapter() {
            @Override
            public void objectRemoved(final LwM2mObjectEnabler object) {
                LOG.info("Object {} disabled.", object.getId());
            }

            @Override
            public void objectAdded(final LwM2mObjectEnabler object) {
                LOG.info("Object {} enabled.", object.getId());
            }
        });

        return client;
    }

}
