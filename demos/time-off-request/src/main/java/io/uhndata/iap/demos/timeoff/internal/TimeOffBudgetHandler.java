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

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Looks up how much time off the person raising a request has left, and records it on the request.
 *
 * <p>This is the demo's own code, and it is meant to stay that way. Every deployment counts time off
 * differently — accrual rules, carry-over, part-time fractions — so the platform deliberately knows nothing
 * about any of it; what the platform provides is the {@link ServiceTaskHandler} extension point, and this is what
 * plugging into it looks like from a project. Nothing here belongs in the core.</p>
 *
 * <p>The answers are canned rather than fetched: a real one would call a human resources system, which the demo
 * has no business requiring. That substitution is the whole difference between this and the real thing — the
 * workflow, the activity that names it, and the record it leaves behind are all exactly as they would be.</p>
 *
 * <p>The result is written onto the request itself as well as into a workflow variable. The variable is for
 * whatever the process does next; the properties are for the approver, who has to decide with this in front of
 * them, and who may well look days after the check ran.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
@Designate(ocd = TimeOffBudgetConfiguration.class)
public class TimeOffBudgetHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "checkTimeOffBudget";

    /** The property recording how many days the requester had left when the request was raised. */
    public static final String REMAINING_DAYS = "budgetRemainingDays";

    /** The property recording whose budget was looked up, so the number is never read as somebody else's. */
    public static final String CHECKED_FOR = "budgetCheckedFor";

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeOffBudgetHandler.class);

    private Map<String, Long> budgets;

    private long defaultRemainingDays;

    /**
     * Reads the canned answers once, at activation, the way a real handler would open its connection.
     *
     * @param configuration the answers this stand-in gives
     */
    @Activate
    public TimeOffBudgetHandler(final TimeOffBudgetConfiguration configuration)
    {
        this.budgets = parseBudgets(configuration.budgets());
        this.defaultRemainingDays = configuration.defaultRemainingDays();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final String actor = context.getActor();
        final long remaining = this.budgets.getOrDefault(actor, this.defaultRemainingDays);
        // The engine executes as its own service user and re-resolves the target through it, so what it hands a
        // handler is always writable; a null here would mean the engine itself is misconfigured, not that this
        // particular request is unusual.
        final ModifiableValueMap properties = Objects.requireNonNull(
            context.getTarget().adaptTo(ModifiableValueMap.class),
            "The engine hands handlers a target it can write to");
        properties.put(REMAINING_DAYS, remaining);
        properties.put(CHECKED_FOR, actor);
        context.setVariable(REMAINING_DAYS, remaining);
    }

    /**
     * Reads the configured answers, ignoring entries that say nothing usable.
     *
     * <p>A bad entry is skipped rather than fatal: this stands in for a service that would be allowed to be
     * partially unavailable, and a demo that refuses to start because one line of configuration has a typo in it
     * teaches the wrong lesson.</p>
     *
     * @param entries the configured {@code username=days} entries
     * @return the days each named person has left, last entry winning where a name is repeated
     */
    private static Map<String, Long> parseBudgets(final String[] entries)
    {
        return Arrays.stream(entries)
            .map(entry -> entry.split("=", 2))
            .filter(TimeOffBudgetHandler::isUsable)
            .collect(Collectors.toMap(parts -> parts[0].trim(), parts -> Long.parseLong(parts[1].trim()),
                (first, second) -> second));
    }

    /**
     * Whether one configured entry names a person and a number of days.
     *
     * @param parts an entry already split on its first {@code =}
     * @return {@code true} if the entry can be used
     */
    private static boolean isUsable(final String[] parts)
    {
        if (parts.length != 2) {
            LOGGER.warn("Ignoring time off budget entry [{}]: it is not of the form username=days",
                String.join("", parts));
            return false;
        }
        try {
            Long.parseLong(parts[1].trim());
            return true;
        } catch (final NumberFormatException e) {
            LOGGER.warn("Ignoring time off budget for [{}]: [{}] is not a number of days", parts[0], parts[1]);
            return false;
        }
    }
}
