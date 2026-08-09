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
 * Reading the platform in another language.
 *
 * The sign-in page is the one that has to work in the reader's language before anything else does: it is
 * the only page an unauthenticated visitor can reach, so a person who cannot read it cannot get far enough
 * to change anything about it.
 */
test.describe('reading the platform in another language', () => {
  test('signs a French reader in in French', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open('fr');

    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();
    await expect(page.getByLabel('Mot de passe')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
  });

  test('translates the page it renders as well as the page it sends', async ({ page }) => {
    // The intro text is shipped configuration rendered into a <meta> tag by the server, while the field
    // labels are fetched by the browser afterwards. Two mechanisms, one language: this is the assertion
    // that stops them drifting into signing somebody in in French under an English heading.
    const login = new LoginPage(page);
    await login.open('fr');

    await expect(page.getByText(/accompagne les études de recherche/)).toBeVisible();
    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();
  });

  test('reads English by default', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open();

    await expect(login.username).toBeVisible();
    await expect(login.signIn).toBeVisible();
  });

  test('offers each language under its own name', async ({ page }) => {
    // "Français", never "French": a reader who cannot read the page cannot read the name of the language
    // they want either, so naming them in the current language would help exactly nobody.
    const login = new LoginPage(page);
    await login.open();

    await expect(login.language('English')).toBeVisible();
    await expect(login.language('français')).toBeVisible();
  });

  test('changes language when the reader asks it to', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open();
    await login.language('français').click();

    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();
    await expect(page.getByRole('button', { name: 'Se connecter' })).toBeVisible();
  });

  test('lets a French reader find their way back', async ({ page }) => {
    // The language being read stays a link rather than becoming plain text, so the way back is in the
    // same place as the way out
    const login = new LoginPage(page);
    await login.open('fr');
    await login.language('English').click();

    await expect(login.username).toBeVisible();
  });
});

/**
 * The pseudo-locale check.
 *
 * en-XA is English with every letter accented and the whole message bracketed and padded. Nothing is stored
 * under that name — it is derived from the English catalog on request, so every key is present and the
 * locale fallback never fires. That is what makes this an assertion rather than a survey: any plain English
 * left on screen is a string that never went through a catalog at all, not one whose translation somebody
 * forgot to write.
 *
 * It covers the strings the platform itself owns. Shipped configuration — the intro text, the sign-in
 * heading — is translated by a different mechanism that has no pseudo-locale yet, so this says nothing
 * about it.
 */
test.describe('the pseudo-locale check', () => {
  test('has no hardcoded strings left in the credentials form', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open('en-XA');

    // Waited for rather than read straight off: nothing renders until the catalog has arrived, and reading
    // the labels without waiting finds an empty page and passes for the wrong reason
    await expect(login.labels().first()).toBeVisible();

    const labels = await login.labels().allTextContents();
    expect(labels.length).toBeGreaterThan(0);
    for (const label of labels) {
      expect(label, `"${label}" never went through a message catalog`).toMatch(/^\[/);
    }
  });

  test('has no hardcoded string on the sign-in button', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open('en-XA');

    const submit = page.locator('form button[type="submit"]');
    await expect(submit).toHaveText(/^\[/);
  });

  test('makes every message longer than it was', async ({ page }) => {
    // The other half of what a pseudo-locale is for: a layout that cannot hold a longer translation should
    // fail here, in a build, rather than in French in front of somebody trying to sign in.
    const login = new LoginPage(page);
    await login.open('en-XA');

    const submit = page.locator('form button[type="submit"]');
    await expect(submit).toHaveText(/·/);
  });
});

/**
 * The catalog endpoint itself.
 */
test.describe('the message catalog', () => {
  test('answers a caller who has not signed in', async ({ request }) => {
    // The sign-in page needs its own words before anybody has signed in, so this has to answer anonymously.
    // Redirects are refused rather than followed: Sling sends an unauthenticated browser to /login, which
    // is a 200 with a page in it, and a test that followed it would pass while proving nothing.
    const response = await request.get('/libs/iap/messages.json', { maxRedirects: 0 });

    expect(response.status()).toBe(200);
    const body = await response.json() as { catalog: string; messages: Record<string, string> };
    expect(body.catalog).toBe('iap.interface');
    expect(Object.keys(body.messages).length).toBeGreaterThan(0);
  });

  test('answers in the language it is asked for', async ({ request }) => {
    const response = await request.get('/libs/iap/messages.json?locale=fr', { maxRedirects: 0 });

    const body = await response.json() as { locale: string; messages: Record<string, string> };
    expect(body.locale).toBe('fr');
    expect(Object.values(body.messages)).toContain('Se connecter');
  });

  test('serves every key in a pseudo-locale', async ({ request }) => {
    // Derived rather than authored, so it cannot be partial — and a key it did miss would fall back to
    // English and read as a hardcoded string that is not there
    const english = await (await request.get('/libs/iap/messages.json?locale=en', { maxRedirects: 0 }))
      .json() as { messages: Record<string, string> };
    const pseudo = await (await request.get('/libs/iap/messages.json?locale=en-XA', { maxRedirects: 0 }))
      .json() as { messages: Record<string, string> };

    expect(Object.keys(pseudo.messages).sort()).toEqual(Object.keys(english.messages).sort());
    for (const [ key, message ] of Object.entries(pseudo.messages)) {
      expect(message, `${key} came back untransformed`).not.toBe(english.messages[key]);
    }
  });
});
