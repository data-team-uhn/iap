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

import { LoginPage } from '../../pages/login.page';
import { uniqueTitle } from '../../support/titles';

const asAdmin = { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` };

const asResearcher = {
  Authorization: `Basic ${Buffer.from('demo-researcher:demo-researcher').toString('base64')}`,
};

const asSteward = {
  Authorization: `Basic ${Buffer.from('demo-steward:demo-steward').toString('base64')}`,
};

const FILED = new RegExp('^/Submissions/([0-9a-f]{2})/([0-9a-f]{2})/([0-9a-f]{2})/\\1\\2\\3[0-9a-f-]+$');

/** A field only the older version offers, and one only the newer one does. */
const ONLY_IN_V1 = 'people/Person/legacyId';

const ONLY_IN_V2 = 'visits/Visit/dischargeDate';

const IN_BOTH = 'people/Person/birthYear';

/** Starts a study as the researcher, and hands back where it was filed. */
async function startStudy(request: APIRequestContext, title: string): Promise<string> {
  const response = await request.post('/Submissions', {
    headers: asResearcher,
    form: { title, schemaVersion: '/Schemas/dataStudy/v1' },
    maxRedirects: 0,
  });
  expect(response.status()).toBe(302);
  const location = response.headers().location;
  expect(location).toMatch(FILED);
  return location;
}

/**
 * Records a selection against the study's data requirement.
 *
 * The body is built by hand rather than through Playwright's `form`, which takes one value per name:
 * a selection is many fields under one name, repeated, which is how the handler reads it back as a
 * list — the same shape a multi-valued answer is sent in.
 */
function chooseData(request: APIRequestContext, study: string, fields: string[],
  headers: Record<string, string> = asResearcher) {
  const body = new URLSearchParams();
  body.append('requirement', 'data');
  fields.forEach(field => body.append('fields', field));
  // The event is named by a selector, so `.json` follows it: Sling reads the last dot-separated token
  // as the extension, and without it the event name would arrive as a format
  return request.post(`${study}.saveDataSelection.json`, {
    headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' },
    data: body.toString(),
  });
}

/**
 * The retrospective data study demo.
 *
 * What this suite is for, beyond the process running end to end, is the one thing no other content
 * shows: a selection is answered against a *version* of a catalogue, and what a submitter may choose
 * is judged against the version their study is bound to rather than against whatever is published now.
 */
test.describe('the data study demo', () => {
  test('installs the registry, published twice', async ({ request }) => {
    const response = await request.get('/Catalogues/demoRegistry.2.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const registry = (await response.json()) as {
      'jcr:primaryType'?: string;
      title?: string;
      v1?: { version?: string; active?: boolean };
      v2?: { version?: string; active?: boolean };
    };
    expect(registry['jcr:primaryType']).toBe('datareq:Catalogue');
    expect(registry.title).toBe('Demo clinical registry');
    // Exactly one is active, and it is the newer: that is what a study started today is answered against
    expect(registry.v1?.active).toBe(false);
    expect(registry.v2?.active).toBe(true);
    expect(registry.v2?.version).toBe('2026-08');
  });

  test('serves a whole catalogue version, three levels down', async ({ request }) => {
    // What a browser fetches to draw the tree: databases, their collections, and the fields beneath
    const response = await request.get('/Catalogues/demoRegistry/v2.3.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    // Named rather than indexed: an index signature hands back a value that is never absent as far as
    // the types are concerned, and the checks below would read as pointless
    const version = (await response.json()) as {
      people?: {
        identifier?: string;
        Person?: { birthYear?: { identifier?: string; phi?: boolean } };
      };
    };
    expect(version.people?.identifier).toBe('people');
    expect(version.people?.Person?.birthYear?.identifier).toBe('birthYear');
    expect(version.people?.Person?.birthYear?.phi).toBe(false);
  });

  test('says nothing about identifiability where nobody assessed a field', async ({ request }) => {
    const response = await request.get('/Catalogues/demoRegistry/v2/people/Person/sex.json',
      { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    // Explicitly false here rather than absent, which is the distinction the model keeps
    expect(((await response.json()) as { phi?: boolean }).phi).toBe(false);
  });

  test('the two versions differ in both directions', async ({ request }) => {
    const retired = await request.get('/Catalogues/demoRegistry/v2/people/Person/legacyId.json',
      { headers: asAdmin });
    const added = await request.get('/Catalogues/demoRegistry/v1/visits/Visit/dischargeDate.json',
      { headers: asAdmin });

    expect(retired.status()).toBe(404);
    expect(added.status()).toBe(404);
  });

  test('installs a schema whose data requirement names the registry', async ({ request }) => {
    const response = await request.get('/Schemas/dataStudy/v1/data.json', { headers: asAdmin });

    expect(response.ok()).toBeTruthy();
    const requirement = (await response.json()) as {
      'jcr:primaryType'?: string;
      label?: string;
      catalogue?: { '@path'?: string };
    };
    expect(requirement['jcr:primaryType']).toBe('datareq:DataRequirement');
    expect(requirement.label).toBe('Which data do you need?');
    // The catalogue, not one of its versions: which version is used is decided when somebody chooses
    expect(requirement.catalogue?.['@path']).toBe('/Catalogues/demoRegistry');
  });

  test('installs the workflow, and ships its diagram as a file', async ({ request }) => {
    const version = await request.get('/Workflows/dataStudy/v1.json', { headers: asAdmin });
    expect(version.ok()).toBeTruthy();
    expect(((await version.json()) as { state?: string }).state).toBe('ACTIVE');

    const diagram = await request.get('/Workflows/dataStudy/v1/bpmn.xml', { headers: asAdmin });
    expect(diagram.ok()).toBeTruthy();
    expect(diagram.headers()['content-type']).toContain('application/xml');
  });

  test.describe('choosing the data a study needs', () => {
    test('records the selection, bound to the version it was chosen from', async ({ request }) => {
      const study = await startStudy(request, 'Readmissions after day surgery');

      const saved = await chooseData(request, study, [ IN_BOTH, ONLY_IN_V2 ]);

      expect(saved.ok()).toBeTruthy();
      const response = await request.get(`${study}.2.json`, { headers: asAdmin });
      const filed = (await response.json()) as Record<string, {
        'jcr:primaryType'?: string;
        fields?: string[];
        catalogueVersion?: { '@path'?: string };
      }>;
      const selection = Object.values(filed)
        .find(child => child['jcr:primaryType'] === 'datareq:Selection');
      expect(selection).toBeDefined();
      expect(selection?.fields).toEqual([ IN_BOTH, ONLY_IN_V2 ]);
      expect(selection?.catalogueVersion?.['@path']).toBe('/Catalogues/demoRegistry/v2');
    });

    // The whole mechanism, in one response: what may be chosen is judged against the bound version
    test('refuses a field only the older version ever offered', async ({ request }) => {
      const study = await startStudy(request, 'A study of the legacy identifier');

      const refused = await chooseData(request, study, [ ONLY_IN_V1 ]);

      expect(refused.status()).toBe(400);
      expect(((await refused.json()) as { error?: string }).error).toContain(ONLY_IN_V1);
    });

    test('leaves the binding alone when the selection is changed', async ({ request }) => {
      const study = await startStudy(request, 'Changing my mind');
      await chooseData(request, study, [ IN_BOTH ]);

      const again = await chooseData(request, study, [ ONLY_IN_V2 ]);

      expect(again.ok()).toBeTruthy();
      const response = await request.get(`${study}.2.json`, { headers: asAdmin });
      const filed = (await response.json()) as Record<string, {
        'jcr:primaryType'?: string;
        fields?: string[];
        catalogueVersion?: { '@path'?: string };
      }>;
      const selections = Object.values(filed)
        .filter(child => child['jcr:primaryType'] === 'datareq:Selection');
      // One selection, not two: a second save finds the first by what it answers
      expect(selections).toHaveLength(1);
      expect(selections[0].fields).toEqual([ ONLY_IN_V2 ]);
      expect(selections[0].catalogueVersion?.['@path']).toBe('/Catalogues/demoRegistry/v2');
    });

    test('takes an empty selection, which is how one is cleared', async ({ request }) => {
      const study = await startStudy(request, 'Nothing after all');
      await chooseData(request, study, [ IN_BOTH ]);

      const cleared = await chooseData(request, study, []);

      expect(cleared.ok()).toBeTruthy();
    });

    test('refuses a requirement this study does not ask for', async ({ request }) => {
      const study = await startStudy(request, 'Asking the wrong thing');

      const body = new URLSearchParams();
      body.append('requirement', 'nothingLikeIt');
      body.append('fields', IN_BOTH);
      const refused = await request.post(`${study}.saveDataSelection.json`, {
        headers: { ...asResearcher, 'Content-Type': 'application/x-www-form-urlencoded' },
        data: body.toString(),
      });

      expect(refused.status()).toBe(400);
    });

    test('refuses somebody else choosing for the researcher', async ({ request }) => {
      const study = await startStudy(request, 'Not the steward\'s to fill in');

      const refused = await chooseData(request, study, [ IN_BOTH ], asSteward);

      expect(refused.status()).toBe(403);
    });
  });

  test.describe('what the form says about it', () => {
    test('offers the version to browse, and what has been chosen', async ({ request }) => {
      const study = await startStudy(request, 'Reading the form back');
      await chooseData(request, study, [ IN_BOTH ]);

      const response = await request.get(`${study}.form.json`, { headers: asResearcher });

      expect(response.ok()).toBeTruthy();
      const form = (await response.json()) as {
        requirements?: { name?: string; type?: string; fields?: string[];
          catalogueVersion?: string; catalogueVersionLabel?: string }[];
      };
      const data = form.requirements?.find(requirement => requirement.name === 'data');
      expect(data?.type).toBe('datareq/DataRequirement');
      expect(data?.fields).toEqual([ IN_BOTH ]);
      expect(data?.catalogueVersion).toBe('/Catalogues/demoRegistry/v2');
      expect(data?.catalogueVersionLabel).toBe('2026-08');
    });

    // Nothing chosen yet, so the form falls back on what the registry is publishing now
    test('offers the current version before anything is chosen', async ({ request }) => {
      const study = await startStudy(request, 'Not started choosing');

      const response = await request.get(`${study}.form.json`, { headers: asResearcher });

      const form = (await response.json()) as {
        requirements?: { name?: string; fields?: string[]; catalogueVersion?: string }[];
      };
      const data = form.requirements?.find(requirement => requirement.name === 'data');
      expect(data?.fields).toEqual([]);
      expect(data?.catalogueVersion).toBe('/Catalogues/demoRegistry/v2');
    });
  });

  test('stops taking data once the study has been sent', async ({ request }) => {
    const study = await startStudy(request, 'Sent, and then reconsidered');
    await chooseData(request, study, [ IN_BOTH ]);

    const sent = await request.post(`${study}/wf:instances/dataStudy/fillIn`, { headers: asResearcher });
    expect(sent.ok()).toBeTruthy();

    const refused = await chooseData(request, study, [ ONLY_IN_V2 ]);
    expect(refused.status()).toBe(403);
  });
});

/**
 * The same requirement, reached the way a researcher reaches it.
 *
 * Everything above goes over HTTP. That proves the model, the binding and the workflow, and says
 * nothing about whether anybody can get at them — because the component that draws a data
 * requirement is contributed by a module the submission editor has never heard of, through an
 * extension whose asset registers the component as it is evaluated. Every link in that chain — the
 * extension point answering, the asset resolving by name, the registration happening before the form
 * decides what can draw a requirement — is invisible to a unit test, which mocks the registry it is
 * meant to be exercising.
 */
test.describe('choosing data in the editor', () => {
  // Three page loads and a sign-in share one test budget, and CI runs on a quarter of the cores
  test.slow();

  test('draws the catalogue, and records what was ticked', async ({ page, request }) => {
    const study = await startStudy(request, uniqueTitle('Chosen in the browser'));

    const login = new LoginPage(page);
    await login.open();
    await login.signInAs('demo-researcher', 'demo-researcher');

    // Straight to the editor rather than through the dashboard: a hard load is also what exercises
    // the `.edit` script, which resolves differently from a client-side navigation
    await page.goto(`${study}.edit`);

    // The tree opens with every database expanded and every collection shut, so the collection has
    // to be opened before any of its fields is drawn
    await page.getByRole('button', { name: 'Visit collection' }).click();

    const field = page.getByRole('checkbox', { name: 'Date of visit' });
    await expect(field).toBeVisible();
    await field.check();

    await page.getByRole('button', { name: 'Save selection' }).click();

    // Saved rather than merely ticked: the control has nothing left to do only once the form has
    // been read again and agrees with what is on screen
    await expect(page.getByRole('button', { name: 'Save selection' })).toBeDisabled();

    const stored = await request.get(`${study}.form.json`, { headers: asResearcher });
    const form = (await stored.json()) as { requirements?: { name?: string; fields?: string[] }[] };
    expect(form.requirements?.find(requirement => requirement.name === 'data')?.fields)
      .toEqual([ 'visits/Visit/visitDate' ]);
  });
});
