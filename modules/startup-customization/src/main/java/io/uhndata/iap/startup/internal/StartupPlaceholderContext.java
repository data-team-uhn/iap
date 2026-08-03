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
 * A placeholder whiteboard servlet context at the root path. A whiteboard filter only runs inside a whiteboard
 * servlet context, and the main Sling one only appears once the repository is up, seconds into the startup — until
 * then requests would bypass the {@link StartupGateFilter} and get the raw container 404. This empty context exists
 * so the filter has somewhere to run from the very start; its rock-bottom ranking lets every real context take over
 * as it appears.
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
