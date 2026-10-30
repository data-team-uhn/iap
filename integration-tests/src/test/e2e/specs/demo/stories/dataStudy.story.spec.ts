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

import { expect, test, type APIRequestContext } from '@playwright/test';

import { adminAuth, basicAuth, signInAs } from '../../../support/auth';
import {
  chooseData, CURRENT_REGISTRY_VERSION, DATA_REQUIREMENT, IN_BOTH, ONLY_IN_V1, ONLY_IN_V2, RESEARCHER, startStudy,
  STEWARD,
} from '../../../support/dataStudyDemo';
import { childrenOfType, type SerializedChild } from '../../../support/nodes';
import { uniqueTitle } from '../../../support/titles';

/** A `datareq:Selection` as the repository serves it, with the catalogue version it is bound to embedded. */
interface FiledSelection extends SerializedChild {
  fields?: string[];
  catalogueVersion?: { '@path'?: string };
}

/** One entry of `requirements` in a study's `.form.json`, typed for what a data requirement adds. */
interface DataRequirementOnForm {
  name?: string;
  type?: string;
  fields?: string[];
  catalogueVersion?: string;
  catalogueVersionLabel?: string;
}

/**
 * The selections filed on a study, read as an administrator.
 *
 * @param study the path of the `sub:Submission`, as `startStudy` returns it
 * @returns every `datareq:Selection` child of the study; empty if it has none
 */
async function selectionsOf(request: APIRequestContext, study: string): Promise<FiledSelection[]> {
  const response = await request.get(`${study}.2.json`, { headers: adminAuth });
  expect(response.ok(), `${study} could not be read`).toBeTruthy();
  return childrenOfType(await response.json(), 'datareq:Selection');
}

/**
 * What the study's form tells the researcher about its data requirement.
 *
 * @param study the path of the `sub:Submission`, as `startStudy` returns it
 * @returns the form's entry for the requirement named `data`; `undefined` if the form lists no such requirement
 */
async function dataRequirementOnForm(request: APIRequestContext, study: string)
  : Promise<DataRequirementOnForm | undefined> {
  const response = await request.get(`${study}.form.json`, { headers: basicAuth(RESEARCHER) });
  expect(response.ok(), `the form for ${study} could not be read`).toBeTruthy();
  const form = (await response.json()) as { requirements?: DataRequirementOnForm[] };
  return form.requirements?.find(requirement => requirement.name === DATA_REQUIREMENT);
}

/**
 * A story: choosing the data a study needs, over HTTP.
 *
 * Each test starts a study and saves a selection on it, so these are stories, run after the specs that only look.
 */
test.describe('a story: choosing the data a study needs', () => {
  test.describe('saving a selection', () => {
    test('records the selection, bound to the version it was chosen from', async ({ request }) => {
      const study = await startStudy(request, 'Readmissions after day surgery');

      const saved = await chooseData(request, study, [ IN_BOTH, ONLY_IN_V2 ]);

      expect(saved.ok()).toBeTruthy();
      const selections = await selectionsOf(request, study);
      expect(selections).toHaveLength(1);
      expect(selections[0].fields).toEqual([ IN_BOTH, ONLY_IN_V2 ]);
      expect(selections[0].catalogueVersion?.['@path']).toBe(CURRENT_REGISTRY_VERSION);
    });

    test('refuses a field only the older version ever offered', async ({ request }) => {
      const study = await startStudy(request, 'A study of the legacy identifier');

      const refused = await chooseData(request, study, [ ONLY_IN_V1 ]);

      expect(refused.status()).toBe(400);
      expect(((await refused.json()) as { error?: string }).error).toContain(ONLY_IN_V1);
    });

    test('replaces the whole selection when it is saved again', async ({ request }) => {
      const study = await startStudy(request, 'Changing my mind');
      expect((await chooseData(request, study, [ IN_BOTH ])).ok()).toBeTruthy();

      const again = await chooseData(request, study, [ ONLY_IN_V2 ]);

      expect(again.ok()).toBeTruthy();
      const selections = await selectionsOf(request, study);
      expect(selections).toHaveLength(1);
      expect(selections[0].fields).toEqual([ ONLY_IN_V2 ]);
    });

    test('takes an empty selection, which is how one is cleared', async ({ request }) => {
      const study = await startStudy(request, 'Nothing after all');
      expect((await chooseData(request, study, [ IN_BOTH ])).ok()).toBeTruthy();

      const cleared = await chooseData(request, study, []);

      expect(cleared.ok()).toBeTruthy();
      expect((await dataRequirementOnForm(request, study))?.fields).toEqual([]);
    });

    test('refuses a requirement this study does not ask for', async ({ request }) => {
      const study = await startStudy(request, 'Asking the wrong thing');

      const refused = await chooseData(request, study, [ IN_BOTH ], RESEARCHER, 'nothingLikeIt');

      expect(refused.status()).toBe(400);
    });

    test('refuses somebody else choosing for the researcher', async ({ request }) => {
      const study = await startStudy(request, 'Not the steward\'s to fill in');

      const refused = await chooseData(request, study, [ IN_BOTH ], STEWARD);

      expect(refused.status()).toBe(403);
    });
  });

  test.describe('what the form says about it', () => {
    test('offers the version to browse, and what has been chosen', async ({ request }) => {
      const study = await startStudy(request, 'Reading the form back');
      await chooseData(request, study, [ IN_BOTH ]);

      const data = await dataRequirementOnForm(request, study);

      expect(data?.type).toBe('datareq/DataRequirement');
      expect(data?.fields).toEqual([ IN_BOTH ]);
      expect(data?.catalogueVersion).toBe(CURRENT_REGISTRY_VERSION);
      expect(data?.catalogueVersionLabel).toBe('2026-08');
    });

    test('offers the current version before anything is chosen', async ({ request }) => {
      const study = await startStudy(request, 'Not started choosing');

      const data = await dataRequirementOnForm(request, study);

      expect(data?.fields).toEqual([]);
      expect(data?.catalogueVersion).toBe(CURRENT_REGISTRY_VERSION);
    });
  });

  test('stops taking data once the study has been sent', async ({ request }) => {
    const study = await startStudy(request, 'Sent, and then reconsidered');
    await chooseData(request, study, [ IN_BOTH ]);

    const sent = await request.post(`${study}/wf:instances/dataStudy/fillIn`, { headers: basicAuth(RESEARCHER) });
    expect(sent.ok()).toBeTruthy();

    const refused = await chooseData(request, study, [ ONLY_IN_V2 ]);
    expect(refused.status()).toBe(409);
  });
});

/**
 * A story: choosing data in the editor, as the researcher does.
 *
 * The HTTP story above says nothing about whether the editor can draw a data requirement. The component comes
 * from another module, through an extension whose asset registers it as it loads. Unit tests mock that registry,
 * so only a browser sees the whole chain work.
 */
test.describe('a story: choosing data in the editor', () => {
  // A sign-in, the editor's first load and a save need more than the ordinary per-test budget
  test.slow();

  test('draws the catalogue, and records what was ticked', async ({ page, request }) => {
    const study = await startStudy(request, uniqueTitle('Chosen in the browser'));

    await signInAs(page, RESEARCHER);

    // Straight to the editor's URL: a hard load runs the server's `.edit` script, which a client-side
    // navigation skips
    await page.goto(`${study}.edit`);

    // Every database opens expanded and every collection shut, so no field is drawn before its collection opens
    await page.getByRole('button', { name: 'Visit collection' }).click();

    const field = page.getByRole('checkbox', { name: 'Date of visit' });
    await expect(field).toBeVisible();
    await field.check();

    await page.getByRole('button', { name: 'Save selection' }).click();

    // Disabled again only once the form has been read back and matches what is ticked. While it saves, the
    // button reads "Saving…", which this locator does not match.
    await expect(page.getByRole('button', { name: 'Save selection' })).toBeDisabled();

    expect((await dataRequirementOnForm(request, study))?.fields).toEqual([ 'visits/Visit/visitDate' ]);
  });
});
