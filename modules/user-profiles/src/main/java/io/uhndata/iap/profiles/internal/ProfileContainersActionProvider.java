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
package io.uhndata.iap.profiles.internal;

import java.util.List;

import org.apache.jackrabbit.oak.spi.security.SecurityProvider;
import org.apache.jackrabbit.oak.spi.security.user.action.AuthorizableAction;
import org.apache.jackrabbit.oak.spi.security.user.action.AuthorizableActionProvider;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;

/**
 * Offers {@link ProfileContainersAction} to Oak's user management.
 *
 * <p>
 * Oak collects these providers through a {@code 0..n} dynamic reference and composes them, so this one <em>adds</em> to
 * the platform's configured {@code DefaultAuthorizableActionProvider} rather than replacing it -- the access control
 * action it enables keeps running, and no service ranking is involved.
 * </p>
 *
 * <p>
 * Deliberately absent from the {@code SecurityProviderRegistration.requiredServicePids} list in the Oak feature, where
 * the external principal configuration is named: that list makes the security provider, and therefore the repository,
 * wait for a service, and this bundle starts long after Oak. The window it would close is empty anyway, because the
 * only accounts created before this bundle starts are the service users repoinit declares, and a service account has no
 * profile.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AuthorizableActionProvider.class)
public class ProfileContainersActionProvider implements AuthorizableActionProvider
{
    @Override
    @NotNull
    public List<? extends AuthorizableAction> getAuthorizableActions(@NotNull final SecurityProvider securityProvider)
    {
        return List.of(new ProfileContainersAction());
    }
}
