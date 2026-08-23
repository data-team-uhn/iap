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

import { expect, test } from '@playwright/test';

import { LoginPage } from '../../pages/login.page';

/**
 * The bare platform: what a deployment looks like before anyone has configured anything. These tests
 * deliberately assume no content, so that anything they rely on is something the modules themselves ship.
 */
test.describe('the bare platform', () => {
  test('serves the sign-in page', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open();

    await expect(login.username).toBeVisible();
    await expect(login.password).toBeVisible();
    await expect(login.signIn).toBeVisible();
    // With no other sign-in method registered, the credentials form is the primary one and there is
    // nothing to collapse it behind — the mirror image of the test-data instance
    await expect(login.localAccountToggle).toHaveCount(0);
  });

  test('cannot be signed into with the wrong password', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open();
    await login.signInAs('admin', 'definitely-not-the-password');

    // Still on the sign-in page, rather than let through
    await expect(login.signIn).toBeVisible();
  });

  test('shows no participating institutions when none are registered', async ({ page }) => {
    // The strip renders nothing at all where no registry exists, which is what a single-institution
    // deployment looks like. This is also what tells this instance apart from the test-data one.
    const login = new LoginPage(page);
    await login.open();
    await expect(login.signIn).toBeVisible();

    await expect(login.participatingInstitutions).toHaveCount(0);
  });

  test('ships no participating institutions registry', async ({ request }) => {
    // The API-level counterpart of the test above. Worth having both: this one keeps working where no
    // browser can run, and it is what actually distinguishes this instance's feature set from the
    // test-data one, so it would catch the two suites being pointed at the same instance.
    const response = await request.get('/libs/iap/ParticipatingInstitutions.1.json');

    expect(response.status()).toBe(404);
  });

  test('serves its configuration through our own JSON serializer', async ({ request }) => {
    // The conf tree is app:Configuration rather than plain JCR nodes so that it goes through the IAP
    // serializer instead of Sling's default renderer. Both would answer with the properties, so the
    // things asserted here are the ones only ours produces: the @path/@name identification, and a
    // depth selector that actually descends. The tree is world-readable, hence no credentials.
    const response = await request.get('/libs/iap/conf.1.json');

    expect(response.ok()).toBeTruthy();
    const body = (await response.json()) as {
      '@path': string;
      ThemeColor: { '@name': string; primaryColor: string };
    };
    expect(body['@path']).toBe('/libs/iap/conf');
    expect(body.ThemeColor['@name']).toBe('ThemeColor');
    expect(body.ThemeColor.primaryColor).toBeTruthy();
  });

  test('delivers that configuration as meta tags, and nothing else', async ({ request }) => {
    // ConfigMetadata flattens the same tree into the page head. Everything app:Configuration
    // autocreates on those nodes is namespaced, and namespaced names are filtered out precisely so
    // that they do not surface here: the configuration vocabulary is unprefixed, machinery is not.
    const html = await (await request.get('/login.html')).text();
    const names = [...html.matchAll(/<meta name="([^"]+)"/g)].map(match => match[1]);

    expect(names).toContain('primaryColor');
    expect(names.filter(name => name.includes(':'))).toEqual([]);
  });

  test('reports nothing broken in its health checks', async ({ request }) => {
    const response = await request.get('/system/health.json?tags=iap', {
      headers: { Authorization: `Basic ${Buffer.from('admin:admin').toString('base64')}` },
    });

    expect(response.ok()).toBeTruthy();
    const body = (await response.json()) as { results: { name: string; status: string }[] };
    // A WARN is informational, so only the outright failures are asserted on. Named rather than counted,
    // so that a failure says which check went wrong.
    const broken = body.results
      .filter(result => ['CRITICAL', 'HEALTH_CHECK_ERROR'].includes(result.status))
      .map(result => `${result.name}: ${result.status}`);
    expect(broken).toEqual([]);
  });
});
