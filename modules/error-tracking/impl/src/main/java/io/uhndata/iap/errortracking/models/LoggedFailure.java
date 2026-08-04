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
package io.uhndata.iap.errortracking.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

/**
 * A recorded error that something was thrown for: the class of the throwable, a sample of the messages it was seen
 * with, and the stack trace of one occurrence.
 *
 * <p>
 * Named a failure rather than an exception because it is a record of one, not one itself — a distinction both the
 * style checks and a reader would otherwise have to guess at.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { LoggedFailure.class, LoggedError.class },
    resourceType = LoggedFailure.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class LoggedFailure extends LoggedError
{
    /** The Sling resource type of a recorded error something was thrown for. */
    public static final String RESOURCE_TYPE = "err/LoggedFailure";

    /** The class of what was thrown. */
    @ValueMapValue
    private String type;

    /** A sample of the messages this fault was seen with. */
    @ValueMapValue
    private String[] messages;

    /** The stack trace of one occurrence. */
    @ValueMapValue
    private String stackTrace;

    /**
     * The class name of what was thrown, e.g. {@code java.lang.IllegalStateException}. Not to be confused with
     * {@link #getType()}, which is this node's own Sling resource type.
     *
     * @return a class name, never {@code null} in a well-formed record
     */
    @NotNull
    public String getThrowableType()
    {
        return this.type == null ? "" : this.type;
    }

    @Override
    @NotNull
    public String getSummary()
    {
        return this.getThrowableType();
    }

    /**
     * A sample of the distinct messages this fault was seen with, most recent first. Several, because the message is
     * not part of what identifies a fault: the same broken code reporting two different paths is one fault seen
     * twice, not two faults.
     *
     * @return the sampled messages, possibly empty, never {@code null}
     */
    @NotNull
    public List<String> getMessages()
    {
        return this.messages == null ? List.of() : List.of(this.messages);
    }

    /**
     * The stack trace of one occurrence, causes included, as it would be printed to a log file. An exemplar rather
     * than the identity: every occurrence shares these frames by construction, but may carry a different message.
     *
     * @return a multi-line string, never {@code null} in a well-formed record
     */
    @NotNull
    public String getStackTrace()
    {
        return this.stackTrace == null ? "" : this.stackTrace;
    }
}
