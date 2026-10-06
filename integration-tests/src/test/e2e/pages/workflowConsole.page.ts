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

import { expect, type Locator, type Page } from '@playwright/test';

/**
 * The workflow console, the administration tool workflows are authored in: one homepage's listing, a
 * workflow's own page with its table of versions, and a version's diagram editor.
 *
 * A version is located by the label it was given, which is what its row shows, and what may be done to it
 * by the actions that row offers, each named the way a person reads it. Repository paths are read off the
 * URLs the console navigates to rather than predicted: the server derives node names, and nobody using the
 * console is told what it decided.
 */
export class WorkflowConsolePage {
  constructor(private readonly page: Page) {}

  /** Opens the console the way an administrator reaches it: from the dashboard, by its own link. */
  async openFromConsole(): Promise<void> {
    await this.page.goto('/admin');
    await this.page.getByRole('link', { name: 'Manage: Workflows' }).click();
    await expect(this.page).toHaveURL(/\/admin\/workflows\/Workflows$/);
    await expect(this.page.getByRole('button', { name: 'New workflow' })).toBeVisible();
  }

  /**
   * Creates a workflow from the listing, and waits for the editor the console opens on its first version.
   *
   * @returns the first version's repository path
   */
  async create(title: string, description: string): Promise<string> {
    await this.page.getByRole('button', { name: 'New workflow' }).click();
    const dialog = this.page.getByRole('dialog');
    // By the name each field announces rather than by its label's text, which also carries the required marker
    await dialog.getByRole('textbox', { name: 'Title', exact: true }).fill(title);
    await dialog.getByRole('textbox', { name: 'Description', exact: true }).fill(description);
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(dialog).toHaveCount(0);
    return this.editedVersion();
  }

  /** Opens a version's diagram in the editor, and waits for the canvas to draw it. */
  async openEditor(version: string): Promise<void> {
    await this.page.goto(`/admin/workflows${version}.edit`);
    await expect(this.page.locator('.djs-shape').first()).toBeVisible();
  }

  /** The editor's heading, which names the workflow and the version open in it. */
  editorHeading(workflow: string, version: string): Locator {
    return this.page.getByRole('heading', { name: `${workflow}: Version ${version}` });
  }

  /**
   * Renames one element of the diagram open in the editor, the way an author does: by selecting it on the
   * canvas and typing its name into the properties panel.
   */
  async rename(elementId: string, name: string): Promise<void> {
    await this.page.locator(`.djs-shape[data-element-id="${elementId}"]`).click();
    // The panel's field announces no name of its own, only the caption beside it
    await this.page.getByText('Name:').getByRole('textbox').fill(name);
    await expect(this.page.getByText('Unsaved changes')).toBeVisible();
  }

  /** Saves the diagram open in the editor, staying in it. */
  async save(): Promise<void> {
    await this.page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(this.page.getByText('The diagram was saved')).toBeVisible();
  }

  /** Opens a workflow's own page, and waits for its versions to be listed. */
  async openWorkflow(workflow: string): Promise<void> {
    await this.page.goto(`/admin/workflows${workflow}`);
    await expect(this.page.getByRole('row').nth(1)).toBeVisible();
  }

  /** What the workflow's page says about whether it runs: `Enabled`, `Disabled` or `Retired`. */
  runs(): Locator {
    return this.page.getByText('Runs', { exact: true }).locator('xpath=following-sibling::*[1]');
  }

  /** One version's row in the workflow's table of versions. */
  version(label: string): Locator {
    return this.page.getByRole('row')
      .filter({ has: this.page.getByRole('gridcell', { name: label, exact: true }) });
  }

  /**
   * The actions one version's row offers, by name and in alphabetical order: what the server offers on the
   * version, as the console reads it.
   */
  async offered(label: string): Promise<string[]> {
    const actions = this.version(label).locator('button[aria-label], a[aria-label]');
    const names = await actions.evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-label') ?? ''));
    return names.sort();
  }

  /**
   * Moves one version along its lifecycle from its row, confirming as a person does, and waits for the row to
   * show where the version stands now.
   */
  async move(label: string, action: string, confirmation: string, lifecycle: string): Promise<void> {
    await this.version(label).getByRole('button', { name: action, exact: true }).click();
    const dialog = this.page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: confirmation })).toBeVisible();
    await dialog.getByRole('button', { name: action, exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(this.version(label)).toContainText(lifecycle);
  }

  /**
   * Drafts a copy of one version from its row, accepting the label the console suggests, and waits for the
   * editor it opens on the copy.
   *
   * @returns the copy's repository path
   */
  async draftFrom(label: string, suggested: string): Promise<string> {
    await this.version(label).getByRole('button', { name: 'New draft from this', exact: true }).click();
    const dialog = this.page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: `New draft from version ${label}` })).toBeVisible();
    await expect(dialog.getByRole('textbox', { name: 'Version', exact: true })).toHaveValue(suggested);
    await dialog.getByRole('button', { name: 'Create draft' }).click();
    await expect(dialog).toHaveCount(0);
    return this.editedVersion();
  }

  /** The repository path of the version the editor has open, read off its URL once the editor is there. */
  private async editedVersion(): Promise<string> {
    await expect(this.page).toHaveURL(/\.edit$/);
    await expect(this.page.locator('.djs-shape').first()).toBeVisible();
    return new URL(this.page.url()).pathname.replace(/^\/admin\/workflows/, '').replace(/\.edit$/, '');
  }
}
