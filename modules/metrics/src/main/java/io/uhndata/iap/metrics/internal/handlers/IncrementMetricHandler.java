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
package io.uhndata.iap.metrics.internal.handlers;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.metrics.api.Metric;
import io.uhndata.iap.metrics.api.MetricsManager;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Counts in a metric: the {@value #HANDLER_NAME} service task. The activity names the metric in its {@code metric}
 * property, and may give an {@code amount} to add other than one. The count lands once the delivery has committed,
 * so a delivery that fails counts nothing. A metric that does not exist refuses the definition: a metric is defined,
 * with its label and its audience, before anything counts in it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class IncrementMetricHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "incrementMetric";

    /** The activity property naming the metric. */
    private static final String METRIC_PARAMETER = "metric";

    /** The activity property giving the amount to add. */
    private static final String AMOUNT_PARAMETER = "amount";

    private final MetricsManager metrics;

    /**
     * Constructor.
     *
     * @param metrics where the metrics are kept
     */
    @Activate
    public IncrementMetricHandler(@Reference final MetricsManager metrics)
    {
        this.metrics = metrics;
    }

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowDefinitionException
    {
        final Metric metric = metricOf(context);
        final long amount = amountOf(context);
        context.afterCommit(() -> metric.increment(amount));
    }

    private Metric metricOf(final WorkflowTaskContext context) throws WorkflowDefinitionException
    {
        final Object name = context.getActivity().get(METRIC_PARAMETER);
        if (!(name instanceof String) || ((String) name).isBlank()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not configure which " + METRIC_PARAMETER + " to increment");
        }
        return this.metrics.getMetric((String) name).orElseThrow(() -> new WorkflowDefinitionException("The activity "
            + context.getActivity().getPath() + " names the metric " + name + ", which does not exist"));
    }

    private static long amountOf(final WorkflowTaskContext context) throws WorkflowDefinitionException
    {
        final Object amount = context.getActivity().get(AMOUNT_PARAMETER);
        if (amount == null) {
            return 1;
        }
        if (!(amount instanceof Long)) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " gives an " + AMOUNT_PARAMETER + " that is not a whole number: " + amount);
        }
        return (Long) amount;
    }
}
