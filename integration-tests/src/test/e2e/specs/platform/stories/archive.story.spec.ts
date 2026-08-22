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

import { AppShell } from '../../../pages/appShell.page';
import { ArchiveEntryPage, ArchivePage } from '../../../pages/archive.page';
import { CategoryManagerPage } from '../../../pages/categoryManager.page';
import { ADMIN, basicAuth, signInAs, type Credentials } from '../../../support/auth';
import { ensureUser } from '../../../support/users';

/**
 * THE STORIES: what becomes of something after it is deleted.
 *
 * Miriam administers this deployment. Over five episodes she deletes submission categories and then
 * has to deal with what became of them: one she deleted by mistake and wants back, a branch that has
 * to go back in the order it came out, one that can never go back at all, and three she has to tell
 * apart. The last episode is not hers — a colleague with an account and nothing else goes looking
 * for a deletion she has been given no right to see.
 *
 * Categories are what gets deleted here because they are the only real thing this platform has that
 * can be: a category is a versionable node whose parent constrains what it will hold, so putting one
 * back is a fair test of putting anything back.
 *
 * Every step below is covered by a spec somewhere, and none of that coverage says the path exists —
 * that deleting from the category manager reaches the archive at all, that a listing's link resolves
 * to an entry addressed without the buckets it is stored in, that a preflight and the operation it
 * previews agree against a live repository, or that a restored category is accepted back by the
 * parent that has to hold it.
 */
test.describe('stories: what becomes of something after it is deleted', () => {
  // One archive, one taxonomy, one instance: these run in order, and each leaves the archive empty
  // for the next. Run alongside each other they would be counting each other's deletions.
  //
  // The default 30s budget is sized for a spec that checks one thing; a story is a dozen steps with a
  // page load and a server round trip in most of them, so it needs a budget of its own. Long enough
  // that a story is never the thing that fails, short enough that a step waiting forever still ends.
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  const PILOT = 'Pilot studies';

  const PHASE = 'Phase one';

  const LEGACY = 'Legacy studies';

  const DUPLICATE = 'Duplicate intake';

  const SPECIALTIES = [ 'Cardiology', 'Neurology', 'Oncology' ];

  /** A colleague of Miriam's, with an account and nothing else. */
  const NADIA: Credentials = { username: 'nadia', password: 'nadia' };

  test('Miriam deletes the wrong category, and puts it back', async ({ page }) => {
    const shell = new AppShell(page);
    const manager = new CategoryManagerPage(page);
    const archive = new ArchivePage(page);
    const entry = new ArchiveEntryPage(page);

    await test.step('she signs in, and nothing has ever been deleted here', async () => {
      await signInAs(page, ADMIN);
      expect(await shell.signedInAs()).toBe('admin');

      // Reached the way she reaches it. The console is offered on the application bar to whoever can
      // read it, which is the same grant that decides everything else in these stories.
      await page.getByRole('button', { name: 'Administration' }).click();
      await expect(page).toHaveURL(/\/admin$/);

      expect(await archive.count('Archived in total')).toBe(0);
      expect(await archive.count('Archived in the last 24 hours')).toBe(0);
    });

    await test.step('she creates a category', async () => {
      await manager.openFromConsole();
      await manager.create(PILOT);
    });

    await test.step('she deletes it, and is promised it is kept', async () => {
      await manager.deleteControl(PILOT).click();
      // The promise the rest of this story is here to check
      await expect(page.getByRole('dialog'))
        .toContainText('It is kept in the archive, where an administrator can restore it.');
      await manager.confirmDelete(PILOT);
    });

    await test.step('the console counts the deletion', async () => {
      await page.goto('/admin');
      expect(await archive.count('Archived in the last 24 hours')).toBe(1);
      expect(await archive.count('Archived in total')).toBe(1);
    });

    let deleted = '';

    await test.step('she finds it in the archive, against her own name', async () => {
      await archive.openFromConsole();
      await expect(archive.rows()).toHaveCount(1);

      // She was never told the node name — the server derived it from the label she typed — so the
      // path is read off the listing rather than assumed here
      deleted = await archive.newestEntryPath();
      expect(deleted).toMatch(/^\/Categories\/[^/]+$/);
      await expect(archive.rowFor(deleted)).toContainText('admin');
    });

    await test.step('she looks at what putting it back would do, before she does it', async () => {
      await archive.openEntry(deleted);

      await expect(entry.heading(deleted)).toBeVisible();
      await expect(entry.itemsHeading()).toContainText('Archived items (1)');
      await expect(entry.item(deleted)).toContainText('Would be restored here');
      await expect(entry.item(deleted)).toContainText('Restorable');
      await expect(entry.restoreEverything()).toBeEnabled();
    });

    await test.step('she restores it, and the archive is empty again', async () => {
      await entry.restoreEverything().click();

      // The entry stops existing, so the page that was showing it goes back to the listing
      await expect(page).toHaveURL(/\/admin\/archive$/);
      await expect(archive.empty()).toBeVisible();

      await page.goto('/admin');
      expect(await archive.count('Archived in total')).toBe(0);
    });

    await test.step('and the category is back where it was', async () => {
      await manager.openFromConsole();
      await expect(manager.edit(PILOT)).toBeVisible();
    });

    await test.step('she signs out', async () => {
      await shell.signOut();
    });
  });

  test('Miriam clears out a branch, and has to put it back in order', async ({ page }) => {
    const shell = new AppShell(page);
    const manager = new CategoryManagerPage(page);
    const archive = new ArchivePage(page);
    const entry = new ArchiveEntryPage(page);

    await test.step('she gives the category a subcategory', async () => {
      await signInAs(page, ADMIN);
      await manager.openFromConsole();
      await manager.create(PHASE, PILOT);
    });

    await test.step('the manager will not let her sweep away a whole branch', async () => {
      // Not a refusal from the archive: the deletion never starts, so there is nothing to restore
      await expect(manager.deleteControl(PILOT)).toBeDisabled();
    });

    await test.step('so she deletes the subcategory first, and then the category', async () => {
      await manager.delete(PHASE);
      await expect(manager.deleteControl(PILOT)).toBeEnabled();
      await manager.delete(PILOT);
    });

    let parent = '';
    let child = '';

    await test.step('both are in the archive, the category above its subcategory', async () => {
      await archive.openFromConsole();
      await expect(archive.rows()).toHaveCount(2);

      // Newest first, so the one she deleted last is on top — and the one below it turns out to be
      // its child, which is what the next step turns on
      [ parent, child ] = await archive.listedPaths();
      expect(child, 'the subcategory should have been archived from inside its parent')
        .toContain(`${parent}/`);
    });

    await test.step('putting the subcategory back moves nothing', async () => {
      await archive.restore(child);

      await expect(archive.notice()).toContainText('Nothing was restored, because something is in the way');
      await expect(archive.notice()).toContainText('PARENT_MISSING');
      await expect(archive.rowFor(child)).toHaveCount(1);
    });

    await test.step('and the entry would have told her why first', async () => {
      await archive.openEntry(child);

      await expect(entry.item(child)).toContainText('the folder it was deleted from no longer exists');
      await expect(entry.item(child)).toContainText('PARENT_MISSING');
      // Refused before she can even ask for it, which is the whole reason this page exists
      await expect(entry.restoreEverything()).toBeDisabled();

      await page.getByRole('link', { name: 'Back to the archive' }).click();
    });

    await test.step('she puts the category back, and then the subcategory follows', async () => {
      await archive.restore(parent);
      await expect(archive.notice()).toContainText('Restored 1 item to where it was deleted from.');
      await expect(archive.rowFor(parent)).toHaveCount(0);

      await archive.restore(child);
      await expect(archive.rowFor(child)).toHaveCount(0);
      await expect(archive.empty()).toBeVisible();
    });

    await test.step('and the branch is whole again', async () => {
      await manager.openFromConsole();
      await manager.expand(PILOT);
      await expect(manager.edit(PHASE)).toBeVisible();
    });

    await test.step('she signs out', async () => {
      await shell.signOut();
    });
  });

  test('Miriam finds the way back blocked, and destroys the entry instead', async ({ page }) => {
    const shell = new AppShell(page);
    const manager = new CategoryManagerPage(page);
    const archive = new ArchivePage(page);
    const entry = new ArchiveEntryPage(page);

    await test.step('a category is deleted', async () => {
      await signInAs(page, ADMIN);
      await manager.openFromConsole();
      await manager.create(LEGACY);
      await manager.delete(LEGACY);
    });

    await test.step('and somebody makes a new one with the same name', async () => {
      // The manager takes the name because nothing is using it any more: the deleted one is in the
      // archive, not in the taxonomy
      await manager.create(LEGACY);
    });

    let deleted = '';

    await test.step('so the archive has nowhere to put the old one', async () => {
      await archive.openFromConsole();
      await expect(archive.rows()).toHaveCount(1);
      deleted = await archive.newestEntryPath();

      await archive.restore(deleted);
      await expect(archive.notice()).toContainText('Nothing was restored, because something is in the way');
      await expect(archive.notice()).toContainText('OCCUPIED');
      await expect(archive.rowFor(deleted)).toHaveCount(1);
    });

    await test.step('the entry says so before she commits to anything', async () => {
      await archive.openEntry(deleted);

      await expect(entry.item(deleted)).toContainText('something else is at that path now');
      await expect(entry.restoreEverything()).toBeDisabled();
      // The one thing that can still be done to it is offered; nothing else is
      await expect(entry.purgeControl()).toBeEnabled();
    });

    await test.step('she opens the purge dialog, and changes her mind', async () => {
      await entry.purgeControl().click();

      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(`Everything archived under ${deleted} will be destroyed.`);
      await expect(dialog).toContainText('This cannot be undone.');
      await expect(dialog).toContainText('1 archived item(s) will be permanently removed.');

      await dialog.getByRole('button', { name: 'Cancel' }).click();
      await expect(dialog).toHaveCount(0);
      await expect(entry.heading(deleted)).toBeVisible();
    });

    await test.step('then she means it, and it is gone for good', async () => {
      await entry.purge();

      await expect(page).toHaveURL(/\/admin\/archive$/);
      await expect(archive.empty()).toBeVisible();

      // Destroyed rather than hidden: the console counts what is in the archive, and it is not there
      await page.goto('/admin');
      expect(await archive.count('Archived in total')).toBe(0);
    });

    await test.step('she signs out', async () => {
      await shell.signOut();
    });
  });

  test('Miriam clears out three categories, and is asked for one of them back', async ({ page }) => {
    const shell = new AppShell(page);
    const manager = new CategoryManagerPage(page);
    const archive = new ArchivePage(page);

    await test.step('three categories are created in error, and cleared out in one sitting', async () => {
      await signInAs(page, ADMIN);
      await manager.openFromConsole();
      for (const specialty of SPECIALTIES) {
        await manager.create(specialty);
      }
      for (const specialty of SPECIALTIES) {
        await manager.delete(specialty);
      }
    });

    await test.step('the console counts all three', async () => {
      await page.goto('/admin');
      expect(await archive.count('Archived in the last 24 hours')).toBe(3);
      expect(await archive.count('Archived in total')).toBe(3);
    });

    await test.step('and the archive lists them, the last one deleted first', async () => {
      await archive.openFromConsole();

      const listed = await archive.listedPaths();
      expect(listed).toHaveLength(3);
      // Checked against the labels she typed rather than against paths nobody ever showed her: the
      // names themselves are the server's to derive
      const newestFirst = [ ...SPECIALTIES ].reverse();
      listed.forEach((path, position) => {
        expect(path.toLowerCase()).toContain(newestFirst[position].toLowerCase());
      });
    });

    let neurology = '';

    await test.step('she finds the one she was asked for by typing part of its name', async () => {
      await archive.filterBy('neuro');
      await expect(archive.rows()).toHaveCount(1);

      [ neurology ] = await archive.listedPaths();
      expect(neurology.toLowerCase()).toContain('neurology');
    });

    await test.step('and they are all hers, which the same box will tell her', async () => {
      // The filter takes the name of whoever did the deleting, not only the path
      await archive.filterBy('admin');
      await expect(archive.rows()).toHaveCount(3);
    });

    await test.step('she checks the order she deleted them in', async () => {
      await archive.filterBy('');
      await expect(archive.rows()).toHaveCount(3);

      await archive.sortBy('Deleted at');
      await expect
        .poll(async () => (await archive.listedPaths())[0].toLowerCase())
        .toContain(SPECIALTIES[0].toLowerCase());
    });

    await test.step('she puts back the one she was asked for', async () => {
      await archive.restore(neurology);
      await expect(archive.notice()).toContainText('Restored 1 item to where it was deleted from.');
      await expect(archive.rowFor(neurology)).toHaveCount(0);
    });

    await test.step('and destroys the other two', async () => {
      for (const path of await archive.listedPaths()) {
        await archive.purge(path);
        await expect(archive.notice())
          .toContainText('The entry and everything archived in it were permanently removed.');
      }

      await expect(archive.empty()).toBeVisible();
      await page.goto('/admin');
      expect(await archive.count('Archived in total')).toBe(0);
    });

    await test.step('the taxonomy has that one back, and neither of the others', async () => {
      await manager.openFromConsole();
      await expect(manager.edit('Neurology')).toBeVisible();
      await expect(manager.edit('Cardiology')).toHaveCount(0);
      await expect(manager.edit('Oncology')).toHaveCount(0);
    });

    await test.step('she signs out', async () => {
      await shell.signOut();
    });
  });

  test('Nadia goes looking for a deletion she was given no right to see', async ({ page, request }) => {
    const shell = new AppShell(page);
    const manager = new CategoryManagerPage(page);

    await test.step('Miriam deletes a category, and Nadia is given an account and nothing else', async () => {
      await signInAs(page, ADMIN);
      await manager.openFromConsole();
      await manager.create(DUPLICATE);
      await manager.delete(DUPLICATE);
      await shell.signOut();

      await ensureUser(request, NADIA);
    });

    await test.step('Nadia signs in, and is not offered the administration console', async () => {
      await signInAs(page, NADIA);
      // The account control is itself one of the application bar's extensions, so its arrival says
      // the bar has finished being assembled. Without that, finding no console button would only
      // mean the page had not caught up yet.
      expect(await shell.signedInAs()).toBe(NADIA.username);
      await expect(page.getByRole('button', { name: 'Administration' })).toHaveCount(0);
    });

    await test.step('the page that would show her the archive is not served to her', async () => {
      const refused = await page.goto('/admin/archive');
      expect(refused?.status()).not.toBe(200);
      // Told that it does not exist, rather than that she may not see it — which is the same answer
      // the archive's own endpoints give her, and the one an administrator's tools should give
      await expect(page.getByRole('heading', { name: 'Not found' })).toBeVisible();
    });

    await test.step('nor is the archive itself, however she asks for it', async () => {
      // Each refusal paired with the same request as the administrator, so that it can never be a
      // request that was malformed, or a path that does not answer anybody
      for (const url of [ '/Archive.entries.json', '/Archive.summary.json' ]) {
        const hers = await request.get(url, { headers: basicAuth(NADIA), maxRedirects: 0 });
        expect(hers.status(), `${url} was served to somebody with no right to it`).not.toBe(200);

        const miriams = await request.get(url, { headers: basicAuth(ADMIN), maxRedirects: 0 });
        expect(miriams.status(), `${url} did not answer the administrator either`).toBe(200);
      }
    });

    await test.step('so she goes back to her own work, and signs out', async () => {
      // The refusal page is not the application — it carries none of the shell, and so nothing to
      // sign out from. What it does carry is the way back, which is the only thing it owes her.
      await page.getByRole('button', { name: 'Go to the homepage' }).click();
      expect(await shell.signedInAs()).toBe(NADIA.username);

      await shell.signOut();
    });
  });

  // -----------------------------------------------------------------------------------------------
  // THE REST OF THE STORY, still prose because the platform cannot yet tell it:
  //
  //   Nadia deletes something of her own, and restores it herself.
  //
  // Waiting on: any grant on /Archive at all. It is granted to nobody, and administrators reach it
  // only by bypassing access control, so there is not yet such a thing as a person who may restore
  // one entry and not another. The NO_RIGHTS restore conflict — "you may not create it there" — is
  // unreachable for the same reason.
  //
  //   Miriam is refused a purge, because the deletion falls inside a period the deployment keeps.
  //
  // Waiting on: a guard that objects to a purge. The only one that exists refuses deletion, not
  // destruction, so the entry page's "This entry cannot be purged" has nothing to report. The
  // retention and policy vetoes are on their own branch.
  //
  //   She restores a deletion that took several things with it, and either all of them go back or
  //   none of them do.
  //
  // Waiting on: something that refers to a category. Every entry in these stories holds exactly one
  // item, because the manager refuses to delete a category that still has subcategories and nothing
  // else in the platform points at one — so "a restore is all or nothing", which the entry page says
  // out loud, is a promise no story here can make it keep.
  //
  //   She deletes a category that is in use, is told what refers to it, and retires it instead.
  //
  // Waiting on: the same thing. Half of the delete dialog is unreachable end to end until a category
  // can be referred to.
  // -----------------------------------------------------------------------------------------------
});
