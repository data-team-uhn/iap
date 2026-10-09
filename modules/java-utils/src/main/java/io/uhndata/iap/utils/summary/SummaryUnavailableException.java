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
package io.uhndata.iap.utils.summary;

/**
 * Thrown by {@link AdminSummaryServlet#summarize} when the summary cannot be read. The message is sent to the
 * client, so it names what failed and never the cause's details.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class SummaryUnavailableException extends Exception
{
    private static final long serialVersionUID = 1L;

    /**
     * A failure with nothing further to log.
     *
     * @param message what could not be read, as the client is told
     */
    public SummaryUnavailableException(final String message)
    {
        super(message);
    }

    /**
     * A failure caused by another exception, which is logged.
     *
     * @param message what could not be read, as the client is told
     * @param cause the underlying failure
     */
    public SummaryUnavailableException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
