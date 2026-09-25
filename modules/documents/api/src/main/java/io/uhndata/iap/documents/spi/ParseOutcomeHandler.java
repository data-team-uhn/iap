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
package io.uhndata.iap.documents.spi;

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.documents.api.ParseOutcome;

/**
 * Told how a parse ended, for the node it was queued for. Register one to be handed the outcome of every parse
 * that named a {@code target}; a parse queued without one is only ever polled for.
 *
 * <p>A handler that takes the outcome - stored what the parse produced, or recorded why it failed - says so, and
 * the job record is deleted: what it held now lives where it belongs. A handler that could not take it leaves the
 * record for somebody to look at.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface ParseOutcomeHandler
{
    /**
     * Take the outcome of a parse.
     *
     * @param outcome how the parse ended, and for which node
     * @return {@code true} when the outcome has been taken care of and its job record is no longer needed
     */
    boolean handle(@NotNull ParseOutcome outcome);
}
