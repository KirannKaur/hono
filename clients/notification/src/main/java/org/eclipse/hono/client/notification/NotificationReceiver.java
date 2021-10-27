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

import org.eclipse.hono.util.Lifecycle;

import io.vertx.core.Handler;

/**
 * A client that supports receiving Hono's (internal) notifications.
 *
 */
public interface NotificationReceiver extends Lifecycle {

    /**
     * Registers a notification consumer for a specific type of notifications.
     *
     * @param notificationType The class of the notifications to consume.
     * @param consumer The handler to be invoked with the received notification.
     * @param <T> The type of notifications to consume.
     */
    <T extends Notification> void registerConsumer(Class<T> notificationType, Handler<T> consumer);
}
