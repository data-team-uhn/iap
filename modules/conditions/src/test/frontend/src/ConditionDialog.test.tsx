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

import { type ComponentProps } from "react";

import { MenuItem, TextField } from "@mui/material";
import { ThemeProvider } from "@mui/material/styles";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

import type { OperandEditorProps } from "@iap/conditions/ConditionBuilder";
import ConditionDialog from "@iap/conditions/ConditionDialog";
import { type OperandSource, PROPERTY_SOURCE, tagsSource } from "@iap/conditions/conditionModel";
import { appTheme } from "@iap/frontend-commons/appTheme";

const FIELDS: Record<string, ReturnType<OperandSource["shape"]>> = {
  age: { type: "long", multiple: false },
  colours: {
    type: "text", multiple: true, choices: [ { value: "red", label: "Red" }, { value: "blue", label: "Blue" } ],
  },
  due: { type: "date", multiple: false },
  consent: { type: "boolean", multiple: false },
  notes: { type: "text", multiple: true },
  free: { multiple: false },
};
const FIELD_SOURCE: OperandSource = {
  name: "field",
  label: "A field",
  valueLabel: "Field",
  shape: value => FIELDS[value.at(0) ?? ""] ?? {},
  describe: value => `the field ${value.at(0) ?? "nowhere"}`,
};
const SOURCES = [ FIELD_SOURCE, tagsSource([ { value: "urgent", label: "Urgent" } ]), PROPERTY_SOURCE ];

// A field picked from a list, as a module offering a source may ask for what its operands name
function FieldPicker({ label, value, disabled, onChange }: OperandEditorProps) {
  return (
    <TextField select label={label} value={value.at(0) ?? ""} disabled={disabled}
      onChange={event => onChange([ event.target.value ])}>
      { Object.keys(FIELDS).map(name => <MenuItem key={name} value={name}>{name}</MenuItem>) }
    </TextField>
  );
}

const renderDialog = (props: Partial<ComponentProps<typeof ConditionDialog>> = {}) => {
  const onSave = vi.fn().mockResolvedValue(undefined);
  const onClose = vi.fn();
  render(
    <ThemeProvider theme={appTheme} defaultMode="light">
      <ConditionDialog title="When it applies" sources={SOURCES}
        editors={{ field: editor => <FieldPicker {...editor} /> }} onSave={onSave} onClose={onClose} {...props} />
    </ThemeProvider>
  );
  return { onSave, onClose, dialog: screen.getByRole("dialog") };
};

const pick = async (scope: HTMLElement, label: string, option: string) => {
  fireEvent.mouseDown(within(scope).getByRole("combobox", { name: label }));
  fireEvent.click(await screen.findByRole("option", { name: option }));
};

const conditions = (dialog: HTMLElement) => within(dialog).getAllByRole("group", { name: "Condition" });

const single = (comparator: string, a: Record<string, unknown>, b?: Record<string, unknown>) => ({
  "jcr:primaryType": "cond:SingleCondition", "sling:resourceSuperType": "cond/Condition", comparator,
  "operandA": { "jcr:primaryType": "cond:ConditionOperand", ...a },
  ...b ? { operandB: { "jcr:primaryType": "cond:ConditionOperand", ...b } } : {},
});

const field = (name: string) => ({ source: "field", value: [ name ] });

const ANY_OF_TWO = {
  "jcr:primaryType": "cond:ConditionGroup", "sling:resourceSuperType": "cond/Condition", "requireAll": false,
  "first": single("is empty", field("age")),
  "second": single("includes", { source: "tags" }, { value: [ "urgent" ] }),
};

describe("ConditionDialog", () => {
  it("starts with no condition, which always applies", () => {
    const { dialog } = renderDialog();

    expect(within(dialog).getByText("No condition. It always applies.")).toBeInTheDocument();
    expect(within(dialog).getByText("Always applies.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
    expect(within(dialog).queryByRole("button", { name: "Remove the condition" })).not.toBeInTheDocument();
  });

  it("builds a condition, checking its values against what they are compared with", async () => {
    const { dialog, onSave, onClose } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    expect(within(condition).getByRole("combobox", { name: "Compare" })).toHaveTextContent("A field");
    await pick(condition, "Field", "age");
    await pick(condition, "Comparison", "is at least");
    const value = within(condition).getByRole("textbox", { name: "Value" });
    expect(value).toHaveAttribute("inputmode", "numeric");
    fireEvent.change(value, { target: { value: "adult" } });
    expect(within(condition).getByText("Enter a whole number.")).toBeInTheDocument();
    expect(within(dialog).getByText("Complete each condition to save.")).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.change(value, { target: { value: "18" } });
    expect(within(dialog).getByText("Only when the field age is at least 18")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith({
      "jcr:primaryType": "cond:ConditionGroup", "requireAll": true,
      "condition1": {
        "jcr:primaryType": "cond:SingleCondition", "comparator": "greater or equal",
        "operandA": { "jcr:primaryType": "cond:ConditionOperand", "source": "field", "value": [ "age" ] },
        "operandB": { "jcr:primaryType": "cond:ConditionOperand", "source": "literal", "value": [ 18 ] },
      },
    }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it("edits a stored condition: which must hold, and what it holds", async () => {
    const { dialog, onSave } = renderDialog({ condition: ANY_OF_TWO });

    expect(within(dialog).getByText("Only when the field age is empty or its tag list includes “Urgent”"))
      .toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Any" })).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(within(dialog).getByRole("button", { name: "All" }));
    // Choosing what is already chosen changes nothing
    fireEvent.click(within(dialog).getByRole("button", { name: "All" }));
    expect(within(dialog).getByText(/is empty and its tag list/)).toBeInTheDocument();

    await pick(conditions(dialog)[1], "Comparison", "includes any of");
    expect(within(conditions(dialog)[1]).getByRole("combobox", { name: "Comparison" }))
      .toHaveTextContent("includes any of");
    fireEvent.click(within(conditions(dialog)[0]).getByRole("button", { name: "Remove this condition" }));
    expect(within(dialog).queryByRole("button", { name: "All" })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      requireAll: true,
      condition1: expect.objectContaining({ comparator: "includes any" }) as unknown,
    })));
  });

  it("shows a stored value that is no longer among the choices for what it is", () => {
    const { dialog } = renderDialog({ condition: single("equals", field("consent"), { value: [ "maybe" ] }) });

    expect(within(dialog).getByRole("combobox", { name: "Value" })).toHaveTextContent("maybe");
    expect(within(dialog).getByText("Choose yes or no.")).toBeInTheDocument();
  });

  it("removes the condition", async () => {
    const { dialog, onSave } = renderDialog({ condition: ANY_OF_TWO });

    fireEvent.click(within(dialog).getByRole("button", { name: "Remove the condition" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(null));
  });

  it("picks values among the choices, and counts several values", async () => {
    const { dialog, onSave } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    await pick(condition, "Field", "colours");
    await pick(condition, "Comparison", "includes any of");
    await pick(condition, "Values", "Red");
    await pick(condition, "Values", "Blue");
    expect(within(dialog).getByText("Only when the field colours includes any of “Red”, “Blue”")).toBeInTheDocument();
    // Down to one value, for a comparison taking one
    await pick(condition, "Comparison", "is not");
    expect(within(dialog).getByText("Only when the field colours is not “Red”, “Blue”")).toBeInTheDocument();

    await pick(condition, "Using", "How many there are");
    await pick(condition, "Comparison", "is more than");
    fireEvent.change(within(condition).getByRole("textbox", { name: "Value" }), { target: { value: "1" } });
    expect(within(dialog).getByText("Only when the number of values in the field colours is more than 1"))
      .toBeInTheDocument();
    await pick(condition, "Using", "The values");
    expect(within(condition).getByRole("combobox", { name: "Comparison" })).toHaveTextContent("is");
    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
    expect(onSave).not.toHaveBeenCalled();
  });

  it("types in several values where there are no choices", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    await pick(condition, "Field", "notes");
    await pick(condition, "Comparison", "includes all of");
    const values = within(condition).getByRole("combobox", { name: "Values" });
    fireEvent.change(values, { target: { value: "late" } });
    fireEvent.keyDown(values, { key: "Enter" });

    expect(within(dialog).getByText("Only when the field notes includes “late”")).toBeInTheDocument();
  });

  it("asks for a single value as its type calls for, or for none", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    await pick(condition, "Field", "consent");
    await pick(condition, "Value", "No");
    expect(within(dialog).getByText("Only when the field consent is “No”")).toBeInTheDocument();

    await pick(condition, "Field", "due");
    const date = within(condition).getByLabelText("Value");
    expect(date).toHaveAttribute("type", "date");
    fireEvent.change(date, { target: { value: "2026-09-29" } });
    fireEvent.change(date, { target: { value: "" } });
    expect(within(dialog).getByText("Only when the field due is nothing")).toBeInTheDocument();

    await pick(condition, "Field", "free");
    expect(within(condition).getByRole("textbox", { name: "Value" })).toHaveAttribute("inputmode", "text");

    await pick(condition, "Comparison", "is empty");
    expect(within(condition).queryByRole("combobox", { name: "Compared with" })).not.toBeInTheDocument();
  });

  it("compares with what another source reads", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    await pick(condition, "Compare", "One of its properties");
    fireEvent.change(within(condition).getByRole("textbox", { name: "Property" }), { target: { value: "status" } });
    fireEvent.change(within(condition).getByRole("textbox", { name: "Property" }), { target: { value: "" } });
    fireEvent.change(within(condition).getByRole("textbox", { name: "Property" }), { target: { value: "status" } });
    await pick(condition, "Compared with", "Its tags");
    expect(within(dialog).getByText("Only when its status is its tag list")).toBeInTheDocument();
    await pick(condition, "Compared with", "A field");
    await pick(condition, "Field", "age");
    expect(within(dialog).getByText("Only when its status is the field age")).toBeInTheDocument();
    // Once what is compared changes, what it is compared with stays when it is not a value
    await pick(condition, "Compare", "Its tags");
    expect(within(dialog).getByText("Only when its tag list is the field age")).toBeInTheDocument();
  });

  it("keeps groups inside groups", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a group" }));
    const nested = within(dialog).getByRole("group", { name: "Group of conditions" });
    fireEvent.click(within(nested).getByRole("button", { name: "Add a condition" }));
    expect(within(nested).getAllByRole("group", { name: "Condition" })).toHaveLength(2);
    fireEvent.click(within(nested).getByRole("button", { name: "Any" }));
    fireEvent.click(within(nested).getAllByRole("button", { name: "Remove this condition" })[0]);
    fireEvent.click(within(nested).getByRole("button", { name: "Add a group" }));
    expect(within(nested).getAllByRole("group", { name: "Group of conditions" })).toHaveLength(1);

    fireEvent.click(within(nested).getAllByRole("button", { name: "Remove this group" }).at(-1)!);
    expect(within(dialog).queryByRole("group", { name: "Group of conditions" })).not.toBeInTheDocument();
  });

  it("keeps what it does not know, and saves only once it is gone", async () => {
    const { dialog, onSave } = renderDialog({
      editors: {},
      condition: {
        "jcr:primaryType": "cond:ConditionGroup", "sling:resourceSuperType": "cond/Condition", "requireAll": true,
        "odd": { "jcr:primaryType": "cond:Strange", "sling:resourceSuperType": "cond/Condition" },
        "known": single("sounds like", { source: "elsewhere", value: [ "x" ], aggregate: "median" },
          { value: [ "y" ] }),
      },
    });

    expect(within(dialog).getByText(/of a kind this editor does not know/)).toBeInTheDocument();
    const known = conditions(dialog)[0];
    expect(within(known).getByRole("combobox", { name: "Compare" })).toHaveTextContent("elsewhere");
    expect(within(known).getByRole("combobox", { name: "Using" })).toHaveTextContent("median");
    expect(within(known).getByRole("combobox", { name: "Comparison" })).toHaveTextContent("sounds like");
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.click(within(dialog).getAllByRole("button", { name: "Remove this condition" })[0]);
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      condition1: expect.objectContaining({ comparator: "sounds like" }) as unknown,
    })));
  });

  it("names what a source reads as text when it asks for nothing else, and reports a refused save", async () => {
    const { dialog, onClose } = renderDialog({ editors: {}, onSave: vi.fn().mockRejectedValue(new Error("Refused")) });

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    fireEvent.change(within(dialog).getByRole("textbox", { name: "Field" }), { target: { value: "age" } });
    await pick(dialog, "Comparison", "is not empty");
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(await within(dialog).findByText("Refused")).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("offers nothing to compare when no source is given", () => {
    const { dialog } = renderDialog({ sources: [] });

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));

    expect(within(dialog).getByRole("combobox", { name: "Compare" })).toHaveTextContent("literal");
  });
});
