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

import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import type { SerializedNode } from "@iap/frontend-commons/serializedNode";
import SchemaTemplateActions from "@iap/schemas/SchemaTemplateActions";
import { ReloadTree } from "@iap/schemas/schemaTree";

const PATH = "/Schemas/study/v3/consent";

const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const requirement = (overrides: Partial<SerializedNode> = {}): SerializedNode => ({
  "@path": PATH, "sling:resourceType": "sch/DocumentRequirement", "label": "Consent form",
  "@events": [ "update", "setTemplate", "removeTemplate" ], ...overrides,
});

const serve = (refusal?: string) => {
  const fetch = vi.fn((url: string) => Promise.resolve({
    ok: !refusal, status: refusal ? 400 : 200, url, redirected: false,
    json: () => Promise.resolve(refusal ? { error: refusal } : { status: "completed" }),
  } as unknown as Response));
  vi.stubGlobal("fetch", fetch);
  return fetch;
};

// What the file dialog hands back. The input is made on the spot and never drawn, so it is caught as it is clicked.
const choosing = (file?: File) => {
  const opened: HTMLInputElement[] = [];
  vi.spyOn(HTMLInputElement.prototype, "click").mockImplementation(function (this: HTMLInputElement) {
    opened.push(this);
    Object.defineProperty(this, "files", { value: file ? [ file ] : [] });
    this.dispatchEvent(new Event("change"));
  });
  return opened;
};

const renderActions = (node: SerializedNode) => {
  const reload = vi.fn();
  render(
    <ReloadTree value={reload}>
      <SchemaTemplateActions node={node} />
    </ReloadTree>,
    { wrapper: NoticeProvider },
  );
  return reload;
};

describe("SchemaTemplateActions", () => {
  it("uploads the file picked as the requirement's template, then reads the tree again", async () => {
    const fetch = serve();
    choosing(new File([ "%PDF" ], "consent.pdf", { type: "application/pdf" }));
    const reload = renderActions(requirement());

    fireEvent.click(screen.getByRole("button", { name: "Upload template" }));

    await waitFor(() => { expect(reload).toHaveBeenCalled(); });
    const [ url, init ] = fetch.mock.calls[0] as unknown as [ string, RequestInit ];
    expect(url).toBe(`${PATH}.setTemplate.json`);
    expect(((init.body as FormData).get("file") as File).name).toBe("consent.pdf");
    expect(await screen.findByText("Template uploaded")).toBeInTheDocument();
  });

  it("offers only a .docx for a generated document, and anything for a blank", () => {
    serve();
    const opened = choosing();
    renderActions(requirement({ generated: true }));
    fireEvent.click(screen.getByRole("button", { name: "Upload template" }));
    renderActions(requirement());
    fireEvent.click(screen.getAllByRole("button", { name: "Upload template" })[1]);

    expect(opened[0].accept).toBe(`.docx,${DOCX}`);
    expect(opened[1].accept).toBe("");
  });

  it("sends nothing when the file dialog was dismissed", () => {
    const fetch = serve();
    choosing();
    renderActions(requirement());

    fireEvent.click(screen.getByRole("button", { name: "Upload template" }));

    expect(fetch).not.toHaveBeenCalled();
  });

  it("says why a template was refused, in the engine's own words", async () => {
    serve("Consent form is generated from the answers, so its template has to be a Word (.docx) file");
    choosing(new File([ "%PDF" ], "consent.pdf", { type: "application/pdf" }));
    const reload = renderActions(requirement());

    fireEvent.click(screen.getByRole("button", { name: "Upload template" }));

    expect(await screen.findByText("The template was not uploaded")).toBeInTheDocument();
    expect(screen.getByText(/has to be a Word \(\.docx\) file/)).toBeInTheDocument();
    expect(reload).not.toHaveBeenCalled();
  });

  it("offers to replace or remove a template already there", async () => {
    const fetch = serve();
    const reload = renderActions(requirement({ generated: true, template: { "jcr:primaryType": "nt:file" } }));

    expect(screen.getByRole("button", { name: "Replace template" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Remove template" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/cannot be published/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove template" }));

    await waitFor(() => { expect(reload).toHaveBeenCalled(); });
    const [ url, init ] = fetch.mock.calls[0] as unknown as [ string, RequestInit ];
    expect(url).toBe(`${PATH}.removeTemplate.json`);
    expect((init.body as URLSearchParams).get("content")).toBe("null");
  });

  it("says what removing a blank costs the submitter", async () => {
    serve();
    renderActions(requirement({ template: { "jcr:primaryType": "nt:file" } }));

    fireEvent.click(screen.getByRole("button", { name: "Remove template" }));

    expect(await screen.findByText(/no longer offered a blank/)).toBeInTheDocument();
  });

  it("offers nothing the server does not", () => {
    renderActions(requirement({ "@events": [ "update" ], "template": { "jcr:primaryType": "nt:file" } }));

    expect(screen.queryByRole("button", { name: /template/ })).toBeNull();
  });
});
