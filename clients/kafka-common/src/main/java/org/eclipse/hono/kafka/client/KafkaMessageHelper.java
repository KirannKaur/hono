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

package org.eclipse.hono.kafka.client;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.QoS;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.EncodeException;
import io.vertx.core.json.Json;
import io.vertx.kafka.client.producer.KafkaHeader;
import io.vertx.kafka.client.producer.impl.KafkaHeaderImpl;

/**
 * Utility methods for working with Kafka {@code Message}s.
 */
public final class KafkaMessageHelper {

    private KafkaMessageHelper() {
    }

    /**
     * Creates a Kafka header for the given key and value.
     * <p>
     * If the value is not a {@code String}, it will be JSON encoded.
     *
     * @param key The key of the header.
     * @param value The value of the header.
     * @return an encoded Kafka header.
     * @throws NullPointerException if any of the parameters are {@code null}.
     * @throws EncodeException if encoding the value to JSON fails.
     */
    public static KafkaHeader createKafkaHeader(final String key, final Object value) throws EncodeException {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);

        final String encodedValue;
        if (value instanceof String) {
            encodedValue = (String) value;
        } else {
            encodedValue = Json.encode(value);
        }

        return new KafkaHeaderImpl(key, Buffer.buffer(encodedValue));
    }

    /**
     * Gets the {@link MessageHelper#SYS_PROPERTY_CONTENT_TYPE content type} header from the given list of Kafka
     * headers.
     *
     * @param headers The headers to get the content-type from.
     * @return The content type.
     */
    public static Optional<String> getContentType(final List<KafkaHeader> headers) {
        return getHeaderValue(headers, MessageHelper.SYS_PROPERTY_CONTENT_TYPE, String.class);
    }

    /**
     * Gets the {@link MessageHelper#APP_PROPERTY_QOS quality of service} header from the given list of Kafka headers.
     *
     * @param headers The headers to get the QoS from.
     * @return The content type.
     */
    public static Optional<QoS> getQoS(final List<KafkaHeader> headers) {
        return getHeaderValue(headers, MessageHelper.APP_PROPERTY_QOS, Integer.class)
                .map(integer -> Integer.valueOf(0).equals(integer) ? QoS.AT_MOST_ONCE : QoS.AT_LEAST_ONCE);
    }

    /**
     * Gets the value of a Kafka header from the given headers.
     *
     * @param headers The Kafka headers to retrieve the value from.
     * @param key The header key.
     * @param type The expected value type.
     * @param <T> The expected type of the header value.
     * @return The value or an empty Optional if the headers do not contain a correctly encoded value of the expected
     *         type for the given key.
     * @throws NullPointerException if key or type is {@code null}.
     * @see #createKafkaHeader(String, Object)
     */
    public static <T> Optional<T> getHeaderValue(final List<KafkaHeader> headers, final String key,
            final Class<T> type) {

        Objects.requireNonNull(key);
        Objects.requireNonNull(type);

        if (headers == null) {
            return Optional.empty();
        }

        return headers.stream()
                .filter(h -> key.equals(h.key()))
                .findFirst()
                .map(h -> decode(h, type));
    }

    /**
     * Returns the decoded value of the given Kafka header.
     *
     * @param header The header with the value to be decoded.
     * @param type The expected value type.
     * @param <T> The expected type of the header value.
     * @return The decoded value or {@code  null} if the header does not contain a correctly encoded value of the
     *         expected type for the given name.
     * @throws NullPointerException if type is {@code null}.
     * @see #createKafkaHeader(String, Object)
     */
    public static <T> T decode(final KafkaHeader header, final Class<T> type) {
        Objects.requireNonNull(type);

        if (header == null) {
            return null;
        }

        return decode(header.value(), type);
    }

    /**
     * Returns the decoded value of the given buffer.
     *
     * @param encodedHeaderValue The buffer with the value to be decoded.
     * @param type The expected value type.
     * @param <T> The expected type of the header value.
     * @return The decoded value or {@code  null} if the buffer does not contain a correctly encoded value of the
     *         expected type for the given name.
     * @throws NullPointerException if type is {@code null}.
     * @see #createKafkaHeader(String, Object)
     */
    @SuppressWarnings("unchecked")
    public static <T> T decode(final Buffer encodedHeaderValue, final Class<T> type) {
        Objects.requireNonNull(type);

        if (encodedHeaderValue == null) {
            return null;
        }

        try {
            if (String.class.equals(type)) {
                return (T) encodedHeaderValue.toString();
            } else {
                return Json.decodeValue(encodedHeaderValue, type);
            }
        } catch (DecodeException ex) {
            return null;
        }
    }

}
