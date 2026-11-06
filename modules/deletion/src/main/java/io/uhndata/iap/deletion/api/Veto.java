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
package io.uhndata.iap.deletion.api;

import org.jetbrains.annotations.NotNull;

/**
 * One guard's objection to a deletion or a purge: which guard objected, to which resource, and why.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Veto
{
    private final String vetoerName;

    private final String path;

    private final String reason;

    /**
     * Record a veto.
     *
     * @param vetoerName the {@link io.uhndata.iap.deletion.spi.DeletionVeto#getName() name} of the guard
     * @param path the absolute path of the node the guard objected to
     * @param reason the guard's explanation, in a sentence for the requesting user
     */
    public Veto(@NotNull final String vetoerName, @NotNull final String path, @NotNull final String reason)
    {
        this.vetoerName = vetoerName;
        this.path = path;
        this.reason = reason;
    }

    /**
     * The name of the guard that objected.
     *
     * @return a {@link io.uhndata.iap.deletion.spi.DeletionVeto#getName() guard name}, such as {@code undeletable}
     */
    @NotNull
    public String getVetoerName()
    {
        return this.vetoerName;
    }

    /**
     * The node the guard objected to. It can be the requested resource, a node inside it, or another resource the
     * deletion takes along.
     *
     * @return an absolute path
     */
    @NotNull
    public String getPath()
    {
        return this.path;
    }

    /**
     * Why the guard objected.
     *
     * @return the guard's explanation, in a sentence for the requesting user
     */
    @NotNull
    public String getReason()
    {
        return this.reason;
    }
}
