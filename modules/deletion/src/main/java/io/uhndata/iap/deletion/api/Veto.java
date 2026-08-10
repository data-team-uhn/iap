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
 * One reason a deletion was blocked: which guard objected, to which resource, and why.
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
     * @param vetoerName the name of the guard that objected
     * @param path the path of the resource the guard objected to
     * @param reason the human-readable explanation
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
     * @return a {@link io.uhndata.iap.deletion.spi.DeletionVeto#getName() veto name}
     */
    @NotNull
    public String getVetoerName()
    {
        return this.vetoerName;
    }

    /**
     * The path of the resource the guard objected to, which is not necessarily the resource whose deletion was
     * requested: it may be a descendant, or another resource the deletion would have impacted.
     *
     * @return an absolute path
     */
    @NotNull
    public String getPath()
    {
        return this.path;
    }

    /**
     * The human-readable explanation.
     *
     * @return the reason returned by the guard
     */
    @NotNull
    public String getReason()
    {
        return this.reason;
    }
}
