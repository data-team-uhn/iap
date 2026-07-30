/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.errortracking.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;

/**
 * Default implementation of {@link ErrorLoggerService}, storing one {@code err:LoggedError} node per distinct error
 * under {@value ErrorLoggerService#LOGGED_ERRORS_PATH}.
 *
 * <p>
 * A node is named after the digest of the stack trace it holds, so the same failure thrown again from the same place
 * lands on the node already describing it and is counted there. A loop failing thousands of times therefore leaves
 * one node behind with a large {@code occurrences} count, rather than thousands of copies of itself. Two errors share
 * a node only when their stack traces are identical, message included, so an error that names what it was working on
 * is still recorded separately for every distinct thing it failed on.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true, service = ErrorLoggerService.class)
public class ErrorLoggerImpl implements ErrorLoggerService
{
    /** The name of the property counting how many times one error was recorded. */
    static final String OCCURRENCES = "occurrences";

    /** The name of the property holding when an error was last recorded. */
    static final String LAST_OCCURRENCE = "lastOccurrence";

    /** The name of the property holding the stack trace of a recorded error. */
    static final String STACK_TRACE = "stackTrace";

    private static final Logger LOGGER = LoggerFactory.getLogger(ErrorLoggerImpl.class);

    /** The subservice name mapped to the service user allowed to record errors. */
    private static final Map<String, Object> SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, "errortracking");

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Activate
    protected void activate()
    {
        ErrorLogger.setService(this);
    }

    @Deactivate
    protected void deactivate()
    {
        // Without this the facade would keep calling a stopped component, whose service references are gone
        ErrorLogger.unsetService(this);
    }

    @Override
    public void logError(final Throwable error)
    {
        if (error == null) {
            return;
        }
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(SERVICE_USER)) {
            final Resource home = resolver.getResource(LOGGED_ERRORS_PATH);
            if (home == null) {
                LOGGER.warn("Cannot record an error, {} does not exist", LOGGED_ERRORS_PATH);
                return;
            }
            final String stackTrace = print(error);
            final String name = digest(stackTrace);
            final Resource known = home.getChild(name);
            if (known == null) {
                resolver.create(home, name, describe(error, stackTrace));
            } else {
                countAnotherOccurrence(known);
            }
            resolver.commit();
        } catch (final Exception e) {
            // The caller is already handling a failure; recording it must not raise a second one. This also covers
            // two threads recording the same error at once: the one that loses the race logs, and the error itself
            // is recorded by the winner either way
            LOGGER.error("Could not record the error {}: {}", error.getClass().getName(), e.getMessage(), e);
        }
    }

    /**
     * Notes that an already recorded error happened again.
     *
     * @param known the node recording the error
     * @throws PersistenceException if the node cannot be modified by the recording session
     */
    private void countAnotherOccurrence(final Resource known) throws PersistenceException
    {
        final ModifiableValueMap values = known.adaptTo(ModifiableValueMap.class);
        if (values == null) {
            throw new PersistenceException("The recorded error at " + known.getPath() + " cannot be updated");
        }
        values.put(OCCURRENCES, values.get(OCCURRENCES, 1L) + 1);
        values.put(LAST_OCCURRENCE, Calendar.getInstance());
    }

    /**
     * Turns a throwable into the properties of the node recording it.
     *
     * @param error the throwable to record
     * @param stackTrace the throwable's printed stack trace
     * @return the properties of a new {@code err:LoggedError} node
     */
    private Map<String, Object> describe(final Throwable error, final String stackTrace)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", "err:LoggedError");
        properties.put("type", error.getClass().getName());
        properties.put(STACK_TRACE, stackTrace);
        properties.put(OCCURRENCES, 1L);
        properties.put(LAST_OCCURRENCE, Calendar.getInstance());
        if (error.getMessage() != null) {
            properties.put("message", error.getMessage());
        }
        return properties;
    }

    /**
     * The stack trace of a throwable, causes included, as it would be printed to a log file.
     *
     * @param error the throwable to print
     * @return a multi-line string
     */
    private String print(final Throwable error)
    {
        final StringWriter trace = new StringWriter();
        try (PrintWriter writer = new PrintWriter(trace)) {
            error.printStackTrace(writer);
        }
        return trace.toString();
    }

    /**
     * The name of the node recording a stack trace: its digest, which turns "have I already recorded this exact
     * error?" into looking up one child by name, with no query and no index to maintain.
     *
     * @param stackTrace the stack trace to name
     * @return a hexadecimal digest, usable as a JCR node name
     * @throws NoSuchAlgorithmException never, SHA-256 is required of every Java platform
     */
    private String digest(final String stackTrace) throws NoSuchAlgorithmException
    {
        final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(sha256.digest(stackTrace.getBytes(StandardCharsets.UTF_8)));
    }
}
