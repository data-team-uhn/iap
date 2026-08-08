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

/** The administrator a freshly launched instance always has, which is how these suites sign in. */
export const ADMIN = { username: 'admin', password: 'admin' } as const;

/**
 * Request headers that authenticate as the administrator.
 *
 * Deliberately passed per request rather than configured as Playwright `httpCredentials`, because
 * several tests exist precisely to check what an *unauthenticated* caller is served — a project-wide
 * credential would silently make those pass against a repository that never refused anyone.
 */
export const adminAuth = {
  Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString('base64')}`,
} as const;
