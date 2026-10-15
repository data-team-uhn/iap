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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.ValidityCheck;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link CheckValidHandler}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class CheckValidHandlerTest
{
    private final SlingContext context = new SlingContext();

    private final CheckValidHandler handler = new CheckValidHandler();

    private final Activity activity = Mockito.mock(Activity.class);

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
        AuthoringFixture.createVersion(this.context, "1-0", "1.0", "draft", Map.of());
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(CheckValidHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void letsATargetWithNoProblemsThrough() throws Exception
    {
        checks(reporting(true));

        this.handler.execute(this.request(AuthoringFixture.path("1-0")));
    }

    @Test
    void reportsEveryProblemAtOnce() throws Exception
    {
        checks(reporting(true, "it has no start"), reporting(true, "it waits", "it loops"));

        final InvalidStateException refusal = assertThrows(InvalidStateException.class,
            () -> this.handler.execute(this.request(AuthoringFixture.path("1-0"))));

        assertEquals("Version 1.0 is not ready yet: it has no start; it waits; it loops.", refusal.getMessage());
    }

    @Test
    void skipsTheChecksThatDoNotApply() throws Exception
    {
        checks(reporting(false, "not mine to judge"));

        this.handler.execute(this.request(AuthoringFixture.path("1-0")));
    }

    @Test
    void namesATargetWithoutAVersionByItsName() throws Exception
    {
        checks(reporting(true, "it is broken"));

        final InvalidStateException refusal = assertThrows(InvalidStateException.class,
            () -> this.handler.execute(this.request(AuthoringFixture.DEFINITION)));

        assertEquals("timeOff is not ready yet: it is broken.", refusal.getMessage());
    }

    private void checks(final ValidityCheck... checks) throws ReflectiveOperationException
    {
        final var reference = CheckValidHandler.class.getDeclaredField("checks");
        reference.setAccessible(true);
        reference.set(this.handler, List.of(checks));
    }

    /**
     * A check reporting fixed problems.
     *
     * @param applies whether it applies to anything
     * @param problems what it reports
     * @return the check
     */
    private static ValidityCheck reporting(final boolean applies, final String... problems)
    {
        return new ValidityCheck()
        {
            @Override
            public boolean appliesTo(@NotNull final Resource target)
            {
                return applies;
            }

            @Override
            public @NotNull List<String> check(@NotNull final Resource target)
            {
                return List.of(problems);
            }
        };
    }

    private WorkflowTaskContext request(final String path)
    {
        return AuthoringFixture.context(this.context.resourceResolver().getResource(path), "activate", Map.of(),
            this.activity, new HashMap<>());
    }
}
