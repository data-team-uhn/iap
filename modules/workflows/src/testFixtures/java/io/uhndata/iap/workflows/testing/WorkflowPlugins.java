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

import org.apache.sling.testing.mock.osgi.context.ContextPlugin;
import org.apache.sling.testing.mock.sling.context.SlingContextImpl;

/**
 * Puts a workflow engine, real or mocked, in a test's Sling context. A test installs one when it builds the context:
 *
 * {@snippet :
 * private final SlingContext context = new SlingContextBuilder(ResourceResolverType.JCR_OAK)
 *     .plugin(WorkflowPlugins.ENGINE)
 *     .build();
 * }
 *
 * <p>Either way the engine is then {@code context.getService(WorkflowEngine.class)}, and a component the test
 * registers with {@code registerInjectActivateService} gets it as the framework would hand it out.</p>
 *
 * <p>A module's tests use the plugins through the {@code iap-workflows} test jar, in the module's {@code tests}
 * profile.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowPlugins
{
    /**
     * The real engine, with the services it is built from, as the framework wires them.
     *
     * <p>What lies outside the engine is recorded instead of done. Notifications go to a
     * {@link RecordedNotifications}, and each delivery is committed and kept by a {@link RecordedDeliveries}
     * rather than recorded in the history. A test reads either back with {@code context.getService} and its class.
     * No service task handler is registered: a test registers the ones its workflows name. It may register them at
     * any time, before an event or after.</p>
     *
     * <p>The engine opens its sessions through the context's own resolver factory, so it sees what the test has
     * committed and nothing else. It authorizes against the repository's user store, which only a {@code JCR_OAK}
     * context has. The workflow node types need nothing from the test: sling-mock registers the ones every bundle on
     * the classpath declares.</p>
     *
     * <p>The engine's services are registered once, when the context is set up, and each binds what is there at
     * that moment. A test that needs one of them replaced registers its stand-in ranked higher, then registers again
     * whatever was built on it, up to the engine.</p>
     */
    public static final ContextPlugin<SlingContextImpl> ENGINE = new EnginePlugin();

    /**
     * A Mockito mock of the engine, ranked above any other engine in the context. Every call answers with Mockito's
     * defaults until the test stubs it. {@code context.getService(WorkflowEngine.class)} hands out the mock to stub
     * and verify.
     */
    public static final ContextPlugin<SlingContextImpl> MOCK_ENGINE = new MockEnginePlugin();

    private WorkflowPlugins()
    {
    }
}
