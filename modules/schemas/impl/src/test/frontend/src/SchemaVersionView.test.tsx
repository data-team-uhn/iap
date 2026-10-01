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
import { NoticeProvider } from "@iap/frontend-commons/components/NoticeSnackbar";
import { getPageCrumbs } from "@iap/frontend-commons/pageCrumbs";
import { stubPhone } from "@iap/frontend-commons/phone.fixture";
import SchemaPage from "@iap/schemas/SchemaPage";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { CONTENT, serveSchemas } from "./schemaServer.fixture";

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
    <MemoryRouter initialEntries={[ `/admin/schemas/${schema}/${version}` ]}>
      <Routes>
        <Route path="/admin/schemas/*" element={<SchemaPage />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>,
  { wrapper: NoticeProvider },
);

// The card of the part with the given heading
const card = async (heading: string) =>
  (await screen.findByText(heading, { ignore: "script, style, button *" })).closest("li") as HTMLElement;

// The places something may move to, by what they are called
const spots = () => screen.getAllByRole("button", { name: /^Move (before|to the end)/ })
  .map(spot => spot.getAttribute("aria-label"));

// Where renaming "Your name" is asked for
const RENAME_NAME = "/Schemas/study/v3/intake/name.rename.json";

// Opens the edit dialog of the part with the given heading
const editPart = async (heading: string) => {
  fireEvent.click(within(await card(heading)).getByRole("button", { name: "Edit" }));
  return screen.findByRole("dialog");
};

// Turns the identifier of the part being edited into a field, and gives that field
const startRenaming = (dialog: HTMLElement) => {
  fireEvent.click(within(dialog).getByRole("button", { name: "Rename" }));
  return within(dialog).getByRole("textbox", { name: "Identifier" });
};

// Gives the identifier being changed a new name, and saves it
const renameTo = (dialog: HTMLElement, name: string) => {
  fireEvent.change(within(dialog).getByRole("textbox", { name: "Identifier" }), { target: { value: name } });
  fireEvent.click(within(dialog).getByRole("button", { name: "Save the identifier" }));
};

// How many times the draft's whole tree has been read
const treeReads = () => vi.mocked(fetch).mock.calls.filter(([ url ]) => String(url).includes("/v3.deep")).length;

// Picks an option of a select in a dialog
const pick = async (dialog: HTMLElement, label: string, option: string) => {
  fireEvent.mouseDown(within(dialog).getByRole("combobox", { name: label }));
  fireEvent.click(await screen.findByRole("option", { name: option }));
};

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
      .getByText("Only when the answer to “Which arms does it have?” includes “Placebo”")).toBeInTheDocument();
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

    // Its options' chip opens and closes it, as its arrow does
    const chip = within(await card("Which arms does it have?")).getByRole("button", { name: "2 options" });
    expect(chip).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(chip);
    expect(chip).toHaveAttribute("aria-expanded", "true");
    const arms = await card("Which arms does it have?");
    expect(await within(arms).findByText("Placebo")).toBeInTheDocument();
    // What an answer stores, where it differs from what the submitter reads
    expect(within(arms).getByText("placebo")).toBeInTheDocument();
    expect(within(arms).getByText("drug")).toBeInTheDocument();
    expect(within(arms).getByText("A substance with no effect")).toBeInTheDocument();
    fireEvent.click(chip);
    await waitFor(() => expect(screen.queryByText("Placebo")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Expand Which arms does it have?" })).toBeInTheDocument();
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
    expect(within(consent).getByText(/^Only when the submission's tag list is/)).toBeInTheDocument();
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

  it("renames a part on its own, then saves the rest where it now is", async () => {
    const posted = serveSchemas({ answers: { [RENAME_NAME]: { redirect: "/Schemas/study/v3/intake/fullName" } } });
    renderVersion("study", "v3");

    const dialog = await editPart("Your name");
    // Right after what names the part
    expect(within(dialog).getByLabelText(/Question/).compareDocumentPosition(within(dialog).getByText("name")))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    const identifier = startRenaming(dialog);
    expect(identifier).toHaveFocus();
    expect(within(dialog).getByText("Letters, digits, - and _.")).toBeInTheDocument();
    renameTo(dialog, " fullName ");

    expect(await within(dialog).findByText("fullName")).toBeInTheDocument();
    expect(posted[0].url).toBe(RENAME_NAME);
    expect(posted[0].params.get("name")).toBe("fullName");
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Your full name" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[1]?.url).toBe("/Schemas/study/v3/intake/fullName.update.json"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("says why a rename was refused, and reads nothing again when nothing was renamed", async () => {
    const posted = serveSchemas({ answers: { [RENAME_NAME]: { status: 400, error: "2x is not a name this can take" } } });
    renderVersion("study", "v3");

    const dialog = await editPart("Your name");
    startRenaming(dialog);
    renameTo(dialog, "2x");
    expect(await within(dialog).findByText("2x is not a name this can take")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel renaming" }));
    expect(within(dialog).getByText("name")).toBeInTheDocument();

    const before = treeReads();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(treeReads()).toBe(before);
    expect(posted).toHaveLength(1);
  });

  it("renames from the keyboard, and keeps its name when confirmed as it is", async () => {
    const posted = serveSchemas({ answers: { [RENAME_NAME]: { redirect: "/Schemas/study/v3/intake/fullName" } } });
    renderVersion("study", "v3");

    const dialog = await editPart("Your name");
    fireEvent.keyDown(startRenaming(dialog), { key: "Enter" });
    expect(within(dialog).getByText("name")).toBeInTheDocument();

    const identifier = startRenaming(dialog);
    fireEvent.keyDown(identifier, { key: "a" });
    fireEvent.keyDown(identifier, { key: "Escape" });
    // Only the renaming is cancelled
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(within(dialog).queryByRole("textbox", { name: "Identifier" })).not.toBeInTheDocument();

    const again = startRenaming(dialog);
    fireEvent.change(again, { target: { value: "fullName" } });
    fireEvent.keyDown(again, { key: "Enter" });
    expect(await within(dialog).findByText("fullName")).toBeInTheDocument();
    expect(posted.map(event => event.url)).toEqual([ RENAME_NAME ]);
  });

  it("reads the tree again when closed after a rename", async () => {
    serveSchemas({ answers: { [RENAME_NAME]: { redirect: "/Schemas/study/v3/intake/fullName" } } });
    renderVersion("study", "v3");

    const dialog = await editPart("Your name");
    startRenaming(dialog);
    renameTo(dialog, "fullName");
    await within(dialog).findByText("fullName");

    const before = treeReads();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(treeReads()).toBe(before + 1));
  });

  it("shows an identifier that cannot change, and none for an option", async () => {
    serveSchemas();
    renderVersion("study", "v2");

    fireEvent.click(within(await card("Which arms does it have?")).getAllByRole("button", { name: "Edit" })[0]);
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("arms")).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Rename" })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await expand("Which arms does it have?");
    fireEvent.click(within(screen.getByText("Placebo").closest("li") as HTMLElement)
      .getByRole("button", { name: "Edit" }));
    expect(within(await screen.findByRole("dialog")).queryByText("Identifier")).not.toBeInTheDocument();
  });

  it("adds a requirement at the end of a draft", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    await card("Your name");
    fireEvent.click(screen.getAllByRole("button", { name: "Add" }).at(-1)!);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Document" }));
    const dialog = await screen.findByRole("dialog", { name: /Add document/ });
    expect(within(dialog).getByText("of this version")).toBeInTheDocument();
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
    const dialog = await screen.findByRole("dialog", { name: /Add question/ });
    // Where it goes is already chosen, and said
    expect(within(dialog).getByText("after “Your name”")).toBeInTheDocument();
    expect(within(dialog).queryByRole("group", { name: "Where it goes" })).not.toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Your email" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.create.json"));
    expect(posted[0].params.get("type")).toBe("sch:Question");
    expect(posted[0].params.get("before")).toBe("age");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Add below" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Section" }));
    const last = await screen.findByRole("dialog", { name: /Add section/ });
    fireEvent.change(within(last).getByLabelText(/Title/), { target: { value: "Contact" } });
    fireEvent.click(within(last).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(posted).toHaveLength(2));
    expect(posted[1].params.has("before")).toBe(false);
  });

  it("creates a part with the identifier suggested from what it says, until it is given its own", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Add below" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Question" }));
    const dialog = await screen.findByRole("dialog", { name: /Add question/ });
    const identifier = within(dialog).getByRole("textbox", { name: "Identifier" });
    const question = within(dialog).getByLabelText(/Question/);
    // Right after what names the part
    expect(question.compareDocumentPosition(identifier)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(identifier).toHaveValue("question");
    // Free among what the form holds already
    fireEvent.change(question, { target: { value: "Name" } });
    expect(identifier).toHaveValue("name2");
    fireEvent.change(question, { target: { value: "Your e-mail address" } });
    expect(identifier).toHaveValue("yourEMailAddress");

    fireEvent.change(identifier, { target: { value: "email" } });
    fireEvent.change(question, { target: { value: "Your e-mail" } });
    expect(identifier).toHaveValue("email");
    fireEvent.change(identifier, { target: { value: "" } });
    expect(identifier).toHaveValue("yourEMail");
    fireEvent.change(identifier, { target: { value: "contact" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.create.json"));
    expect(posted[0].params.get("name")).toBe("contact");
  });

  it("sends the suggested identifier when none is given, and none for an option", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Add below" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Question" }));
    const dialog = await screen.findByRole("dialog", { name: /Add question/ });
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Where do you live?" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(posted[0]?.params.get("name")).toBe("whereDoYouLive"));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await expand("Your name");
    fireEvent.click(screen.getByRole("button", { name: "Add option" }));
    const option = await screen.findByRole("dialog", { name: /Add option/ });
    expect(within(option).queryByRole("textbox", { name: "Identifier" })).not.toBeInTheDocument();
    fireEvent.change(within(option).getByLabelText(/Value/), { target: { value: "long" } });
    fireEvent.click(within(option).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(posted).toHaveLength(2));
    expect(posted[1].params.has("name")).toBe(false);
  });

  it("adds a part at the start of what holds it", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    await card("Your name");
    fireEvent.click(screen.getAllByRole("button", { name: "Add" })[0]);
    fireEvent.click(await screen.findByRole("menuitem", { name: "Question" }));
    const dialog = await screen.findByRole("dialog", { name: /Add question/ });
    expect(within(dialog).getByText("of “Intake”")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "at the end" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(within(dialog).getByRole("button", { name: "at the start" }));
    // Choosing it again keeps it chosen
    fireEvent.click(within(dialog).getByRole("button", { name: "at the start" }));
    expect(within(dialog).getByRole("button", { name: "at the start" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Your title" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.create.json"));
    expect(posted[0].params.get("before")).toBe("name");
  });

  it("says what an empty part gains, with nothing to choose about where", async () => {
    serveSchemas();
    renderVersion("study", "v3");

    await expand("Follow-up");
    fireEvent.click(within(await card("Follow-up")).getByRole("button", { name: "Add" }));
    fireEvent.click(await screen.findByRole("menuitem", { name: "Question" }));
    const dialog = await screen.findByRole("dialog", { name: /Add question/ });

    expect(within(dialog).getByText("in “Follow-up”")).toBeInTheDocument();
    expect(within(dialog).queryByRole("group", { name: "Where it goes" })).not.toBeInTheDocument();
  });

  it("adds an option to a question", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    await expand("Your name");
    fireEvent.click(screen.getByRole("button", { name: "Add option" }));
    const dialog = await screen.findByRole("dialog", { name: /Add option/ });
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
    expect(within(dialog).getByRole("button", { name: "Removing…" })).toBeDisabled();
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

  it("sets when a part applies from the answer to another question", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    // Where it cannot be set, it is not offered
    expect(within(await card("Your name")).queryByRole("button", { name: "When it applies" })).not.toBeInTheDocument();
    fireEvent.click(within(await card("Your age")).getByRole("button", { name: "When it applies" }));
    const dialog = await screen.findByRole("dialog", { name: "When this question applies" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    expect(within(dialog).getByRole("combobox", { name: "Compare" })).toHaveTextContent("The answer to a question");
    fireEvent.keyDown(within(dialog).getByRole("combobox", { name: "Question" }), { key: "ArrowDown" });
    // Not itself, and by its identifier too
    const [ only, ...others ] = screen.getAllByRole("option");
    expect(others).toEqual([]);
    expect(within(only).getByText("Your name")).toBeInTheDocument();
    expect(within(only).getByText("name")).toBeInTheDocument();
    fireEvent.click(only);
    // Its options are offered at once
    expect(await screen.findByRole("option", { name: "Short" })).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("listbox"), { key: "Escape" });
    await pick(dialog, "Comparison", "is not empty");
    expect(within(dialog).getByText("Only when the answer to “Your name” is not empty")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/age.condition.json"));
    expect(JSON.parse(posted[0].params.get("content") ?? "")).toMatchObject({
      "jcr:primaryType": "cond:ConditionGroup",
      "condition1": { comparator: "is not empty", operandA: { source: "answer", value: [ "uuid-name" ] } },
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("changes a part's condition from the line reading it with the labels of its tags", async () => {
    const posted = serveSchemas();
    renderVersion("study", "v3");

    const followUp = await card("Follow-up");
    // The line saying when it applies is what changes it
    const line = await within(followUp).findByRole("button", { name: "Change when it applies" });
    expect(line).toHaveAccessibleDescription("Only when the submission's tag list includes “Draft”");
    fireEvent.click(line);
    const dialog = await screen.findByRole("dialog", { name: /When this .* applies/ });
    expect(within(dialog).getByText("Draft")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Clear all conditions" }));

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/followUp.condition.json"));
    expect(posted[0].params.get("content")).toBe("null");
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
    expect(within(await card("Follow-up")).queryByRole("button", { name: /when it applies/ })).not.toBeInTheDocument();
    // Only where it would go somewhere new, and only into what holds questions
    expect(spots()).toEqual([ "Move before Your name", "Move to the end of Follow-up" ]);
    const scrollBy = vi.fn();
    vi.stubGlobal("scrollBy", scrollBy);
    const spot = screen.getByRole("button", { name: "Move before Your name" });
    // Where the place chosen is, and where the tree shows the part once it has moved
    vi.spyOn(spot, "getBoundingClientRect").mockReturnValue({ top: 200 } as DOMRect);
    vi.spyOn(await card("Your age"), "getBoundingClientRect").mockReturnValue({ top: 260 } as DOMRect);
    fireEvent.click(spot);
    expect(spot).toHaveTextContent(/Moving “Your age” before\s*Your name…/);

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/age.move.json"));
    expect(Object.fromEntries(posted[0].params)).toEqual({ parent: "/Schemas/study/v3/intake", before: "name" });
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    // What moved is shown where it was sent from, and the actions are back
    expect(document.activeElement).toBe(await card("Your age"));
    expect(scrollBy).toHaveBeenCalledWith(0, 60);
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

  it("moves an option up or down among its question's, from under the pointer", async () => {
    const posted = serveSchemas({ answers: {
      "/Schemas/study/v3/intake/name/full.move.json": { redirect: "/Schemas/study/v3/intake/name/full" },
    } });
    const scrollBy = vi.fn();
    vi.stubGlobal("scrollBy", scrollBy);
    renderVersion("study", "v3");

    await expand("Your name");
    const short = screen.getByText("Short").closest("li") as HTMLElement;
    const full = screen.getByText("Full").closest("li") as HTMLElement;
    // No place to choose, and nowhere past either end
    expect(within(short).queryByRole("button", { name: "Move" })).not.toBeInTheDocument();
    expect(within(short).getByRole("button", { name: "Move up" })).toBeDisabled();
    expect(within(full).getByRole("button", { name: "Move down" })).toBeDisabled();

    const up = within(full).getByRole("button", { name: "Move up" });
    // Where the tree shows it next, as the test environment cannot lay it out
    vi.spyOn(up, "getBoundingClientRect")
      .mockReturnValueOnce({ top: 300 } as DOMRect)
      .mockReturnValue({ top: 260 } as DOMRect);
    up.focus();
    fireEvent.click(up);
    expect(within(full).getByRole("progressbar")).toBeInTheDocument();
    expect(screen.getByText("Moving “Full” up…")).toBeInTheDocument();

    await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/name/full.move.json"));
    expect(Object.fromEntries(posted[0].params)).toEqual({ parent: "/Schemas/study/v3/intake/name", before: "short" });
    expect(await screen.findByText("“Full” was moved up")).toBeInTheDocument();
    await waitFor(() => expect(scrollBy).toHaveBeenCalledWith(0, -40));
    // What was pressed stays pressed, to be pressed again
    expect(document.activeElement).toBe(up);
  });

  it("moves an option down before the one after next, or last, and says why one did not", async () => {
    const posted = serveSchemas({ answers: {
      "/Schemas/study/v3/intake/name/short.move.json": { redirect: "/Schemas/study/v3/intake/name/short" },
      "/Schemas/study/v3/intake/name/full.move.json": { status: 409, error: "Somebody else changed this" },
    } });
    renderVersion("study", "v3");

    await expand("Your name");
    const short = screen.getByText("Short").closest("li") as HTMLElement;
    const down = within(short).getByRole("button", { name: "Move down" });
    // As the tree reads once the move is made
    const options = CONTENT["study/v3"].intake as Record<string, Record<string, Record<string, unknown>>>;
    const { short: stored } = options.name;
    options.name.short = { ...stored, defaultOrder: 30 };
    try {
      fireEvent.click(down);
      await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake/name/short.move.json"));
      // After the last, there is nothing to go before
      expect(Object.fromEntries(posted[0].params)).toEqual({ parent: "/Schemas/study/v3/intake/name" });
      expect(await screen.findByText("“Short” was moved down")).toBeInTheDocument();
      // Now last, it can only go up again
      await waitFor(() => expect(document.activeElement).toBe(within(short).getByRole("button", { name: "Move up" })));
    } finally {
      options.name.short = stored;
    }

    fireEvent.click(within(screen.getByText("Full").closest("li") as HTMLElement)
      .getByRole("button", { name: "Move down" }));
    expect(await screen.findByText("“Full” could not be moved")).toBeInTheDocument();
    expect(screen.getByText("Somebody else changed this")).toBeInTheDocument();
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

  describe("on a phone", () => {
    beforeEach(() => stubPhone());

    const menuButton = (name: string) => screen.findByRole("button", { name: `Actions for “${name}”` });
    const openActions = async (name: string) => {
      fireEvent.click(await menuButton(name));
      return screen.findByRole("menu");
    };
    const lines = (menu: HTMLElement) => within(menu).getAllByRole("menuitem").map(item => item.textContent);

    it("offers what may be done to a part in one menu, and says its kind in its heading", async () => {
      const posted = serveSchemas();
      renderVersion("study", "v3");

      const age = await card("Your age");
      expect(within(age).queryByRole("button", { name: "Move" })).not.toBeInTheDocument();
      expect(within(age).getByText("Your age").closest("p")).toContainElement(within(age).getByTitle("Question"));
      const menu = await openActions("Your age");
      expect(lines(menu)).toEqual(
        [ "When it applies", "Add section below", "Add question below", "Move", "Remove" ]);
      fireEvent.click(within(menu).getByRole("menuitem", { name: "Add question below" }));
      await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
      const dialog = await screen.findByRole("dialog", { name: /Add question/ });
      fireEvent.change(within(dialog).getByLabelText(/Question/), { target: { value: "Your email" } });
      fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

      await waitFor(() => expect(posted[0]?.url).toBe("/Schemas/study/v3/intake.create.json"));
      expect(posted[0].params.get("type")).toBe("sch:Question");
      expect(posted[0].params.has("before")).toBe(false);
    });

    it("edits and sets when a part applies from its menu", async () => {
      serveSchemas();
      renderVersion("study", "v3");

      fireEvent.click(within(await openActions("Your age")).getByRole("menuitem", { name: "When it applies" }));
      fireEvent.click(within(await screen.findByRole("dialog", { name: "When this question applies" }))
        .getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      fireEvent.click(within(await openActions("Your name")).getByRole("menuitem", { name: "Edit" }));

      expect(await screen.findByRole("dialog", { name: "Edit question" })).toBeInTheDocument();
    });

    it("moves a part chosen from its menu, showing only moving meanwhile", async () => {
      serveSchemas();
      renderVersion("study", "v3");

      fireEvent.click(within(await openActions("Your age")).getByRole("menuitem", { name: "Move" }));

      expect(await screen.findByRole("status")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: /^Actions for “/ })).not.toBeInTheDocument();
      fireEvent.click(within(await card("Your age")).getByRole("button", { name: "Move", pressed: true }));
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      expect(await menuButton("Your age")).toBeInTheDocument();
    });

    it("says only where a part is moving to, as it is the one shown moving", async () => {
      serveSchemas({ answers: {
        "/Schemas/study/v3/intake/age.move.json": { redirect: "/Schemas/study/v3/intake/age" },
      } });
      renderVersion("study", "v3");

      fireEvent.click(within(await openActions("Your age")).getByRole("menuitem", { name: "Move" }));
      const spot = await screen.findByRole("button", { name: "Move before Your name" });
      fireEvent.click(spot);

      expect(spot).toHaveTextContent(/^Moving before\s*Your name…$/);
      await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    });

    it("keeps an option's steps on its row, and the rest in its menu", async () => {
      serveSchemas();
      renderVersion("study", "v3");

      await expand("Your name");
      const short = await card("Short");
      expect(within(short).getByRole("button", { name: "Move down" })).toBeEnabled();
      expect(within(short).queryByRole("button", { name: "Remove" })).not.toBeInTheDocument();

      expect(lines(await openActions("Short"))).toEqual([ "Remove" ]);
    });

    it("acts on the version from its menu", async () => {
      const posted = serveSchemas();
      renderVersion("study", "v3");

      fireEvent.click(await screen.findByRole("button", { name: "Actions for version 3.0" }));
      const versionMenu = await screen.findByRole("menu");
      fireEvent.click(await within(versionMenu).findByRole("menuitem", { name: "Activate" }));
      fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Activate" }));

      await waitFor(() => expect(posted.map(event => event.url)).toEqual([ "/Schemas/study/v3.activate.json" ]));
    });
  });
});
