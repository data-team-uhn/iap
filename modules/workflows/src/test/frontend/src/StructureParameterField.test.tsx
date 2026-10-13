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
import { fireEvent, screen, within } from "@testing-library/react";

import ParameterField from "@iap/workflows/ParameterField";
import type { ParameterValue, StructureRecord } from "@iap/workflows/parameterValues";
import type { Parameter } from "@iap/workflows/workflowTypes";

import { renderField } from "./parameterField.fixture";
import { parameter } from "./vocabulary.fixture";

const CHOICES = parameter("choices", {
  label: "Choice",
  dataType: "structure",
  maxValues: 3,
  keyParameter: "value",
  parameters: [ parameter("value", { label: "Value" }), parameter("label", { label: "Label" }) ],
});

const CONDITION = parameter("appliesWhen", {
  label: "Applies when",
  dataType: "structure",
  parameters: [ parameter("property", { label: "Property" }) ],
});

const A = { value: [ "a" ], label: [ "A" ] };
const B = { value: [ "b" ] };

// Through the dispatcher, as the properties panel renders it, so that the fields inside recurse the same way
const renderStructure = (given: Parameter, values: StructureRecord[], readOnly = false) =>
  renderField<ParameterValue>(ParameterField, given, values, readOnly);

describe("StructureParameterField", () => {
  it("edits a single structure as one group of its fields", async () => {
    const { onChange, onCommit, user } = renderStructure(CONDITION, [ { property: [ "dataType" ] } ]);

    const group = await screen.findByRole("group", { name: "Applies when" });
    const property = within(group).getByLabelText("Property");
    expect(property).toHaveValue("dataType");
    await user.type(property, "s");
    expect(onChange).toHaveBeenLastCalledWith([ { property: [ "dataTypes" ] } ]);
    await user.tab();
    expect(onCommit).toHaveBeenCalledWith([ { property: [ "dataType" ] } ]);
  });

  it("starts an unset single structure from an empty record", async () => {
    const { onChange, user } = renderStructure(CONDITION, []);

    await user.type(await screen.findByLabelText("Property"), "x");

    expect(onChange).toHaveBeenLastCalledWith([ { property: [ "x" ] } ]);
  });

  it("lists the records of a repeated structure by their key", async () => {
    const { onChange, onCommit, user } = renderStructure(CHOICES, [ A, B, {} ]);

    expect(await screen.findByRole("group", { name: "a" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "New Choice" })).toBeInTheDocument();
    await user.type(within(screen.getByRole("group", { name: "b" })).getByLabelText("Label"), "B");

    expect(onChange).toHaveBeenLastCalledWith([ A, { value: [ "b" ], label: [ "B" ] }, {} ]);
    await user.tab();
    expect(onCommit).toHaveBeenCalledTimes(1);
  });

  it("moves and removes a record in one finished edit each", async () => {
    const { onChange, onCommit, user } = renderStructure(CHOICES, [ A, B ]);

    expect(await screen.findByRole("button", { name: "Move a up" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Move b down" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Move a down" }));
    expect(onCommit).toHaveBeenLastCalledWith([ B, A ]);
    await user.click(screen.getByRole("button", { name: "Move b up" }));
    expect(onCommit).toHaveBeenLastCalledWith([ B, A ]);
    await user.click(screen.getByRole("button", { name: "Remove a" }));
    expect(onCommit).toHaveBeenLastCalledWith([ B ]);
    expect(onChange).toHaveBeenCalledTimes(3);
  });

  it("titles every record after the structure when it names no key", async () => {
    renderStructure({ ...CHOICES, keyParameter: undefined }, [ A ]);

    expect(await screen.findByRole("group", { name: "New Choice" })).toBeInTheDocument();
  });

  it("adds a record as a draft, up to the most the structure accepts", async () => {
    const { onChange, onCommit, user } = renderStructure(CHOICES, [ A ]);

    await user.click(await screen.findByRole("button", { name: "Add Choice" }));

    expect(onChange).toHaveBeenCalledWith([ A, {} ]);
    expect(onCommit).not.toHaveBeenCalled();
  });

  it("offers no more records once the structure is full", async () => {
    renderStructure({ ...CHOICES, description: "What may be picked" }, [ A, B, {} ]);

    expect(await screen.findByRole("button", { name: "Add Choice" })).toBeDisabled();
    expect(screen.getByText("What may be picked")).toBeInTheDocument();
  });

  it("shows the records as text, with nothing to change them by, when read only", async () => {
    renderStructure(CHOICES, [ A ], true);

    expect(await screen.findByText("Value: a")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("says a read-only repeated structure holds nothing when it is empty", async () => {
    renderStructure(CHOICES, [], true);

    expect(await screen.findByText("Choice: —")).toBeInTheDocument();
  });

  it("flags a record whose key another record already has, as the server compares them", async () => {
    // The server trims each key and drops every record after the first of a key
    renderStructure(CHOICES, [ A, { value: [ " a " ] }, { value: [ " " ] }, {} ]);

    const [ , later ] = await screen.findAllByRole("group", { name: "a" });
    expect(within(later).getByText('Another Choice has the key "a", so this one will be ignored')).toBeInTheDocument();
    expect(screen.getAllByText(/so this one will be ignored/)).toHaveLength(1);
  });

  it("flags no repeated key when read only, as no field shown as text does", async () => {
    renderStructure(CHOICES, [ A, A ], true);

    expect(await screen.findAllByText("Value: a")).toHaveLength(2);
    expect(screen.queryByText(/so this one will be ignored/)).not.toBeInTheDocument();
  });

  it("asks for a required structure once it has been left holding no record", async () => {
    const { user } = renderStructure({ ...CHOICES, minValues: 1 }, []);
    expect(screen.queryByText("A value is required")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Add Choice" }));
    await user.tab();

    // The record added holds nothing, so it is no value yet
    expect(screen.getByText("A value is required")).toBeInTheDocument();
  });

  it.each([
    [ "asks for a value when its one record holds only blanks", [ " " ], true ],
    [ "is satisfied by a record holding a value", [ "x" ], false ],
  ])("a required structure %s", async (_case, held, flagged) => {
    const { user } = renderStructure({ ...CONDITION, minValues: 1 }, [ { property: held } ]);

    await user.click(await screen.findByLabelText("Property"));
    await user.tab();

    expect(screen.queryByText("A value is required") !== null).toBe(flagged);
  });

  it("asks for nothing when read only, as no field shown as text does", async () => {
    renderStructure({ ...CONDITION, minValues: 1 }, [], true);

    fireEvent.blur(await screen.findByRole("group", { name: "Applies when" }));

    expect(screen.queryByText("A value is required")).not.toBeInTheDocument();
  });

  it("asks for a required field inside a record the way it would anywhere else", async () => {
    const required = { ...CONDITION, parameters: [ parameter("property", { label: "Property", minValues: 1 }) ] };
    const { user } = renderStructure(required, [ {} ]);

    await user.click(screen.getByLabelText(/^Property/));
    await user.tab();

    expect(screen.getByText("A value is required")).toBeInTheDocument();
  });
});
