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
package io.uhndata.iap.demos.timeoff.internal;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * The answers the stand-in time off budget service gives.
 *
 * <p>A real deployment would ask a human resources system instead, and would have no such configuration; this
 * exists so the demo can be run and reasoned about without one.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ObjectClassDefinition(name = "Time off budget (demo stand-in)",
    description = "Canned answers for the demo's time off budget lookup, in place of a real human resources system.")
public @interface TimeOffBudgetConfiguration
{
    /**
     * How many days each named person has left.
     *
     * @return entries of the form {@code username=days}; malformed ones are ignored with a warning
     */
    @AttributeDefinition(name = "Remaining days",
        description = "One 'username=days' entry per person, e.g. 'demo-requester=12'. Anyone not listed gets the "
            + "default below.")
    String[] budgets() default { "demo-requester=12", "demo-approver=20" };

    /**
     * What to answer for somebody the configuration says nothing about.
     *
     * @return a number of days
     */
    @AttributeDefinition(name = "Default remaining days",
        description = "The answer given for anyone the entries above do not name.")
    int defaultRemainingDays() default 0;
}
