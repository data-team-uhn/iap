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
package io.uhndata.iap.metrics.api;

import org.jetbrains.annotations.NotNull;

/**
 * A metric could not be read, defined or changed. The metrics service user could not log in, a node is missing or is
 * not a metric, or a change could not be saved.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class MetricsException extends RuntimeException
{
    private static final long serialVersionUID = -1806265778637173073L;

    /**
     * A failure with no exception behind it.
     *
     * @param message what failed, naming the metric if there is one
     */
    public MetricsException(@NotNull final String message)
    {
        super(message);
    }

    /**
     * A failure caused by another exception.
     *
     * @param message what failed, naming the metric if there is one
     * @param cause the exception that caused it
     */
    public MetricsException(@NotNull final String message, @NotNull final Throwable cause)
    {
        super(message, cause);
    }
}
