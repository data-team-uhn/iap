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

/** The path of the `wf:WorkflowVersion` a study runs. */
export const DATA_STUDY_WORKFLOW_VERSION = '/Workflows/dataStudy/v1';

/** The path of the `datareq:Catalogue` a study chooses its data from. */
export const REGISTRY = '/Catalogues/demoRegistry';

/** The path of the catalogue version tagged `active`, which the study's data requirement names. */
export const CURRENT_REGISTRY_VERSION = `${REGISTRY}/v2`;

/** The readable label of {@link CURRENT_REGISTRY_VERSION}. */
export const CURRENT_REGISTRY_LABEL = '2026-08';

/** The node name of the schema version's `datareq:DataRequirement`, which a save names to say what it answers. */
export const DATA_REQUIREMENT_NAME = 'data';

/** The resource type of a data requirement, as the form reports it. */
export const DATA_REQUIREMENT_TYPE = 'datareq/DataRequirement';

/** The key, `database/collection/field`, of a field only catalogue version `v1` offers. */
export const ONLY_IN_V1 = 'people/Person/legacyId';

/** The key of a field only catalogue version `v2` offers. */
export const ONLY_IN_V2 = 'visits/Visit/dischargeDate';

/** The key of a field both catalogue versions offer. */
export const IN_BOTH = 'people/Person/birthYear';

/** The key of the visit date, which both versions offer under the label "Date of visit". */
export const VISIT_DATE = 'visits/Visit/visitDate';

// Where a submission is filed: named by a UUID, in a prefix tree whose three buckets are the first three pairs of
// characters of that name. The back-reference checks that the buckets come from the name.
const FILED = /^\/Submissions\/([0-9a-f]{2})\/([0-9a-f]{2})\/([0-9a-f]{2})\/\1\2\3[0-9a-f-]+$/;

/**
 * Starts a study as the researcher, against the demo's schema version.
 *
 * @param title the study's `title`, as the researcher would type it
 * @returns the path the engine filed the new `sub:Submission` under, matching {@link FILED}
 */
export async function startStudy(request: APIRequestContext, title: string): Promise<string> {
  const response = await request.post('/Submissions', {
    headers: basicAuth(RESEARCHER),
    form: { title, schemaVersion: DATA_STUDY_SCHEMA_VERSION },
    maxRedirects: 0,
  });
  expect(response.status(), `the researcher could not start "${title}"`).toBe(201);
  const { path } = await response.json() as { path: string };
  expect(path).toMatch(FILED);
  return path;
}

/** Options for a test checking that a save is refused: who saves, and which requirement the save answers. */
export interface ChoiceOptions {
  /** Who saves; the researcher by default. */
  as?: Credentials;
  /** The node name of the requirement answered; {@link DATA_REQUIREMENT_NAME} by default. */
  requirement?: string;
}

/**
 * Records a selection against the study's data requirement, as the editor does.
 *
 * The event repeats `fields` once per chosen field, and Playwright's `form` takes one value per name, so the body
 * is built by hand. The event name is a selector and needs `.json` after it, or Sling reads it as the extension and
 * the POST becomes an ordinary save.
 *
 * @param study the path of the `sub:Submission`, as {@link startStudy} returns it
 * @param fields the field keys, `database/collection/field`, that make up the whole selection; empty clears it
 * @returns the engine's response: a success status when saved, or a refusal carrying an `error` message
 */
export function chooseData(request: APIRequestContext, study: string, fields: string[],
  { as: user = RESEARCHER, requirement = DATA_REQUIREMENT_NAME }: ChoiceOptions = {}) {
  const body = new URLSearchParams();
  body.append('requirement', requirement);
  fields.forEach(field => body.append('fields', field));
  return request.post(`${study}.saveDataSelection.json`, {
    headers: { ...basicAuth(user), 'Content-Type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  });
}
