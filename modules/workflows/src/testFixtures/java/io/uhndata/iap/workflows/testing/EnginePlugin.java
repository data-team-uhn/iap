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
package io.uhndata.iap.workflows.testing;

import org.apache.sling.testing.mock.osgi.context.AbstractContextPlugin;
import org.apache.sling.testing.mock.sling.context.SlingContextImpl;

import io.uhndata.iap.conditions.internal.ConditionEvaluatorImpl;
import io.uhndata.iap.conditions.internal.LiteralOperandResolver;
import io.uhndata.iap.conditions.internal.TagsOperandResolver;
import io.uhndata.iap.notifications.api.NotificationService;
import io.uhndata.iap.principals.internal.CreatorResolver;
import io.uhndata.iap.principals.internal.MeResolver;
import io.uhndata.iap.principals.internal.PrincipalServiceImpl;
import io.uhndata.iap.workflows.internal.DeliveryRecorder;
import io.uhndata.iap.workflows.internal.FlowRouting;
import io.uhndata.iap.workflows.internal.HostAccess;
import io.uhndata.iap.workflows.internal.InstanceRunners;
import io.uhndata.iap.workflows.internal.Performers;
import io.uhndata.iap.workflows.internal.ServiceTaskDispatchers;
import io.uhndata.iap.workflows.internal.TaskCompletion;
import io.uhndata.iap.workflows.internal.VariableOperandResolver;
import io.uhndata.iap.workflows.internal.WorkflowEngineImpl;
import io.uhndata.iap.workflows.internal.WorkflowQueries;

/**
 * Installs {@link WorkflowPlugins#ENGINE}. Each service is registered after the ones it references: a static
 * reference binds what is registered when its component activates.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EnginePlugin extends AbstractContextPlugin<SlingContextImpl>
{
    @Override
    public void afterSetUp(final SlingContextImpl context)
    {
        WorkflowResourceTypes.installIn(context);
        recordTheOutside(context);
        registerConditions(context);
        registerPrincipals(context);
        registerEngine(context);
    }

    private static void recordTheOutside(final SlingContextImpl context)
    {
        final RecordedNotifications notifications = new RecordedNotifications();
        context.registerService(NotificationService.class, notifications);
        context.registerService(RecordedNotifications.class, notifications);
        final RecordedDeliveries deliveries = new RecordedDeliveries();
        context.registerService(DeliveryRecorder.class, deliveries);
        context.registerService(RecordedDeliveries.class, deliveries);
    }

    private static void registerConditions(final SlingContextImpl context)
    {
        context.registerInjectActivateService(LiteralOperandResolver.class);
        context.registerInjectActivateService(TagsOperandResolver.class);
        context.registerInjectActivateService(VariableOperandResolver.class);
        context.registerInjectActivateService(ConditionEvaluatorImpl.class);
    }

    private static void registerPrincipals(final SlingContextImpl context)
    {
        context.registerInjectActivateService(CreatorResolver.class);
        context.registerInjectActivateService(MeResolver.class);
        context.registerInjectActivateService(PrincipalServiceImpl.class);
    }

    private static void registerEngine(final SlingContextImpl context)
    {
        context.registerInjectActivateService(FlowRouting.class);
        context.registerInjectActivateService(Performers.class);
        context.registerInjectActivateService(HostAccess.class);
        context.registerInjectActivateService(InstanceRunners.class);
        context.registerInjectActivateService(TaskCompletion.class);
        context.registerInjectActivateService(WorkflowQueries.class);
        context.registerInjectActivateService(ServiceTaskDispatchers.class);
        context.registerInjectActivateService(WorkflowEngineImpl.class);
    }
}
