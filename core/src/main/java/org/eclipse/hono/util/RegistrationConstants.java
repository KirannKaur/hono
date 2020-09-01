/*******************************************************************************
 * Copyright (c) 2016, 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.hono.util;

/**
 * Constants &amp; utility methods used throughout the Registration API.
 */
public final class RegistrationConstants extends RequestResponseApiConstants {

    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>assert device registration</em> operation.
     */
    public static final String ACTION_ASSERT     = "assert";
    /**
     * The AMQP 1.0 <em>subject</em> to use for the <em>get registration information</em> operation.
     */
    public static final String ACTION_GET        = "get";

    /**
     * The name of the field in a response to the <em>get registration information</em> operation
     * that contains a device's registration information.
     */
    public static final String FIELD_DATA         = "data";

    /**
     * The name of the field in a response to the <em>assert Device Registration</em> operation
     * that contains the identifiers of those gateways that may act on behalf of the device.
     */
    public static final String FIELD_VIA = "via";

    /**
     * The name of the mapper used. This mapper should be configured for the adapter and can be referenced using
     * this field.
     */
    public static final String FIELD_MAPPER = "mapper";

    /**
     * The name of the Device Registration API endpoint.
     */
    public static final String REGISTRATION_ENDPOINT = "registration";

    private RegistrationConstants() {
        // prevent instantiation
    }
}
