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

  test('remembers the language after the reader has chosen it', async ({ page }) => {
    // The choice has to survive the navigation that is certain to follow — signing in — or somebody switches
    // to French, signs in, and lands back in English
    const login = new LoginPage(page);
    await login.open();
    await login.language('français').click();
    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();

    await login.open();

    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();
    await expect(page.getByText(/accompagne les études de recherche/)).toBeVisible();
  });

  test('translates the footer, both its own words and the links a deployment configures', async ({ page }) => {
    // Two mechanisms again, and this time in one strip of the page. "Conçu par" is a developer-authored
    // string the browser fetches from the interface catalog; the link labels are repository content a
    // deployment rewrites, translated server-side by the path of the property holding each one. A footer
    // half in French would be the visible proof that one of them had been forgotten.
    const login = new LoginPage(page);
    await login.open('fr');

    await expect(page.getByText('Conçu par')).toBeVisible();
    await expect(login.footerLink("Conditions d'utilisation")).toBeVisible();
    await expect(login.footerLink('Signaler un problème')).toBeVisible();
    await expect(login.footerLink("Guide de l'utilisateur")).toBeVisible();
  });

  test('leaves a configured link pointing where it pointed', async ({ page }) => {
    // Only the label is words. Everything else an extension carries — where it goes, which point it hangs
    // off, what order it sits in — is machinery, and a catalog that reached those would break the link
    // rather than translate it.
    const login = new LoginPage(page);
    await login.open('fr');

    await expect(login.footerLink("Conditions d'utilisation")).toHaveAttribute('href', '/terms-of-use');
  });

  test('says which language the page is in, before a script could', async ({ page }) => {
    // The half of this nobody can see, and therefore the half that regresses unnoticed. A screen reader
    // chooses its voice and its pronunciation rules from this attribute while the page is being parsed, so
    // it has to be right in what the server sent — a script correcting it afterwards has already lost the
    // announcement. Asserted against the served HTML rather than the live DOM for that reason.
    const served = await (await page.request.get('/login?locale=fr', { maxRedirects: 0 })).text();

    expect(served).toMatch(/<html[^>]*\blang="fr"/);
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
 * It covers both halves of the page: the strings the platform owns, fetched by the browser, and the shipped
 * configuration the server renders into the page before any script runs. Those travel by different
 * mechanisms and used to be checked by only one of them.
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

  test('has no untranslatable text left in what the server renders', async ({ page }) => {
    // The half the check was blind to for a while. The intro paragraph is shipped configuration written
    // into a <meta> tag by the server, not a message the browser fetches, so it reached the screen without
    // passing the door the disfigurement was applied at — and came out plain English, which is exactly what
    // this check reports as a fault everywhere else.
    const login = new LoginPage(page);
    await login.open('en-XA');

    const intro = await login.configured('introText');
    expect(intro, 'the server rendered no intro text at all').not.toBeNull();
    expect(intro, `"${intro}" never went through a message catalog`).toMatch(/^\[/);
    expect(intro).not.toContain('guides research studies');
  });

  test('leaves configuration that nobody reads as prose exactly as stored', async ({ page }) => {
    // Everything under the configuration tree arrives by the same route, and most of it is not prose: a
    // version number, and the list of languages the switcher parses. Being in the content catalog is what
    // marks a property as text somebody reads. Disfiguring this one would strand a reader in a
    // pseudo-locale with no link back out of it, which is a worse failure than the one being tested for.
    const login = new LoginPage(page);
    await login.open('en-XA');

    expect(await login.configured('languages')).toBe('en fr');
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
 * The mirrored pseudo-locale.
 *
 * <p>en-XB is shorter than English and reads the other way, which together make it a stand-in for a real
 * family of languages rather than two unrelated checks: Hebrew and Arabic are both more compact and both
 * right-to-left, so a layout meets them together or not at all.</p>
 */
test.describe('the mirrored pseudo-locale', () => {
  test('turns the page around', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open('en-XB');

    await expect(login.labels().first()).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('dir', 'rtl');
  });

  test('leaves the page alone for a language that reads the usual way', async ({ page }) => {
    const login = new LoginPage(page);
    await login.open('fr');

    await expect(page.getByLabel("Nom d'utilisateur")).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('dir', 'ltr');
  });

  test('leaves the words themselves the right way round', async ({ page }) => {
    // The whole reason for wrapping the text in a direction override rather than reversing it: only the
    // rendering turns around, so this suite goes on finding text where it says it is, a screen reader goes
    // on reading it, and it can still be copied. Reversal would have cost all three.
    //
    // "Sgn" rather than "Sign" because this locale drops vowels to get shorter. The letters that remain are
    // still in the order they were written in, which is exactly what is being asserted.
    const login = new LoginPage(page);
    await login.open('en-XB');

    await expect(page.locator('form button[type="submit"]')).toHaveText(/Sgn/);
  });

  test('does not cut a markup delimiter in half', async ({ page }) => {
    // Shipped text is Markdown, and this locale used to get shorter by cutting at a fraction of the
    // length — which landed mid-token and left "**faster*" behind. One unbalanced delimiter changes how
    // everything after it is parsed, so the page it was testing is not the page anybody would ship.
    const login = new LoginPage(page);
    await login.open('en-XB');
    await expect(login.labels().first()).toBeVisible();

    const intro = (await login.configured('introText')) ?? '';
    expect((intro.match(/\*\*/g) ?? []).length % 2, `unbalanced emphasis in "${intro}"`).toBe(0);
    // And it still reached the page as emphasis rather than as literal asterisks
    await expect(page.locator('strong').first()).toBeVisible();
  });

  test('turns every line around, not only the first', async ({ page }) => {
    // A direction override reaches the end of its bidi paragraph and no further, and the line break
    // Markdown puts in the middle of this paragraph ends one. With a single override around the whole
    // message the first line turned around and the rest did not — the worst of the three outcomes, since
    // the half that was never checked looks exactly like the half that was.
    const login = new LoginPage(page);
    await login.open('en-XB');
    await expect(login.labels().first()).toBeVisible();

    const intro = (await login.configured('introText')) ?? '';
    const opened = (intro.match(/\u202E/g) ?? []).length;
    const popped = (intro.match(/\u202C/g) ?? []).length;
    expect(opened, `only one override in "${intro}"`).toBeGreaterThan(1);
    expect(popped).toBe(opened);
  });

  test('does not turn the rest of the page around with it', async ({ page }) => {
    // An override that is never popped runs on into whatever follows it
    const login = new LoginPage(page);
    await login.open('en-XB');

    await expect(login.labels().first()).toBeVisible();
    const overrides = await page.evaluate(() => {
      const text = document.body.innerText;
      const opened = (text.match(/\u202E/g) ?? []).length;
      const popped = (text.match(/\u202C/g) ?? []).length;
      return { opened, popped };
    });
    expect(overrides.opened).toBeGreaterThan(0);
    expect(overrides.popped).toBe(overrides.opened);
  });

  test('turns the seam pointer around with the page', async ({ page }) => {
    // A clip path is drawn in physical coordinates, so it is the one thing here that logical properties
    // cannot mirror. Left alone it changes sides and goes on pointing the same way — which, in a mirrored
    // layout, is back into the seam instead of across it toward the sign-in panel.
    const pointerTransform = async () => page.evaluate(() => {
      const pointer = Array.from(document.querySelectorAll('[aria-hidden="true"]'))
        .find(element => getComputedStyle(element).clipPath.startsWith('polygon'));
      return pointer === undefined ? null : getComputedStyle(pointer).transform;
    });

    const login = new LoginPage(page);
    await login.open('en-XB');
    await expect(login.labels().first()).toBeVisible();
    expect(await pointerTransform()).toBe('matrix(-1, 0, 0, 1, 0, 0)');

    // Named rather than left to default: visiting the mirrored locale above was a choice, and the server
    // remembered it, so a bare /login in this same browser is still mirrored — which is the point of the
    // cookie, and would look like a failure here
    await login.open('en');
    await expect(login.labels().first()).toBeVisible();
    expect(await pointerTransform()).toBe('none');
  });

  test('says which way each language runs', async ({ request }) => {
    // Answered by the server, so no list of right-to-left languages has to be kept in the browser and kept
    // correct — and real Arabic and Hebrew are answered by the same code that answers this one
    const mirrored = await (await request.get('/libs/iap/messages.json?locale=en-XB', { maxRedirects: 0 }))
      .json() as { direction: string };
    const plain = await (await request.get('/libs/iap/messages.json?locale=fr', { maxRedirects: 0 }))
      .json() as { direction: string };

    expect(mirrored.direction).toBe('rtl');
    expect(plain.direction).toBe('ltr');
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
