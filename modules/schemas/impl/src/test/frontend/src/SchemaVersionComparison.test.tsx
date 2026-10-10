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
import SchemaPage from "@iap/schemas/SchemaPage";
import { comparisonPageUrl } from "@iap/schemas/useSchemaList";
import { clearTagDefinitionsCache } from "@iap/tags/tagDefinitions";

import { serveSchemas } from "./schemaServer.fixture";

type Node = Record<string, unknown>;

const named = (children: [ string, Node ][]) => Object.fromEntries(children);
const form = (label: string, ...items: [ string, Node ][]) => ({
  "jcr:primaryType": "sch:FormRequirement", "sling:resourceType": "sch/FormRequirement",
  "sling:resourceSuperType": "sch/Requirement", label, ...named(items),
});
const question = (text: string, properties: Node = {}, ...options: [ string, Node ][]) => ({
  "jcr:primaryType": "sch:Question", "sling:resourceType": "sch/Question", "sling:resourceSuperType": "sch/FormItem",
  text, dataType: "text", ...properties, ...named(options),
});
const option = (label: string, value = label.toLowerCase()) => ({
  "jcr:primaryType": "sch:AnswerOption", "sling:resourceType": "sch/AnswerOption", value, label,
});
const version = (label: string, ...parts: [ string, Node ][]) => ({
  "jcr:primaryType": "sch:SchemaVersion", "sling:resourceType": "sch/SchemaVersion", version: label, tags: [ "draft" ],
  ...named(parts),
});
const FOR_DRAFTS = {
  "jcr:primaryType": "cond:SingleCondition", "sling:resourceSuperType": "cond/Condition", comparator: "includes",
  operandA: { "jcr:primaryType": "cond:ConditionOperand", source: "tags", value: [] },
  operandB: { "jcr:primaryType": "cond:ConditionOperand", source: "literal", value: [ "draft" ] },
};

const requirement = (type: string, label: string) => ({
  "jcr:primaryType": `sch:${type}`, "sling:resourceType": `sch/${type}`, "sling:resourceSuperType": "sch/Requirement",
  label,
});
const SITE: [ string, Node ][] = [ [ "north", option("North") ], [ "south", option("South") ] ];
const UNCHANGED: [ string, Node ][] = [
  [ "consent", requirement("DocumentRequirement", "Consent form") ],
  [ "review", requirement("ApprovalRequirement", "Review") ],
];

// A condition that always holds, which no sentence says, stored with something else to tell it apart
const oddly = (note: string) => ({
  "jcr:primaryType": "cond:ConditionGroup", "sling:resourceSuperType": "cond/Condition", requireAll: true, note,
});

const BASE: [ string, Node ][] = [
  [ "intake", form("Intake",
    [ "name", question("Your name") ],
    [ "nick", question("Your nickname") ],
    [ "age", question("Your age", { dataType: "long" }) ],
    [ "email", question("Your email") ],
    [ "arm", question("Which arm?", {},
      [ "drug", option("Drug") ], [ "placebo", option("Placebo") ], [ "none", option("None") ]) ],
    [ "notes", question("Notes") ],
    [ "site", question("Which site?", {}, ...SITE, [ "east", option("East") ]) ]) ],
  [ "legacy", form("Legacy", [ "fax", question("Your fax") ]) ],
  ...UNCHANGED,
];

const HOMEPAGE = {
  "jcr:primaryType": "sch:SchemasHomepage",
  "trial": {
    "jcr:primaryType": "sch:Schema", "title": "Trial",
    "v1": { ...version("1.0", ...BASE), description: "First", workflow: "/Workflows/review/v1" },
    "v2": version("2.0",
      [ "intake", form("Intake",
        [ "name", question("Your name") ],
        [ "nick", question("Your nickname") ],
        [ "age", question("Your age in years", { minAnswers: 1, "cond:condition": FOR_DRAFTS }) ],
        [ "arm", question("Which arm?", {},
          [ "placebo", option("Placebo arm", "placebo") ], [ "drug", option("Drug") ],
          [ "device", option("Device") ]) ],
        [ "notes", question("Notes") ],
        [ "site", question("Which site?", { description: "Where it is run" }, ...SITE,
          [ "east", option("Eastern") ]) ],
        [ "fax", question("Your fax") ]) ],
      [ "followUp", form("Follow-up", [ "visit", question("Next visit") ]) ],
      ...UNCHANGED),
    "v3": version("3.0", ...BASE, [ "extra", question("Anything else?") ]),
    "v4": { ...version("4.0", ...BASE), description: "With a faster review", workflow: "/Workflows/fastTrack/v2" },
    "v6": version("6.0", ...BASE, [ "loose", question("Loose end") ]),
    "v7": version("7.0", [ "intake", form("Intake", [ "loose", question("Loose end") ]) ]),
    "v8": version("8.0",
      [ "intake", form("Intake", [ "name", question("Your name", { "cond:condition": oddly("a") }) ]) ]),
    "v9": version("9.0",
      [ "intake", form("Intake", [ "name", question("Your name", { "cond:condition": oddly("b") }) ]) ]),
    "v5": version("5.0", [ "intake", form("Intake", [ "name", {
      "jcr:primaryType": "sch:Section", "sling:resourceType": "sch/Section", "sling:resourceSuperType": "sch/FormItem",
      title: "Names",
    } ]) ]),
  },
};

const FIELDS = {
  comparisonVersionFieldsFrom: [ "/SystemWorkflows/updateDraftSchemaVersion" ],
  comparisonPartFieldsFrom: [ "/SystemWorkflows/updateDraftSchemaPart" ],
  comparisonOptionFieldsFrom: [ "/SystemWorkflows/updateDraftAnswerOption" ],
};

const renderComparison = (base: string, compared: string, definitions: Record<string, string[]> = FIELDS) => render(
  <ThemeProvider theme={appTheme}>
    <MemoryRouter initialEntries={[ comparisonPageUrl("trial", base, compared) ]}>
      <Routes>
        <Route path="/admin/schemas/*"
          element={<SchemaPage extension={definitions} />} />
      </Routes>
    </MemoryRouter>
  </ThemeProvider>,
);

// The compared part with the given heading
const part = async (heading: string) => (await screen.findByText(heading)).closest("li") as HTMLElement;

afterEach(() => {
  vi.unstubAllGlobals();
  clearTagDefinitionsCache();
});

describe("SchemaVersionComparison", () => {
  it("shows what a version added, removed, changed and moved, compared with another", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v2");

    expect(await screen.findByText("2 parts added, 2 removed, 3 changed and 1 moved.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /Version 2\.0 compared with 1\.0/ })).toBeInTheDocument();

    const age = await part("Your age in years");
    expect(within(age).getByText("Question", { selector: "span" })).toBeInTheDocument();
    expect(within(age).getByText("in years").closest("ins")).not.toBeNull();
    expect(within(age).getByText("Answer type").parentElement).toHaveTextContent("Answer typeWhole number→Text");
    expect(within(age).getByText("Minimum answers").parentElement).toHaveTextContent(/^Minimum answers1$/);
    expect(within(age).getByText("When it applies")).toBeInTheDocument();

    const arm = await part("Which arm?");
    expect(within(arm).getByText("Placebo arm").closest("li")).toHaveTextContent("Changed");
    // Of two options that swapped places, the one the newer version lists later moved
    expect(within(arm).getByText("Drug").closest("li")).toHaveTextContent("Order changed");
    expect(within(arm).getByText("Device").closest("li")).toHaveTextContent("Added");
    expect(within(arm).getByText("None").closest("li")).toHaveTextContent("Removed");
    expect(within(arm).queryByText(/other options? (is|are) the same/)).not.toBeInTheDocument();

    const site = await part("Which site?");
    expect(within(site).getByText("Where it is run").closest("ins")).not.toBeNull();
    expect(within(site).getByText("2 other options are the same.")).toBeInTheDocument();

    expect(await part("Your fax")).toHaveTextContent("Moved from “Legacy”");
    expect(await part("Your email")).toHaveTextContent("Removed");
    expect(await part("Legacy")).toHaveTextContent("Removed");
    expect(await part("Follow-up")).toHaveTextContent("Added");
    expect(await part("Next visit")).toHaveTextContent("Added");
  });

  it("counts what did not change, and shows it on demand", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v2");

    expect(await screen.findByRole("button", { name: "Show 1 unchanged question" })).toBeInTheDocument();
    // Each opening the way it would: before what follows at the start, after what precedes at the end, else both
    expect(screen.getAllByRole("button", { name: /^Show \d+ unchanged/ })
      .map(button => button.querySelector("svg")?.getAttribute("data-testid")))
      .toEqual([ "KeyboardArrowUpIcon", "UnfoldMoreIcon", "KeyboardArrowDownIcon" ]);
    expect(screen.getByRole("button", { name: "Show 2 unchanged parts" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show 2 unchanged questions" }));
    expect(screen.getByText("Your nickname")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("switch", { name: "Show unchanged parts" }));
    expect(screen.queryByRole("button", { name: /unchanged/ })).not.toBeInTheDocument();
    expect(screen.getByText("Your name")).toBeInTheDocument();
  });

  it("swaps the versions, and compares others when picked", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v2");

    fireEvent.click(await screen.findByRole("button", { name: "Swap" }));
    expect(await screen.findByRole("heading", { name: /Version 1\.0 compared with 2\.0/ })).toBeInTheDocument();

    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Version" }));
    fireEvent.click(await screen.findByRole("option", { name: "Version 3.0" }));
    expect(await screen.findByRole("heading", { name: /Version 3\.0 compared with 2\.0/ })).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "Compared with" }));
    fireEvent.click(await screen.findByRole("option", { name: "Version 1.0" }));
    expect(await screen.findByText("1 part added.")).toBeInTheDocument();
  });

  it("says what a version says of itself that changed, a workflow by its title, but not its label", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v4");

    expect(await screen.findByText("Only the version's description and workflow changed.")).toBeInTheDocument();
    const version = (await screen.findByText("This version")).closest("div") as HTMLElement;
    const card = version.parentElement!;
    expect(within(card).getByText("With a faster review").closest("ins")).not.toBeNull();
    expect(await screen.findByText("Fast track")).toBeInTheDocument();
    expect(screen.queryByText("Label")).not.toBeInTheDocument();
  });

  it("names a workflow by where it points when what it points at cannot be found", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    const served = vi.mocked(fetch).getMockImplementation();
    vi.mocked(fetch).mockImplementation((url, init) => (String(url).startsWith("/search.json")
      ? Promise.resolve(new Response("{}", { status: 500 }))
      : served!(url, init)));
    renderComparison("v1", "v4");

    expect(await screen.findByText("/Workflows/fastTrack/v2")).toBeInTheDocument();
  });

  it("names only what it can find a path for", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    const served = vi.mocked(fetch).getMockImplementation();
    vi.mocked(fetch).mockImplementation((url, init) => (String(url).startsWith("/search.json")
      ? Promise.resolve(new Response(JSON.stringify({ rows: [
        { title: "Nowhere" }, { "@path": "/Workflows/fastTrack/v2", "title": "Fast track" },
      ] })))
      : served!(url, init)));
    renderComparison("v1", "v4");

    expect(await screen.findByText("Fast track")).toBeInTheDocument();
  });

  it("names a workflow stored by its identifier", async () => {
    serveSchemas({ homepage: { ...HOMEPAGE,
      trial: { ...HOMEPAGE.trial, v4: { ...HOMEPAGE.trial.v4, workflow: "uuid-fast" } } } });
    const served = vi.mocked(fetch).getMockImplementation();
    vi.mocked(fetch).mockImplementation((url, init) => (String(url).startsWith("/search.json")
      ? Promise.resolve(new Response(JSON.stringify({ rows: [
        { "@path": "/Workflows/fastTrack/v2", "jcr:uuid": "uuid-fast", "title": "Fast track" },
      ] })))
      : served!(url, init)));
    renderComparison("v1", "v4");

    expect(await screen.findByText("Fast track")).toBeInTheDocument();
  });

  it("says a part moved from the top level of the version", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v6", "v7");

    expect(await part("Loose end")).toHaveTextContent("Moved from the top level");
  });

  it("tells apart a removed part and an added one of another type going by the same name", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    renderComparison("v1", "v5");

    expect(await part("Your name")).toHaveTextContent("Removed");
    expect(await part("Names")).toHaveTextContent("Added");
    expect(errors.mock.calls.flat().join(" ")).not.toContain("same key");
    errors.mockRestore();
  });

  it("says when a condition changed in a way its words cannot show", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v8", "v9");

    expect(await part("Your name")).toHaveTextContent("The condition changed in a way its words cannot show.");
  });

  it("reads what describes the fields once, whatever pairs are compared", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v2");
    await screen.findByText("2 parts added, 2 removed, 3 changed and 1 moved.");
    const definitionReads = () => vi.mocked(fetch).mock.calls
      .filter(([ url ]) => String(url).startsWith("/SystemWorkflows/")).length;
    const read = definitionReads();

    fireEvent.click(screen.getByRole("button", { name: "Swap" }));
    expect(await screen.findByRole("heading", { name: /Version 1\.0 compared with 2\.0/ })).toBeInTheDocument();
    await screen.findByText(/parts? (added|removed)/);

    expect(definitionReads()).toBe(read);
  });

  it("compares versions whatever their names hold", async () => {
    const odd = "rc+1&b,c";
    serveSchemas({ homepage: { ...HOMEPAGE, trial: { ...HOMEPAGE.trial, [odd]: version("1.1", ...BASE) } } });
    renderComparison("v1", odd);

    expect(await screen.findByRole("heading", { name: /Version 1\.1 compared with 1\.0/ })).toBeInTheDocument();
    expect(await screen.findByText("Only the version's description and workflow changed.")).toBeInTheDocument();
  });

  it("does not offer to compare a version with itself", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v2");

    fireEvent.mouseDown(await screen.findByRole("combobox", { name: "Compared with" }));
    const listbox = await screen.findByRole("listbox");
    expect(within(listbox).getByRole("option", { name: "Version 2.0" })).toHaveAttribute("aria-disabled", "true");
    expect(within(listbox).getByRole("option", { name: "Version 3.0" })).not.toHaveAttribute("aria-disabled");
  });

  it("says when two versions ask for the same", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v1");

    expect(await screen.findByText("The two versions ask for the same.")).toBeInTheDocument();
  });

  it.each([ [ "v1", "v99" ], [ "v99", "v1" ] ])("says when the schema has no version %s or %s", async (base, compared) => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison(base, compared);

    expect(await screen.findByText("This schema has no version v99.")).toBeInTheDocument();
  });

  it("says why the versions could not be compared, and tries again when asked", async () => {
    serveSchemas({ homepage: HOMEPAGE, failContent: 500 });
    renderComparison("v1", "v2");

    expect(await screen.findByText("The versions could not be compared")).toBeInTheDocument();
    serveSchemas({ homepage: HOMEPAGE });
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("2 parts added, 2 removed, 3 changed and 1 moved.")).toBeInTheDocument();
  });

  it("says when what describes the fields compared cannot be read", async () => {
    serveSchemas({ homepage: HOMEPAGE });
    renderComparison("v1", "v2", { ...FIELDS, comparisonPartFieldsFrom: [ "/SystemWorkflows/missing" ] });

    expect(await screen.findByText("The versions could not be compared")).toBeInTheDocument();
    expect(screen.getByText(/\/SystemWorkflows\/missing could not be read/)).toBeInTheDocument();
    const reads = vi.mocked(fetch).mock.calls.length;
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.length).toBeGreaterThan(reads));
    expect(await screen.findByText(/\/SystemWorkflows\/missing could not be read/)).toBeInTheDocument();
  });
});
