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

import { expect, type APIRequestContext } from '@playwright/test';

import { basicAuth, type Credentials } from './auth';

/** The person who asks for time off in the demo. Its feature gives each demo account its own name as password. */
export const REQUESTER: Credentials = { username: 'demo-requester', password: 'demo-requester' };

/** The person who decides it, a member of the group the schema routes approval to. */
export const APPROVER: Credentials = { username: 'demo-approver', password: 'demo-approver' };

/** The path of the schema version a time off request is raised against. */
export const TIME_OFF_SCHEMA_VERSION = '/Schemas/timeOffRequest/v1';

// Where a submission is filed: named by a UUID, in a prefix tree whose three buckets are the first three pairs of
// characters of that name. The back-reference checks that the buckets come from the name.
const FILED_PATH = '/Submissions/([0-9a-f]{2})/([0-9a-f]{2})/([0-9a-f]{2})/\\1\\2\\3[0-9a-f-]+';

/** Matches the path a submission is filed under, as a redirect's `Location` header reports it. */
export const FILED = new RegExp(`^${FILED_PATH}$`);

/**
 * The day `offset` days from today, negative for the past, on the *local* calendar. It is written as the
 * `YYYY-MM-DD` a date answer is stored as. The instance judges dates in its own zone, and in the evening the UTC
 * date from `toISOString()` is already the next day.
 */
export function localDay(offset: number): string {
  const day = new Date();
  day.setDate(day.getDate() + offset);
  const month = String(day.getMonth() + 1).padStart(2, '0');
  return `${day.getFullYear()}-${month}-${String(day.getDate()).padStart(2, '0')}`;
}

/**
 * Raises a time off request, as a person does when they create one.
 *
 * @returns the absolute path the engine filed it under, matching {@link FILED}
 */
export async function raiseRequest(request: APIRequestContext, user: Credentials, title: string): Promise<string> {
  const response = await request.post('/Submissions', {
    headers: basicAuth(user),
    form: { title, schemaVersion: TIME_OFF_SCHEMA_VERSION },
    maxRedirects: 0,
  });
  expect(response.status(), `${user.username} could not raise "${title}"`).toBe(302);
  const path = response.headers().location;
  expect(path).toMatch(FILED);
  return path;
}

/**
 * The address of one of a request's tasks. Completing the task is a POST to it.
 *
 * @param request the path of the submission
 * @param task the id of the user task in the request's workflow definition, such as `fillIn`
 */
export function taskUrl(request: string, task: string): string {
  return `${request}/wf:instances/timeOffRequest/${task}`;
}
