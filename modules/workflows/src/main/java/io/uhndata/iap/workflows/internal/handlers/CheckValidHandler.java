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
package io.uhndata.iap.workflows.internal.handlers;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.ValidityCheck;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Refuses to go on unless the target passes every {@link ValidityCheck} that applies to it. Every problem is reported
 * at once.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class CheckValidHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "checkValid";

    /** Every check registered: this module's, and whatever others register. */
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<ValidityCheck> checks;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException
    {
        final Resource target = context.getTarget();
        final List<String> problems = this.checks.stream()
            .filter(check -> check.appliesTo(target))
            .flatMap(check -> check.check(target).stream())
            .toList();
        if (!problems.isEmpty()) {
            throw new InvalidStateException(describe(target) + " is not ready yet: " + String.join("; ", problems)
                + ".");
        }
    }

    /**
     * How a refusal names its target: a version by its label, anything else by its name.
     *
     * @param target the resource checked
     * @return a name for it
     */
    private static String describe(final Resource target)
    {
        final String version = target.getValueMap().get("version", String.class);
        return version == null ? target.getName() : "Version " + version;
    }
}
