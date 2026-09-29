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

import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";

import { appTheme } from "@iap/frontend-commons/appTheme";
import { getPageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import SchemaPage from "@iap/schemas/SchemaPage";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { serveSchemas } from "./schemaServer.fixture";

vi.mock("@iap/frontend-commons/actionsManager", () => ({
  getActions: (point: string) => import("./actions.fixture").then(fixture => fixture.actionsFor(point)),
}));

// Which the test environment does not lay out, so it cannot scroll either
const scrollIntoView = vi.fn();
Element.prototype.scrollIntoView = scrollIntoView;

afterEach(() => {
  vi.unstubAllGlobals();
  clearTagDefinitionsCache();
});

// In the app's theme, which is what makes a page's title its heading
const renderVersion = (schema: string, version: string) => render(
  <ThemeProvider theme={appTheme}>
    <MemoryRouter initialEntries={[ `/admin/schemas/${schema}?version=${version}` ]}>
      <Routes>
        <Route path="/admin/schemas/*" element={<SchemaPage />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>,
);

// The card of the part with the given heading
const card = async (heading: string) =>
  (await screen.findByText(heading, { ignore: "script, style, button *" })).closest("li") as HTMLElement;

// The places something may move to, by what they are called
const spots = () => screen.getAllByRole("button", { name: /^Move (before|to the end)/ })
  .map(spot => spot.getAttribute("aria-label"));

const expand = async (heading: string) =>
  fireEvent.click(await screen.findByRole("button", { name: `Expand ${heading}` }));

describe("SchemaVersionView", () => {
  it("shows where a version stands, what can be done with it, and the outline of what it asks", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    expect(await screen.findByRole("heading", { name: "Clinical study Version 2.0" })).toBeInTheDocument();
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(await screen.findByText("Active")).toBeInTheDocument();
    // The version's actions, not the schema's
    expect(await screen.findAllByRole("button", { name: "Retire" })).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "Rename" })).not.toBeInTheDocument();
    // The trail leads back to the schema, by its title
    expect(getPageCrumbs()).toEqual([ { path: "/admin/schemas/study", label: "Clinical study" } ]);
    // Requirements and sections start open, questions closed
    expect(await screen.findByText("Design")).toBeInTheDocument();
    const arms = await card("Which arms does it have?");
    expect(within(arms).getByText("Any number of answers")).toBeInTheDocument();
    expect(within(arms).getByText("2 options")).toBeInTheDocument();
    expect(within(arms).getByText("Shown as list")).toBeInTheDocument();
    expect(within(await card("Lead")).getByText("1 option")).toBeInTheDocument();
    expect(screen.queryByText("Placebo")).not.toBeInTheDocument();
    // When a part applies shows without opening it
    expect(within(await card("Minimum age"))
      .getByText("Only when the answer to “Which arms does it have?” includes all of “Placebo”")).toBeInTheDocument();
    expect(screen.queryByText("Link")).not.toBeInTheDocument();
  });

  it.each([
    [ "v3", "Everything in this version can change until it is activated." ],
    [ "v2", "Only the wording of this version can be corrected." ],
  ])("says what can change in %s, as its update does", async (versionName, notice) => {
    serveSchemas();
    renderVersion("study", versionName);
    expect(await screen.findByText(notice)).toBeInTheDocument();
  });

  it("opens a question on what it accepts and when it is asked", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(within(await card("Which arms does it have?")).getByRole("button", { name: "2 options" }));
    const options = await screen.findByRole("presentation");
    expect(within(options).getByText("Placebo")).toBeInTheDocument();
    expect(within(options).getByText(/stored as placebo/)).toBeInTheDocument();
    expect(within(options).getByText("drug")).toBeInTheDocument();
    fireEvent.keyDown(options, { key: "Escape" });
    await waitFor(() => expect(screen.queryByText("Placebo")).not.toBeInTheDocument());
    // And listed once the question is open
    await expand("Which arms does it have?");
    expect(await within(await card("Which arms does it have?")).findByText("Placebo")).toBeInTheDocument();
    await expand("Minimum age");
    expect(await screen.findByText("Between 18 and 99.")).toBeInTheDocument();
    await expand("Study code");
    expect(await screen.findByText("Must match ^[A-Z]+$.")).toBeInTheDocument();
    expect(screen.getByText("Otherwise the submitter reads “Capitals only”.")).toBeInTheDocument();
    await expand("Site");
    expect(await screen.findByText("The options are the items under /Sites.")).toBeInTheDocument();
    // Nothing more to say about a bare question, so nothing to open
    expect(screen.queryByRole("button", { name: "Expand Anything else?" })).not.toBeInTheDocument();
  });

  it("shows what each type of requirement asks for, and a type it does not know as itself", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    const consent = await card("Consent form");
    expect(within(consent).getByText("Optional")).toBeInTheDocument();
    expect(within(consent).getByText("Template provided")).toBeInTheDocument();
    await expand("Consent form");
    expect(await screen.findByText("Accepts application/pdf.")).toBeInTheDocument();
    expect(within(consent).getByText(/^Only when its tag list is/)).toBeInTheDocument();
    expect(within(await card("Protocol")).getByText("Required")).toBeInTheDocument();
    await expand("Ethics approval");
    expect(await screen.findByText("Approved by reb-members.")).toBeInTheDocument();
    await expand("Sign-off");
    expect(await screen.findByText("No approver group is set.")).toBeInTheDocument();
    expect(within(await card("Audit")).getByTitle("sch/AuditRequirement")).toBeInTheDocument();
  });

  it("corrects what a question says, and re-reads the version", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(within(await card("Which arms does it have?")).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog", { name: /Edit question/ });
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Which arms are there?" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v2/basics/design/arms.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ text: "Which arms are there?" });
    // Nothing to correct where nothing is offered
    expect(within(await card("Minimum age")).queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
  });

  it("corrects what an option says", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v2");

    await expand("Which arms does it have?");
    const placebo = (await screen.findByText("Placebo")).closest("li") as HTMLElement;
    fireEvent.click(within(placebo).getByRole("button", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog", { name: /Edit option/ });
    fireEvent.change(within(dialog).getByLabelText(/Label/), { target: { value: "Placebo arm" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v2/basics/design/arms/placebo.update.json"));
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ label: "Placebo arm" });
    expect(screen.getByText("drug").closest("li")?.querySelector("button")).toBeNull();
  });

  it("adds a requirement at the end of a draft", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    await card("Your name");
    fireEvent.click(screen.getAllByRole("button", { name: "Add" }).at(-1)!);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Document" }));
    const dialog = await screen.findByRole("dialog", { name: /New document/ });
    fireEvent.change(within(dialog).getByLabelText(/Label/), { target: { value: "Consent form" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3.create.json"));
    expect(posted[0].params.get("type")).toBe("sch:DocumentRequirement");
    expect(posted[0].params.has("before")).toBe(false);
    expect(JSON.parse(posted[0].params.get("patch") ?? "")).toEqual({ label: "Consent form" });
  });

  it("adds a part below another", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Your name")).getByRole("button", { name: "Add below" }));
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    fireEvent.click(within(await card("Your name")).getByRole("button", { name: "Add below" }));
    expect(screen.getAllByRole("menuitem").map(item => item.textContent)).toEqual([ "Section", "Question" ]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Question" }));
    const dialog = await screen.findByRole("dialog", { name: /New question/ });
    // Where it goes is already chosen
    expect(within(dialog).queryByRole("radiogroup")).not.toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Your email" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.create.json"));
    expect(posted[0].params.get("type")).toBe("sch:Question");
    expect(posted[0].params.get("before")).toBe("age");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Add below" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Section" }));
    const last = await screen.findByRole("dialog", { name: /New section/ });
    fireEvent.change(within(last).getByLabelText(/Title/), { target: { value: "Contact" } });
    fireEvent.click(within(last).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(posted).toHaveLength(2));
    expect(posted[1].params.has("before")).toBe(false);
  });

  it("adds a part at the start of what holds it", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    await card("Your name");
    fireEvent.click(screen.getAllByRole("button", { name: "Add" })[0]);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Question" }));
    const dialog = await screen.findByRole("dialog", { name: /New question/ });
    expect(within(dialog).getByRole("radio", { name: "At the end" })).toBeChecked();
    fireEvent.click(within(dialog).getByRole("radio", { name: "At the start" }));
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Your title" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.create.json"));
    expect(posted[0].params.get("before")).toBe("name");
  });

  it("adds an option to a question", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    await expand("Your name");
    fireEvent.click(screen.getByRole("button", { name: "Add option" }));
    const dialog = await screen.findByRole("dialog", { name: /New option/ });
    fireEvent.change(within(dialog).getByLabelText(/Value/), { target: { value: "long" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/name.create.json"));
    expect(posted[0].params.get("type")).toBe("sch:AnswerOption");
  });

  it("removes a part or an option, and says why one stays", async () => {
    const posted = serveSchemas({ answers: { "/Schemas/study/v3/intake/age.discard.json": {
      status: 409, error: "The conditions of \"Your name\" depend on it. Change those conditions first.",
    } } });
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Remove" }));
    const dialog = await screen.findByRole("dialog", { name: /Remove this question/ });
    fireEvent.click(within(dialog).getByRole("button", { name: "Remove" }));
    expect(await within(dialog).findByText(/depend on it/)).toBeInTheDocument();
    // Asking again would be refused again
    expect(within(dialog).getByRole("button", { name: "Remove" })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await expand("Your name");
    const short = screen.getByText("Short").closest("li") as HTMLElement;
    fireEvent.click(within(short).getByRole("button", { name: "Remove" }));
    fireEvent.click(within(await screen.findByRole("dialog", { name: /Remove this option/ }))
      .getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(posted.map(event => event.url))
      .toEqual([ "/Schemas/study/v3/intake/age.discard.json", "/Schemas/study/v3/intake/name/short.discard.json" ]));
  });

  it("moves a part before another, where nothing else can be done meanwhile", async () => {
    const posted = serveSchemas({ answers: {
      "/Schemas/study/v3/intake/age.move.json": { redirect: "/Schemas/study/v3/intake/age" },
    } });
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Move" }));
    expect(screen.getByRole("status")).toHaveTextContent("Choose where the question goes. Each move is saved at once.");
    expect(within(await card("Your age")).getByRole("button", { name: "Move" })).toHaveAttribute("aria-pressed", "true");
    expect(within(await card("Intake")).queryByRole("button", { name: /^Edit|^Add|^Remove/ }))
      .not.toBeInTheDocument();
    // Only where it would go somewhere new, and only into what holds questions
    expect(spots()).toEqual([ "Move before Your name", "Move to the end of Follow-up" ]);
    fireEvent.click(screen.getByRole("button", { name: "Move before Your name" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/age.move.json"));
    expect(Object.fromEntries(posted[0].params)).toEqual({ parent: "/Schemas/study/v3/intake", before: "name" });
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    // What moved is shown, and the actions are back
    expect(document.activeElement).toBe(await card("Your age"));
    expect(scrollIntoView).toHaveBeenCalledWith({ block: "nearest" });
    expect(screen.getByText("“Your age” was moved")).toBeInTheDocument();
    expect(within(await card("Your age")).getByRole("button", { name: "Remove" })).toBeInTheDocument();
  });

  it("moves a part to the end of a closed part, which opens to show it", async () => {
    const posted = serveSchemas({ answers: {
      "/Schemas/study/v3/intake/age.move.json": { redirect: "/Schemas/study/v3/followUp/age" },
    } });
    renderVersion("study", "v3");

    expect(await screen.findByRole("button", { name: "Expand Follow-up" })).toBeInTheDocument();
    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Move" }));
    fireEvent.click(screen.getByRole("button", { name: "Move to the end of Follow-up" }));

    await waitFor(() => expect(posted[0]?.params.get("parent")).toBe("/Schemas/study/v3/followUp"));
    expect(posted[0].params.has("before")).toBe(false);
    expect(await screen.findByRole("button", { name: "Collapse Follow-up" })).toBeInTheDocument();
  });

  it("moves a requirement to the end of the version", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Intake")).getAllByRole("button", { name: "Move" })[0]);
    expect(screen.getByRole("status")).toHaveTextContent("Choose where the form goes.");
    expect(spots()).toEqual([ "Move to the end" ]);
    fireEvent.click(screen.getByRole("button", { name: "Move to the end" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.move.json"));
    expect(Object.fromEntries(posted[0].params)).toEqual({ parent: "/Schemas/study/v3" });
  });

  it("moves an option among the options of its question", async () => {
    const posted = serveSchemas({ answers: {
      "/Schemas/study/v3/intake/name/short.move.json": { redirect: "/Schemas/study/v3/intake/name/short" },
    } });
    renderVersion("study", "v3");

    await expand("Your name");
    fireEvent.click(within(screen.getByText("Short").closest("li") as HTMLElement)
      .getByRole("button", { name: "Move" }));
    expect(screen.getByRole("status")).toHaveTextContent("Choose where the option goes.");
    expect(spots()).toEqual([ "Move to the end of Your name" ]);
    fireEvent.click(screen.getByRole("button", { name: "Move to the end of Your name" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/name/short.move.json"));
    expect(Object.fromEntries(posted[0].params)).toEqual({ parent: "/Schemas/study/v3/intake/name" });
    await waitFor(() => expect(document.activeElement).toBe(screen.getByText("Short").closest("li")));
    expect(screen.getByText("“Short” was moved")).toBeInTheDocument();
  });

  it("stops moving on Cancel, on Escape, or on the same Move again, giving the focus back", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    const move = within(await card("Your age")).getByRole("button", { name: "Move" });
    // Where the places to move to push it, the page follows, so it stays under the pointer
    const scrollBy = vi.fn();
    vi.stubGlobal("scrollBy", scrollBy);
    vi.spyOn(move, "getBoundingClientRect")
      .mockReturnValueOnce(DOMRect.fromRect({ y: 100 }))
      .mockReturnValueOnce(DOMRect.fromRect({ y: 160 }));
    fireEvent.click(move);
    expect(scrollBy).toHaveBeenCalledWith(0, 60);
    fireEvent.click(within(screen.getByRole("status")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(document.activeElement).toBe(move);
    expect(scrollBy).toHaveBeenCalledTimes(1);

    fireEvent.click(move);
    fireEvent.keyDown(document, { key: "Enter" });
    expect(screen.getByRole("status")).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    fireEvent.click(move);
    fireEvent.click(within(await card("Your name")).getByRole("button", { name: "Move" }));
    expect(within(await card("Your age")).getByRole("button", { name: "Move" })).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(within(await card("Your name")).getByRole("button", { name: "Move" }));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(posted).toEqual([]);
  });

  it("says why a move was refused, and lets another place be chosen", async () => {
    const posted = serveSchemas({ answers: {
      "/Schemas/study/v3/intake/age.move.json": { status: 400, error: "Your age cannot move there." },
    } });
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Move" }));
    fireEvent.click(screen.getByRole("button", { name: "Move before Your name" }));
    expect(screen.getByRole("button", { name: "Move before Your name" })).toBeDisabled();

    const said = await screen.findByText("Your age cannot move there.");
    expect(screen.getByRole("button", { name: "Move before Your name" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Move to the end of Follow-up" }));
    await waitFor(() => expect(posted).toHaveLength(2));
    // Said again, so a second refusal is noticed too
    await waitFor(() => expect(screen.getByText("Your age cannot move there.")).not.toBe(said));
  });

  it("collapses what it contains", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(await screen.findByRole("button", { name: "Collapse Basic information" }));

    await waitFor(() => expect(screen.queryByText("Design")).not.toBeInTheDocument());
  });

  it("says when a version asks for nothing yet", async () => {
    serveSchemas();
    renderVersion("idea", "v1");

    expect(await screen.findByText("This version asks for nothing yet.")).toBeInTheDocument();
  });

  it("says when the schema has no such version", async () => {
    serveSchemas();
    renderVersion("study", "v9");

    expect(await screen.findByText("This schema has no version v9.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Clinical study Version v9" })).toBeInTheDocument();
  });

  it("keeps saying that the schema is retired", async () => {
    serveSchemas();
    renderVersion("legacy", "v1");

    expect(await screen.findByText(/This schema is retired/)).toBeInTheDocument();
  });

  it("reports content that cannot be read", async () => {
    serveSchemas({ failContent: 500 });
    renderVersion("study", "v2");

    expect(await screen.findByText("The version's content could not be loaded")).toBeInTheDocument();
  });

  it("starts a new version as a copy of this one, and opens it", async () => {
    const posted = serveSchemas({ answers: { "/Schemas/study.createVersion.json": { redirect: "/Schemas/study/v4" } } });
    renderVersion("study", "v2");

    fireEvent.click(await screen.findByRole("button", { name: "New version from this one" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByLabelText(/Label/)).toHaveValue("4.0");
    expect(within(dialog).getByRole("combobox", { name: "Start from" })).toHaveTextContent("A copy of version 2.0");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    // Where it opens: a version this stand-in server does not have
    expect(await screen.findByText("This schema has no version v4.")).toBeInTheDocument();
    expect(posted[0].params.get("source")).toBe("/Schemas/study/v2");
    expect(screen.getByText("Version 4.0 is created")).toBeInTheDocument();
  });

  it("says in its dialog why a new version was refused", async () => {
    serveSchemas({ answers: { "/Schemas/study.createVersion.json": {
      status: 400, error: "There is already a version 4.0.",
    } } });
    renderVersion("study", "v2");

    fireEvent.click(await screen.findByRole("button", { name: "New version from this one" }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Create" }));

    expect(await within(dialog).findByText("There is already a version 4.0.")).toBeInTheDocument();
  });

  it("starts no new version of a retired schema", async () => {
    serveSchemas();
    renderVersion("legacy", "v1");

    expect(await screen.findByRole("button", { name: "Retire" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New version from this one" })).not.toBeInTheDocument();
  });

  it("acts on the version, and goes back to the schema once it is discarded", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Activate" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(await screen.findByRole("button", { name: "Discard" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard" }));

    expect(await screen.findByRole("gridcell", { name: "1.0" })).toBeInTheDocument();
    expect(posted.map(event => event.url))
      .toEqual([ "/Schemas/study/v3.activate.json", "/Schemas/study/v3.discard.json" ]);
  });
});
