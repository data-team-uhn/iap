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
package io.uhndata.iap.tags.internal;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.tags.api.TagManager;
import io.uhndata.iap.tags.api.TagRepairService;
import io.uhndata.iap.tags.api.TagRepairService.RepairReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the two things that trigger a repair: the scheduled sweep and the on-request servlet.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class TagRepairTriggersTest
{
    private final SlingContext context = new SlingContext();

    /** Records what the repair service was asked to do. */
    private final List<String> requested = new ArrayList<>();

    private RepairReport nextReport = new RepairReport(0, 0);

    private final TagRepairService repairService = new TagRepairService()
    {
        @Override
        public RepairReport repairFailed()
        {
            TagRepairTriggersTest.this.requested.add("failed");
            return TagRepairTriggersTest.this.nextReport;
        }

        @Override
        public RepairReport repair(final String tagName)
        {
            TagRepairTriggersTest.this.requested.add(tagName);
            return TagRepairTriggersTest.this.nextReport;
        }
    };

    private RecordingScheduler scheduler;

    @BeforeEach
    void setUp()
    {
        this.scheduler = new RecordingScheduler();
    }

    // --- the sweep -------------------------------------------------------------------------------------------

    @Test
    void schedulesTheSweepWhenEnabled() throws Exception
    {
        final ScheduledTagRepair sweep = sweep(config("0 0 * * * ? *", true));

        assertEquals(ScheduledTagRepair.JOB_NAME, this.scheduler.name);
        assertEquals("0 0 * * * ? *", this.scheduler.expression);
        // Two sweeps at once would find the same nodes and fight over them
        assertFalse(this.scheduler.concurrent);

        sweep.sweep();
        assertEquals(List.of("failed"), this.requested);
    }

    @Test
    void doesNotScheduleTheSweepWhenDisabled() throws Exception
    {
        sweep(config("0 0 * * * ? *", false));

        assertNull(this.scheduler.name);
    }

    /** An unusable schedule must not stop the module from starting; repair stays available on request. */
    @Test
    void survivesAnUnusableSchedule() throws Exception
    {
        this.scheduler.rejectExpression = true;

        sweep(config("not a schedule", true));

        assertNull(this.scheduler.name);
    }

    @Test
    void unschedulesOnDeactivation() throws Exception
    {
        sweep(config("0 0 * * * ? *", true)).deactivate();

        assertEquals(ScheduledTagRepair.JOB_NAME, this.scheduler.unscheduled);
    }

    @Test
    void sweepReportsWhatItRepaired() throws Exception
    {
        final ScheduledTagRepair sweep = sweep(config("0 0 * * * ? *", true));
        this.nextReport = new RepairReport(3, 1);

        sweep.sweep();

        assertEquals(List.of("failed"), this.requested);
    }

    // --- the servlet -----------------------------------------------------------------------------------------

    @Test
    void repairsTheNamedTag() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = post("retired", true);

        assertEquals(200, response.getStatus());
        assertEquals(List.of("retired"), this.requested);
        assertTrue(response.getOutputAsString().contains("\"status\":\"ok\""));
    }

    @Test
    void reportsAnIncompleteRepair() throws Exception
    {
        this.nextReport = new RepairReport(2, 1);

        final MockSlingJakartaHttpServletResponse response = post("retired", true);

        assertTrue(response.getOutputAsString().contains("\"status\":\"incomplete\""));
        assertTrue(response.getOutputAsString().contains("\"failed\":1"));
    }

    @Test
    void refusesACallerWhoMayNotEditTheDefinitions() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = post("retired", false);

        assertEquals(403, response.getStatus());
        assertTrue(this.requested.isEmpty());
    }

    @Test
    void refusesARequestWithoutATag() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = post(null, true);

        assertEquals(400, response.getStatus());
        assertTrue(this.requested.isEmpty());
    }

    @Test
    void refusesARequestWithABlankTag() throws Exception
    {
        final MockSlingJakartaHttpServletResponse response = post("   ", true);

        assertEquals(400, response.getStatus());
        assertTrue(this.requested.isEmpty());
    }

    @Test
    void refusesWhenTheDefinitionsAreNotVisibleAtAll() throws Exception
    {
        final TagRepairServlet servlet = new TagRepairServlet();
        inject(servlet, "repairService", this.repairService);
        // A resolver that cannot see /Tags at all, as an unauthenticated one would not
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(this.context.resourceResolver(), this.context.bundleContext());
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        servlet.doPost(request, response);

        assertEquals(403, response.getStatus());
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private ScheduledTagRepair sweep(final TagRepairConfiguration config) throws Exception
    {
        final ScheduledTagRepair sweep = new ScheduledTagRepair();
        inject(sweep, "scheduler", this.scheduler);
        inject(sweep, "repairService", this.repairService);
        sweep.activate(config);
        return sweep;
    }

    private MockSlingJakartaHttpServletResponse post(final String tag, final boolean writable) throws Exception
    {
        this.context.create().resource(TagManager.DEFINITIONS_PATH, Map.of());
        final TagRepairServlet servlet = new TagRepairServlet();
        inject(servlet, "repairService", this.repairService);

        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(writable ? this.context.resourceResolver()
                : readOnly(), this.context.bundleContext());
        if (tag != null) {
            request.setParameterMap(Map.of("tag", tag));
        }
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();
        servlet.doPost(request, response);
        return response;
    }

    /** A caller who may read the definitions but not edit them. */
    private org.apache.sling.api.resource.ResourceResolver readOnly()
    {
        return new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource resource = super.getResource(path);
                return resource == null ? null : new ResourceWrapper(resource)
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == ModifiableValueMap.class ? null : super.adaptTo(type);
                    }
                };
            }
        };
    }

    private static void inject(final Object target, final String name, final Object value) throws Exception
    {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static TagRepairConfiguration config(final String schedule, final boolean enabled)
    {
        return new TagRepairConfiguration()
        {
            @Override
            public Class<TagRepairConfiguration> annotationType()
            {
                return TagRepairConfiguration.class;
            }

            @Override
            public String schedule()
            {
                return schedule;
            }

            @Override
            public boolean enabled()
            {
                return enabled;
            }
        };
    }

    /** Records what was scheduled, since there is no scheduler in a unit test. */
    private static final class RecordingScheduler implements Scheduler
    {
        private String name;

        private String expression;

        private boolean concurrent = true;

        private String unscheduled;

        private boolean rejectExpression;

        @Override
        public ScheduleOptions EXPR(final String expression)
        {
            if (this.rejectExpression) {
                throw new IllegalArgumentException("not a schedule");
            }
            this.expression = expression;
            return new RecordingOptions(this);
        }

        @Override
        public boolean schedule(final Object job, final ScheduleOptions options)
        {
            return true;
        }

        @Override
        public boolean unschedule(final String jobName)
        {
            this.unscheduled = jobName;
            return true;
        }

        @Override
        public ScheduleOptions NOW()
        {
            return new RecordingOptions(this);
        }

        @Override
        public ScheduleOptions NOW(final int times, final long period)
        {
            return new RecordingOptions(this);
        }

        @Override
        public ScheduleOptions AT(final Date date)
        {
            return new RecordingOptions(this);
        }

        @Override
        public ScheduleOptions AT(final Date date, final int times, final long period)
        {
            return new RecordingOptions(this);
        }

        @Override
        @Deprecated
        public void removeJob(final String jobName)
        {
            this.unscheduled = jobName;
        }

        // The rest of the interface is a decade of deprecated scheduling calls that nothing here uses
        @Override
        @Deprecated
        public void addJob(final String n, final Object j, final Map<String, Serializable> c, final String e,
            final boolean f)
        {
            // Not used
        }

        @Override
        @Deprecated
        public void addPeriodicJob(final String n, final Object j, final Map<String, Serializable> c, final long p,
            final boolean f)
        {
            // Not used
        }

        @Override
        @Deprecated
        public void addPeriodicJob(final String n, final Object j, final Map<String, Serializable> c, final long p,
            final boolean f, final boolean l)
        {
            // Not used
        }

        @Override
        @Deprecated
        public void fireJob(final Object j, final Map<String, Serializable> c)
        {
            // Not used
        }

        @Override
        @Deprecated
        public boolean fireJob(final Object j, final Map<String, Serializable> c, final int t, final long p)
        {
            return false;
        }

        @Override
        @Deprecated
        public void fireJobAt(final String n, final Object j, final Map<String, Serializable> c, final Date d)
        {
            // Not used
        }

        @Override
        @Deprecated
        public boolean fireJobAt(final String n, final Object j, final Map<String, Serializable> c, final Date d,
            final int t, final long p)
        {
            return false;
        }
    }

    /** Captures the options the job was scheduled with. */
    private static final class RecordingOptions implements ScheduleOptions
    {
        private final RecordingScheduler owner;

        RecordingOptions(final RecordingScheduler owner)
        {
            this.owner = owner;
        }

        @Override
        public ScheduleOptions name(final String jobName)
        {
            this.owner.name = jobName;
            return this;
        }

        @Override
        public ScheduleOptions canRunConcurrently(final boolean flag)
        {
            this.owner.concurrent = flag;
            return this;
        }

        @Override
        public ScheduleOptions config(final Map<String, java.io.Serializable> config)
        {
            return this;
        }

        @Override
        public ScheduleOptions onInstancesOnly(final String[] slingIds)
        {
            return this;
        }

        @Override
        public ScheduleOptions onLeaderOnly(final boolean flag)
        {
            return this;
        }

        @Override
        public ScheduleOptions onSingleInstanceOnly(final boolean flag)
        {
            return this;
        }

        @Override
        public ScheduleOptions threadPoolName(final String name)
        {
            return this;
        }
    }
}
