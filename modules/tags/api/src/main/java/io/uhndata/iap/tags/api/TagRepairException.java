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
package io.uhndata.iap.tags.api;

/**
 * A tag repair could not run at all, so it cannot say whether anything needed repairing. A repair that ran and could
 * not mark some nodes is not this: its {@link TagRepairService.RepairReport} counts them.
 *
 * <p>
 * The message names the instance's infrastructure, such as the service user, so it is for the log and never for an
 * answer to a request.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public class TagRepairException extends Exception
{
    private static final long serialVersionUID = 6915021734258830193L;

    /**
     * Basic constructor.
     *
     * @param message why the repair could not run
     * @param cause the failure that stopped it
     */
    public TagRepairException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
