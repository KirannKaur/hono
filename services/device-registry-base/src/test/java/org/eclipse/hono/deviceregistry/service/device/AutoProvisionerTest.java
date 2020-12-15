/**
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
 */
package org.eclipse.hono.deviceregistry.service.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.DownstreamSender;
import org.eclipse.hono.client.DownstreamSenderFactory;
import org.eclipse.hono.deviceregistry.service.tenant.TenantInformationService;
import org.eclipse.hono.service.management.Id;
import org.eclipse.hono.service.management.OperationResult;
import org.eclipse.hono.service.management.device.Device;
import org.eclipse.hono.service.management.device.DeviceManagementService;
import org.eclipse.hono.service.management.device.DeviceStatus;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.EventConstants;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.RegistryManagementConstants;
import org.eclipse.hono.util.TenantObject;
import org.eclipse.hono.util.TenantResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
class AutoProvisionerTest {

    private static final String GATEWAY_ID = "barfoo4711";
    private static final String GATEWAY_GROUP_ID = "barfoospam4711";
    private static final String DEVICE_ID = "foobar42";
    private static final Device NEW_EDGE_DEVICE = new Device();

    private Span span;
    private Vertx vertx;
    private DeviceManagementService deviceManagementService;
    private TenantInformationService tenantInformationService;
    private DownstreamSender sender;

    private AutoProvisioner autoProvisioner;

    /**
     * Sets up the fixture.
     */
    @BeforeEach
    public void setUp() {
        tenantInformationService = mock(TenantInformationService.class);
        when(tenantInformationService.getTenant(anyString(), any(Span.class)))
                .thenAnswer(invocation -> Future.succeededFuture(TenantResult.from(HttpURLConnection.HTTP_OK,
                        TenantObject.from(invocation.getArgument(0), true))));

        span = mock(Span.class);
        when(span.context()).thenReturn(mock(SpanContext.class));
        vertx = mock(Vertx.class);
        // run timers immediately
        when(vertx.setTimer(anyLong(), any(Handler.class))).thenAnswer(invocation -> {
            final Handler<Void> task = invocation.getArgument(1);
            task.handle(null);
            return 1L;
        });

        deviceManagementService = mock(DeviceManagementService.class);

        autoProvisioner = new AutoProvisioner();
        autoProvisioner.setTenantInformationService(tenantInformationService);
        autoProvisioner.setDeviceManagementService(deviceManagementService);
        autoProvisioner.setVertx(vertx);
        autoProvisioner.setConfig(new AutoProvisionerConfigProperties());

        final DownstreamSenderFactory downstreamSenderFactoryMock = mock(DownstreamSenderFactory.class);
        sender = mock(DownstreamSender.class);
        when(sender.sendAndWaitForOutcome(any(Message.class), any())).thenReturn(Future.succeededFuture());
        when(downstreamSenderFactoryMock.getOrCreateEventSender(anyString())).thenReturn(Future.succeededFuture(sender));

        autoProvisioner.setDownstreamSenderFactory(downstreamSenderFactoryMock);

        when(deviceManagementService
                .updateDevice(eq(Constants.DEFAULT_TENANT), eq(DEVICE_ID), any(Device.class), any(), any()))
                .thenReturn(Future.succeededFuture(OperationResult.empty(HttpURLConnection.HTTP_OK)));
    }


    /**
     * Verifies a successful auto-provisioning call.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testSuccessfulAutoProvisioning(final VertxTestContext ctx) {
        mockAssertRegistration(GATEWAY_ID, Collections.singletonList(GATEWAY_GROUP_ID), Collections.singletonList(
                RegistryManagementConstants.AUTHORITY_AUTO_PROVISIONING_ENABLED));
        mockAssertRegistration(DEVICE_ID, true);
        mockAddEdgeDevice(HttpURLConnection.HTTP_CREATED);

        autoProvisioner.performAutoProvisioning(Constants.DEFAULT_TENANT, DEVICE_ID, GATEWAY_ID, NEW_EDGE_DEVICE, span.context())
                .onComplete(ctx.succeeding(device -> {
                    ctx.verify(() -> verifySuccessfulAutoProvisioning());
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that auto-provisioning still succeeds if the device to be auto-provisioned has already been created
     * (e.g. by a concurrently running request) and the notification has already been sent.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testAutoProvisioningIsSuccessfulForAlreadyPresentEdgeDevice(final VertxTestContext ctx) {
        mockAssertRegistration(GATEWAY_ID, Collections.singletonList(GATEWAY_GROUP_ID), Collections.singletonList(RegistryManagementConstants.AUTHORITY_AUTO_PROVISIONING_ENABLED));
        mockAssertRegistration(DEVICE_ID, true);
        mockAddEdgeDevice(HttpURLConnection.HTTP_CONFLICT);

        autoProvisioner.performAutoProvisioning(Constants.DEFAULT_TENANT, DEVICE_ID, GATEWAY_ID, NEW_EDGE_DEVICE, span.context())
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> {
                        verify(deviceManagementService).createDevice(any(), any(), any(), any());

                        verify(sender, never()).sendAndWaitForOutcome(any(), any());
                        verify(deviceManagementService, never()).updateDevice(eq(Constants.DEFAULT_TENANT), eq(DEVICE_ID), any(), any(), any());
                    });
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies that auto-provisioning still succeeds if the flag device in the device registration cannot be updated
     * after the device notification has been sent. In that case another device notification will be sent when the next
     * telemetry message is received, i.e. the application will receive a duplicate event.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testAutoProvisioningIsSuccessfulWhenUpdateOfNotificationFlagFails(final VertxTestContext ctx) {
        mockAssertRegistration(GATEWAY_ID, Collections.singletonList(GATEWAY_GROUP_ID), Collections.singletonList(RegistryManagementConstants.AUTHORITY_AUTO_PROVISIONING_ENABLED));
        mockAssertRegistration(DEVICE_ID, true);
        mockAddEdgeDevice(HttpURLConnection.HTTP_CREATED);
        when(deviceManagementService
                .updateDevice(eq(Constants.DEFAULT_TENANT), eq(DEVICE_ID), any(Device.class), any(), any()))
                .thenReturn(Future.succeededFuture(OperationResult.empty(HttpURLConnection.HTTP_INTERNAL_ERROR)));

        autoProvisioner.performAutoProvisioning(Constants.DEFAULT_TENANT, DEVICE_ID, GATEWAY_ID, NEW_EDGE_DEVICE, span.context())
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> verifySuccessfulAutoProvisioning());
                    ctx.completeNow();
                }));
    }

    /**
     * Verifies the event to the northbound application is re-sent, if it is not sent yet when auto-provisioning
     * is performed for an already present edge device.
     *
     * @param ctx The vert.x test context.
     */
    @Test
    public void testAutoProvisioningResendsDeviceNotificationForAlreadyPresentEdgeDevice(final VertxTestContext ctx) {
        mockAssertRegistration(GATEWAY_ID, Collections.singletonList(GATEWAY_GROUP_ID), Collections.singletonList(RegistryManagementConstants.AUTHORITY_AUTO_PROVISIONING_ENABLED));
        mockAssertRegistration(DEVICE_ID, false);
        mockAddEdgeDevice(HttpURLConnection.HTTP_CONFLICT);

        autoProvisioner.performAutoProvisioning(Constants.DEFAULT_TENANT, DEVICE_ID, GATEWAY_ID, new Device(), span.context())
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> {
                        verify(deviceManagementService).createDevice(any(), any(), any(), any());

                        final ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
                        verify(sender).sendAndWaitForOutcome(messageArgumentCaptor.capture(), any());
                        // verify sending the event was done as part of running the timer task
                        verify(vertx).setTimer(anyLong(), notNull());

                        final ArgumentCaptor<Device> updatedDeviceArgumentCaptor = ArgumentCaptor.forClass(Device.class);
                        verify(deviceManagementService).updateDevice(eq(Constants.DEFAULT_TENANT), eq(AutoProvisionerTest.DEVICE_ID), updatedDeviceArgumentCaptor.capture(), any(), any());

                        final Device updatedDevice = updatedDeviceArgumentCaptor.getValue();
                        assertThat(updatedDevice.getStatus().isAutoProvisioningNotificationSent()).isTrue();

                        final Map<String, Object> applicationProperties = messageArgumentCaptor.getValue().getApplicationProperties().getValue();
                        verifyApplicationProperties(AutoProvisionerTest.GATEWAY_ID, AutoProvisionerTest.DEVICE_ID, applicationProperties);

                    });
                    ctx.completeNow();
                }));
    }

    private void mockAssertRegistration(final String deviceId, final List<String> memberOf, final List<String> authorities) {
        final Device registeredGateway = new Device()
                .setMemberOf(memberOf)
                .setAuthorities(new HashSet<>(authorities));

        when(deviceManagementService.readDevice(eq(Constants.DEFAULT_TENANT), eq(deviceId), any(Span.class)))
                .thenReturn(Future.succeededFuture(
                        OperationResult.ok(HttpURLConnection.HTTP_OK, registeredGateway, Optional.empty(), Optional.empty())));

    }

    private void mockAssertRegistration(final String deviceId, final boolean autoProvisioningNotificationSent) {
        when(deviceManagementService.readDevice(eq(Constants.DEFAULT_TENANT), eq(deviceId), any(Span.class)))
                .thenReturn(Future.succeededFuture(newRegistrationResult(autoProvisioningNotificationSent)));

    }

    private OperationResult<Device> newRegistrationResult(final boolean autoProvisioningNotificationSent) {
        final Device edgeDevice = new Device()
                .setVia(Collections.singletonList(AutoProvisionerTest.GATEWAY_ID))
                .setStatus(new DeviceStatus()
                        .setAutoProvisioned(true)
                        .setAutoProvisioningNotificationSent(autoProvisioningNotificationSent));

        return OperationResult.ok(HttpURLConnection.HTTP_OK, edgeDevice, Optional.empty(), Optional.empty());
    }

    private void verifySuccessfulAutoProvisioning() {
        final ArgumentCaptor<Device> registeredDeviceArgumentCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceManagementService).createDevice(any(), any(), registeredDeviceArgumentCaptor.capture(), any());

        final Device registeredDevice = registeredDeviceArgumentCaptor.getValue();
        assertThat(registeredDevice).isEqualTo(NEW_EDGE_DEVICE);

        final ArgumentCaptor<Message> messageArgumentCaptor = ArgumentCaptor.forClass(Message.class);
        verify(sender).sendAndWaitForOutcome(messageArgumentCaptor.capture(), any());

        final ArgumentCaptor<Device> updatedDeviceArgumentCaptor = ArgumentCaptor.forClass(Device.class);
        verify(deviceManagementService).updateDevice(eq(Constants.DEFAULT_TENANT), eq(DEVICE_ID), updatedDeviceArgumentCaptor.capture(), any(), any());

        final Device updatedDevice = updatedDeviceArgumentCaptor.getValue();
        assertThat(updatedDevice.getStatus().isAutoProvisioningNotificationSent()).isTrue();

        final Map<String, Object> applicationProperties = messageArgumentCaptor.getValue().getApplicationProperties().getValue();
        verifyApplicationProperties(GATEWAY_ID, DEVICE_ID, applicationProperties);
    }

    private void verifyApplicationProperties(final String gatewayId, final String deviceId, final Map<String, Object> applicationProperties) {
        assertThat(applicationProperties.get(MessageHelper.APP_PROPERTY_REGISTRATION_STATUS))
                .isEqualTo(EventConstants.RegistrationStatus.NEW.name());
        assertThat(applicationProperties.get(MessageHelper.APP_PROPERTY_TENANT_ID))
                .isEqualTo(Constants.DEFAULT_TENANT);
        assertThat(applicationProperties.get(MessageHelper.APP_PROPERTY_DEVICE_ID))
                .isEqualTo(deviceId);
        assertThat(applicationProperties.get(MessageHelper.APP_PROPERTY_GATEWAY_ID))
                .isEqualTo(gatewayId);
    }

    private void mockAddEdgeDevice(final int httpOk) {
        when(deviceManagementService.createDevice(any(), any(), any(), any()))
                .thenAnswer((Answer<Future<OperationResult<Id>>>) invocation -> {
                    final Optional<String> deviceId = invocation.getArgument(1);
                    if (!deviceId.isPresent()) {
                        return Future.failedFuture("missing device id");
                    }
                    return Future.succeededFuture(OperationResult.ok(httpOk, Id.of(deviceId.get()),
                            Optional.empty(), Optional.empty()));
                });
    }
}
