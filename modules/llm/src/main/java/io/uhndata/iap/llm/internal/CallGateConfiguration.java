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
package io.uhndata.iap.llm.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * How many model calls may be in flight at once.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "IAP LLM - Calls in flight",
    description = "Caps how many requests this instance sends the LLM provider at the same time.")
public @interface CallGateConfiguration
{
    /**
     * The cap. Anything below 1 is read as 1.
     *
     * @return how many calls may run at once
     */
    @AttributeDefinition(name = "Maximum calls in flight",
        description = "How many LLM requests may be in flight at once, across every caller. Measure against the "
            + "provider before raising it: past its limit it queues requests, so every call gets slower and "
            + "nothing fails. See modules/extraction/extraction.md for how to measure.")
    int maxCallsInFlight() default 4;
}
