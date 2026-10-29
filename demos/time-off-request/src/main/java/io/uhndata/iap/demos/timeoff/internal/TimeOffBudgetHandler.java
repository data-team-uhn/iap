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

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Looks up how much time off the person raising a request has left, and records it on the request.
 *
 * <p>Every deployment counts time off differently, with its own accrual, carry-over and part-time rules. The
 * platform knows none of them. A project plugs its own in as a {@link ServiceTaskHandler}, as this demo does.</p>
 *
 * <p>The answers are canned, from {@link TimeOffBudgetConfiguration}. A real handler would call a human resources
 * system.</p>
 *
 * <p>The result goes onto the request, where the budget rule finds it on every later save.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
@Designate(ocd = TimeOffBudgetConfiguration.class)
public class TimeOffBudgetHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "checkTimeOffBudget";

    /** The property recording how many days the requester had left when the request was raised. */
    public static final String REMAINING_DAYS_PROPERTY = "budgetRemainingDays";

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeOffBudgetHandler.class);

    private final Map<String, Long> budgets;

    private final long defaultRemainingDays;

    /**
     * Reads the canned answers once, at activation.
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
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final long remaining = this.budgets.getOrDefault(context.getActor(), this.defaultRemainingDays);
        final ModifiableValueMap properties = Objects.requireNonNull(
            context.getTarget().adaptTo(ModifiableValueMap.class),
            "The engine hands handlers a target it can write to");
        properties.put(REMAINING_DAYS_PROPERTY, remaining);
    }

    /**
     * Reads the configured answers, ignoring entries that say nothing usable.
     *
     * <p>A bad entry is skipped, so one typo does not keep the handler from activating. It is recorded for an
     * administrator, since the person it names is left with the default budget.</p>
     *
     * @param entries the configured {@code username=days} entries
     * @return the days each person has left, keyed by repository user id, the last entry winning where a user id is
     *     repeated; empty if no entry is well formed
     */
    private static Map<String, Long> parseBudgets(final String[] entries)
    {
        return Arrays.stream(entries)
            .map(TimeOffBudgetHandler::parseBudget)
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> second));
    }

    /**
     * Reads one configured entry, which names a person and a number of days.
     *
     * @param entry a configured {@code username=days} entry
     * @return the user id and the days they have left; {@code null} if the entry has no {@code =}, or its days are
     *     not a whole number
     */
    private static Map.Entry<String, Long> parseBudget(final String entry)
    {
        final String[] parts = entry.split("=", 2);
        if (parts.length != 2) {
            LOGGER.warn("Ignoring time off budget entry [{}]: it is not of the form username=days", entry);
            recordIgnored(entry);
            return null;
        }
        try {
            return Map.entry(parts[0].trim(), Long.valueOf(parts[1].trim()));
        } catch (final NumberFormatException e) {
            LOGGER.warn("Ignoring time off budget for [{}]: [{}] is not a number of days", parts[0], parts[1]);
            recordIgnored(entry);
            return null;
        }
    }

    private static void recordIgnored(final String entry)
    {
        ErrorLogger.logProblem("A time off budget entry is malformed",
            ErrorContext.of(TimeOffBudgetHandler.class, "parseBudgets").with("entry", entry));
    }
}
