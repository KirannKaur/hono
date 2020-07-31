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

package org.eclipse.hono.deviceregistry.jdbc.impl;

import java.net.HttpURLConnection;
import java.util.Set;

import org.eclipse.hono.deviceregistry.service.device.AbstractRegistrationService;
import org.eclipse.hono.deviceregistry.service.device.DeviceKey;
import org.eclipse.hono.service.base.jdbc.store.device.TableAdapterStore;
import org.eclipse.hono.util.RegistrationConstants;
import org.eclipse.hono.util.RegistrationResult;

import io.opentracing.Span;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * An implementation of the <em>registration service</em>.
 */
public class RegistrationServiceImpl extends AbstractRegistrationService {

    private final TableAdapterStore store;

    /**
     * Create a new instance.
     *
     * @param store The backing store to use.
     */
    public RegistrationServiceImpl(final TableAdapterStore store) {
        this.store = store;
    }

    @Override
    public Future<RegistrationResult> processAssertRegistration(final DeviceKey deviceKey, final Span span) {

        return this.store
                .readDevice(deviceKey, span.context())
                .map(r -> r
                        .map(result -> {
                            final var data = JsonObject.mapFrom(result.getDevice());
                            final var payload = new JsonObject()
                                    .put(RegistrationConstants.FIELD_PAYLOAD_DEVICE_ID, deviceKey.getDeviceId())
                                    .put(RegistrationConstants.FIELD_DATA, data);
                            return RegistrationResult.from(HttpURLConnection.HTTP_OK, payload, null);
                        })
                        .orElseGet(() -> RegistrationResult.from(HttpURLConnection.HTTP_NOT_FOUND))
                );

    }

    @Override
    protected Future<Set<String>> processResolveGroupMembers(final String tenantId, final Set<String> viaGroups, final Span span) {

        return this.store
                .resolveGroupMembers(tenantId, viaGroups, span.context());

    }
}
