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
package io.uhndata.iap.startup.internal;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.servlet.context.ServletContextHelper;
import org.osgi.service.servlet.whiteboard.HttpWhiteboardConstants;

/**
 * An empty servlet context at the root path, for the {@link StartupGateFilter} to run in before Sling's own context
 * exists. A whiteboard filter runs only inside a servlet context, and Sling registers its context only once the
 * repository is up. Without this context, a request before then would get the servlet container's raw 404. This
 * context has the lowest ranking, so Sling's context takes over as soon as it appears. The gate disables this
 * component when it retires.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServletContextHelper.class, property = {
    HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_NAME + "=io.uhndata.iap.startup",
    HttpWhiteboardConstants.HTTP_WHITEBOARD_CONTEXT_PATH + "=/",
    "service.ranking:Integer=-2147483648"
})
public final class StartupPlaceholderContext extends ServletContextHelper
{
}
