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

import { act, fireEvent, render, screen, within } from "@testing-library/react";

import ChoiceSearch from "@iap/frontend-commons/inputs/ChoiceSearch";
import type { ChoiceSource } from "@iap/frontend-commons/inputs/choiceSource";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/valueInput";

const SCHEMA_CHOICES = [ { value: "/Schemas/intake", label: "Intake" }, { value: "/Schemas/exit", label: "Exit" } ];
const SCHEMAS: ChoiceSource = {
  key: "schemas", labelOf: value => value, search: () => Promise.resolve(SCHEMA_CHOICES),
};

const renderSearch = (props: Partial<Parameters<typeof ChoiceSearch>[0]> = {}) => {
  const onChange = vi.fn();
  const onCommit = vi.fn();
  render(<ChoiceSearch label="Schema" source={SCHEMAS} values={[]} onChange={onChange} onCommit={onCommit}
    {...props} />);
  return { onChange, onCommit, input: screen.queryByRole("combobox", { name: /Schema/ }) };
};

const options = async (input: HTMLElement) => {
  fireEvent.mouseDown(input);
  return within(await screen.findByRole("listbox")).getAllByRole("option").map(option => option.textContent);
};

describe("ChoiceSearch", () => {
  it("picks one value by its label, and commits it", async () => {
    const { onChange, onCommit, input } = renderSearch();

    fireEvent.mouseDown(input!);
    fireEvent.click(await screen.findByRole("option", { name: "Exit" }));

    expect(onChange).toHaveBeenCalledWith([ "/Schemas/exit" ]);
    expect(onCommit).toHaveBeenCalledWith([ "/Schemas/exit" ]);
  });

  it("narrows the list as it is typed", async () => {
    const { input } = renderSearch();
    await options(input!);

    act(() => input!.focus());
    fireEvent.change(input!, { target: { value: "int" } });

    expect(screen.getAllByRole("option").map(option => option.textContent)).toEqual([ "Intake" ]);
  });

  it("keeps a stored value the source does not offer, labelled by the source", async () => {
    const { input } = renderSearch({ values: [ "/Schemas/gone" ] });

    expect(input).toHaveValue("/Schemas/gone");
    expect(await options(input!)).toEqual([ "Intake", "Exit", "/Schemas/gone" ]);
  });

  it("clears the value", async () => {
    const { onChange, input } = renderSearch({ values: [ "/Schemas/intake" ] });
    await options(input!);

    fireEvent.change(input!, { target: { value: "" } });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("picks several, as chips", async () => {
    const { onChange, onCommit, input } = renderSearch({
      multiple: true, values: [ "/Schemas/intake", "/Schemas/gone" ], chipError: value => value === "/Schemas/gone",
    });

    expect(await screen.findByText("Intake")).toBeInTheDocument();
    expect(screen.getByText("/Schemas/gone").closest(".MuiChip-root")).toHaveClass("MuiChip-colorError");
    fireEvent.change(input!, { target: { value: "/Elsewhere" } });
    fireEvent.blur(input!);
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.mouseDown(input!);
    fireEvent.click(await screen.findByRole("option", { name: "Exit" }));
    expect(onCommit).toHaveBeenCalledWith([ "/Schemas/intake", "/Schemas/gone", "/Schemas/exit" ]);
  });

  it("shows the labels as text when read only", async () => {
    renderSearch({ values: [ "/Schemas/intake", "/Schemas/gone" ], readOnly: true, multiple: true });

    expect(await screen.findByText("Schema: Intake, /Schemas/gone")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("flags a missing value once the field is left, beside its problems", () => {
    const { input } = renderSearch({ required: true, problems: [ "Not this one" ], description: "Which schema" });

    expect(screen.getByText("Not this one")).toBeInTheDocument();
    fireEvent.blur(input!);

    expect(screen.getByText(`Not this one. ${REQUIRED_PROBLEM}`)).toBeInTheDocument();
  });

  it("says it is loading, and then why the choices could not be read", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    let fail: (error: Error) => void = () => undefined;
    const source: ChoiceSource = { key: "failing", labelOf: value => value,
      search: () => new Promise((_resolve, reject) => { fail = reject; }) };
    const { input } = renderSearch({ source, description: "Which schema" });

    fireEvent.mouseDown(input!);
    expect(await screen.findByText("Loading…")).toBeInTheDocument();
    fail(new Error("gone"));

    expect(await screen.findByText(/gone/)).toBeInTheDocument();
    expect(screen.queryByText("Which schema")).not.toBeInTheDocument();
  });
});
