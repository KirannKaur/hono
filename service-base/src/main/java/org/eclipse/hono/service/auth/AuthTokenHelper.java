/*******************************************************************************
 * Copyright (c) 2016, 2021 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.service.auth;

import java.time.Duration;

import org.eclipse.hono.auth.Authorities;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;

/**
 * A utility for creating and parsing JSON Web Tokens containing user identity and
 * granted authorities.
 *
 */
public interface AuthTokenHelper {

    /**
     * Gets the duration being used for calculating the <em>exp</em> claim of tokens
     * created by this class.
     * <p>
     * Clients should always check if a token is expired before using any information
     * contained in the token.
     *
     * @return The duration.
     */
    Duration getTokenLifetime();

    /**
     * Creates a JSON Web Token for an identity and its granted authorities.
     * <p>
     * The returned JWT 
     * <ul>
     * <li>contains the authorization id in the <em>sub</em> claim.</li>
     * <li>contains the authorities (as returned by {@link Authorities#asMap()}) as claims.</li>
     * <li>expires after the {@linkplain #getTokenLifetime() configured expiration time}.</li>
     * <li>contains a signature of the claims.</li>
     * </ul>
     *
     * @param authorizationId The asserted identity.
     * @param authorities The granted authorities.
     * @return The compact encoding of the JWT.
     */
    String createToken(String authorizationId, Authorities authorities);

    /**
     * Expands an encoded JWT token containing a signed <em>claims</em> body.
     *
     * @param token The compact encoding of the JWT token to expand.
     * @return The expanded token.
     * @throws io.jsonwebtoken.JwtException if the token cannot be expanded, e.g. because its signature cannot
     *                      be verified or it is expired or malformed.
     */
    Jws<Claims> expand(String token);
}
