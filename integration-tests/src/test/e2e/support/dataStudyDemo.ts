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

/** The person who describes a study and chooses its data. Each demo account has its own name as password. */
export const RESEARCHER: Credentials = { username: 'demo-researcher', password: 'demo-researcher' };

/** The person who releases the data or refuses it, a member of the group the schema routes approval to. */
export const STEWARD: Credentials = { username: 'demo-steward', password: 'demo-steward' };

/** The path of the `sch:SchemaVersion` a study is started against. */
export const DATA_STUDY_SCHEMA_VERSION = '/Schemas/dataStudy/v1';

/** The path of the `datareq:Catalogue` a study chooses its data from. */
export const REGISTRY = '/Catalogues/demoRegistry';

/** The path of the catalogue version tagged `active`, which a study's first save binds its selection to. */
export const CURRENT_REGISTRY_VERSION = `${REGISTRY}/v2`;

/** The node name of the schema version's `datareq:DataRequirement`, which a save names as what it answers. */
export const DATA_REQUIREMENT = 'data';

/** The key, `database/collection/field`, of a field only catalogue version `v1` offers. */
export const ONLY_IN_V1 = 'people/Person/legacyId';

/** The key of a field only catalogue version `v2` offers. */
export const ONLY_IN_V2 = 'visits/Visit/dischargeDate';

/** The key of a field both catalogue versions offer. */
export const IN_BOTH = 'people/Person/birthYear';

// Where a submission is filed: named by a UUID, in a prefix tree whose three buckets are the first three pairs of
// characters of that name. The back-reference checks that the buckets come from the name.
const FILED = /^\/Submissions\/([0-9a-f]{2})\/([0-9a-f]{2})\/([0-9a-f]{2})\/\1\2\3[0-9a-f-]+$/;

/**
 * Starts a study as the researcher, against the demo's schema version.
 *
 * @param title the study's `title`, as the researcher would type it
 * @returns the path of the new `sub:Submission`, as the engine's redirect names it
 */
export async function startStudy(request: APIRequestContext, title: string): Promise<string> {
  const response = await request.post('/Submissions', {
    headers: basicAuth(RESEARCHER),
    form: { title, schemaVersion: DATA_STUDY_SCHEMA_VERSION },
    maxRedirects: 0,
  });
  expect(response.status(), `the researcher could not start "${title}"`).toBe(302);
  const location = response.headers().location;
  expect(location).toMatch(FILED);
  return location;
}

/**
 * Records a selection against the study's data requirement, as the editor's Save does.
 *
 * The event repeats `fields` once per chosen field, and Playwright's `form` takes one value per name, so the body
 * is built by hand. The event name is a selector and needs `.json` after it, or Sling reads it as the extension and
 * the POST becomes an ordinary save.
 *
 * @param study the path of the `sub:Submission`, as {@link startStudy} returns it
 * @param fields the field keys, `database/collection/field`, that make up the whole selection; empty clears it
 * @param user who saves; the researcher unless a test is checking that somebody else is refused
 * @param requirement the node name of the requirement answered; {@link DATA_REQUIREMENT} unless a test is
 *     checking that an unknown one is refused
 * @returns the engine's response: a success status when saved, or a refusal carrying an `error` message
 */
export function chooseData(request: APIRequestContext, study: string, fields: string[],
  user: Credentials = RESEARCHER, requirement = DATA_REQUIREMENT) {
  const body = new URLSearchParams();
  body.append('requirement', requirement);
  fields.forEach(field => body.append('fields', field));
  return request.post(`${study}.saveDataSelection.json`, {
    headers: { ...basicAuth(user), 'Content-Type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  });
}
