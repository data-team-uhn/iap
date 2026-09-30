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
function FieldPicker({ label, value, disabled, required, onChange }: OperandEditorProps) {
  return (
    <TextField select label={label} value={value.at(0) ?? ""} disabled={disabled} required={required}
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

// The summary above the conditions, if there is one
const summary = (dialog: HTMLElement) => within(dialog).queryByText(/^Only when/)?.textContent;

describe("ConditionDialog", () => {
  it("starts with no condition, which always applies", () => {
    const { dialog } = renderDialog();

    expect(within(dialog).getByText("No condition. It always applies.")).toBeInTheDocument();
    expect(summary(dialog)).toBeUndefined();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
    expect(within(dialog).queryByRole("button", { name: "Clear all conditions" })).not.toBeInTheDocument();
  });

  it("builds a condition from sensible defaults, summing it up only once it is complete", async () => {
    const { dialog, onSave, onClose } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    expect(within(condition).getByRole("combobox", { name: "Compare" })).toHaveTextContent("A field");
    expect(within(condition).getByRole("combobox", { name: "Comparison" })).toHaveTextContent("is");
    expect(within(condition).getByRole("combobox", { name: "Compared with" })).toHaveTextContent("A specific value");
    // A value waits for what it is compared with
    expect(within(condition).getByRole("textbox", { name: "Value" })).toBeDisabled();
    expect(summary(dialog)).toBeUndefined();

    await pick(condition, "Field", "age");
    await pick(condition, "Comparison", "is at least");
    const value = within(condition).getByRole("textbox", { name: "Value" });
    expect(value).toBeRequired();
    expect(value).toHaveAttribute("inputmode", "numeric");
    fireEvent.change(value, { target: { value: "adult" } });
    expect(within(condition).getByText("Enter a whole number.")).toBeInTheDocument();
    expect(summary(dialog)).toBeUndefined();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();

    fireEvent.change(value, { target: { value: "18" } });
    expect(summary(dialog)).toBe("Only when the field age is at least 18");
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

  it("edits a stored condition: which must be true, how they are joined, and what they hold", async () => {
    const { dialog, onSave } = renderDialog({ condition: ANY_OF_TWO });

    expect(summary(dialog)).toBe("Only when the field age is empty or the tag list includes “Urgent”");
    expect(within(dialog).getByRole("button", { name: "Any" })).toHaveAttribute("aria-pressed", "true");
    expect(within(dialog).getByText("Or")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "All" }));
    // Choosing what is already chosen changes nothing
    fireEvent.click(within(dialog).getByRole("button", { name: "All" }));
    expect(within(dialog).getByText("And")).toBeInTheDocument();
    expect(summary(dialog)).toMatch(/is empty and the tag list/);

    await pick(conditions(dialog)[1], "Comparison", "includes any of");
    fireEvent.click(within(conditions(dialog)[0]).getByRole("button", { name: "Remove this condition" }));
    expect(within(dialog).queryByRole("button", { name: "All" })).not.toBeInTheDocument();
    expect(within(dialog).queryByText("And")).not.toBeInTheDocument();
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

  it("clears all conditions", async () => {
    const { dialog, onSave } = renderDialog({ condition: ANY_OF_TWO });

    fireEvent.click(within(dialog).getByRole("button", { name: "Clear all conditions" }));

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(null));
  });

  it("offers the choices as soon as what is compared has some, and counts several values", async () => {
    const { dialog, onSave } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    expect(within(condition).queryByRole("combobox", { name: "Using" })).not.toBeInTheDocument();
    await pick(condition, "Field", "colours");
    // Already open, on the choices
    fireEvent.click(await screen.findByRole("option", { name: "Red" }));
    await pick(condition, "Comparison", "includes any of");
    await pick(condition, "Values", "Blue");
    expect(summary(dialog)).toBe("Only when the field colours includes any of “Red”, “Blue”");
    // Down to one value, for a comparison taking one
    await pick(condition, "Comparison", "is not");
    expect(summary(dialog)).toBe("Only when the field colours is not “Red”, “Blue”");

    await pick(condition, "Using", "The number of values");
    await pick(condition, "Comparison", "is more than");
    fireEvent.change(within(condition).getByRole("textbox", { name: "Value" }), { target: { value: "1" } });
    expect(summary(dialog)).toBe("Only when the number of values in the field colours is more than 1");
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
    expect(within(condition).getByRole("combobox", { name: "Compared with" })).toHaveTextContent("Specific values");
    const values = within(condition).getByRole("combobox", { name: "Values" });
    fireEvent.change(values, { target: { value: "late" } });
    fireEvent.keyDown(values, { key: "Enter" });

    expect(summary(dialog)).toBe("Only when the field notes includes “late”");
  });

  it("asks for a single value as its type calls for, starting from yes, or for none", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    await pick(condition, "Field", "consent");
    expect(summary(dialog)).toBe("Only when the field consent is “Yes”");
    // Its choices are offered at once
    fireEvent.click(await screen.findByRole("option", { name: "No" }));
    expect(summary(dialog)).toBe("Only when the field consent is “No”");

    await pick(condition, "Field", "due");
    const date = within(condition).getByLabelText(/Value/);
    expect(date).toHaveAttribute("type", "date");
    fireEvent.change(date, { target: { value: "2026-09-29" } });
    expect(summary(dialog)).toBe("Only when the field due is “2026-09-29”");
    fireEvent.change(date, { target: { value: "" } });
    expect(summary(dialog)).toBeUndefined();

    await pick(condition, "Field", "free");
    expect(within(condition).getByRole("textbox", { name: "Value" })).toHaveAttribute("inputmode", "text");

    await pick(condition, "Comparison", "is empty");
    expect(within(condition).queryByRole("combobox", { name: "Compared with" })).not.toBeInTheDocument();
  });

  it("compares with what another source reads", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a condition" }));
    const [ condition ] = conditions(dialog);
    await pick(condition, "Compare", "A property");
    const property = within(condition).getByRole("textbox", { name: "Property" });
    expect(property).toBeRequired();
    fireEvent.change(property, { target: { value: "status" } });
    fireEvent.change(property, { target: { value: "" } });
    fireEvent.change(property, { target: { value: "status" } });
    await pick(condition, "Compared with", "The tags");
    expect(summary(dialog)).toBe("Only when the status is the tag list");
    await pick(condition, "Compared with", "A field");
    await pick(condition, "Field", "age");
    expect(summary(dialog)).toBe("Only when the status is the field age");
    // Once what is compared changes, what it is compared with stays when it is not a value
    await pick(condition, "Compare", "The tags");
    expect(summary(dialog)).toBe("Only when the tag list is the field age");
  });

  it("keeps groups inside groups, each of which closes to what it says", async () => {
    const { dialog } = renderDialog();

    fireEvent.click(within(dialog).getByRole("button", { name: "Add a group" }));
    const nested = within(dialog).getByRole("group", { name: "Group of conditions" });
    fireEvent.click(within(nested).getByRole("button", { name: "Add a condition" }));
    expect(within(nested).getAllByRole("group", { name: "Condition" })).toHaveLength(2);
    fireEvent.click(within(nested).getByRole("button", { name: "Any" }));
    expect(within(nested).getByText("Or")).toBeInTheDocument();

    fireEvent.click(within(nested).getAllByRole("button", { name: "Collapse this group" })[0]);
    expect(within(nested).getByText("Some of these are not complete yet.")).toBeInTheDocument();
    expect(within(nested).queryByRole("group", { name: "Condition" })).not.toBeInTheDocument();
    fireEvent.click(within(nested).getByRole("button", { name: "Expand this group" }));
    fireEvent.click(within(nested).getAllByRole("button", { name: "Remove this condition" })[0]);
    fireEvent.click(within(nested).getAllByRole("button", { name: "Remove this condition" })[0]);
    fireEvent.click(within(nested).getByRole("button", { name: "Collapse this group" }));
    expect(within(nested).getByText("No condition yet.")).toBeInTheDocument();
    fireEvent.click(within(nested).getByRole("button", { name: "Expand this group" }));

    fireEvent.click(within(nested).getByRole("button", { name: "Add a group" }));
    expect(within(nested).getAllByRole("group", { name: "Group of conditions" })).toHaveLength(1);
    fireEvent.click(within(nested).getAllByRole("button", { name: "Remove this group" })[0]);
    expect(within(dialog).queryByRole("group", { name: "Group of conditions" })).not.toBeInTheDocument();
  });

  it("closes a complete group to what it says, and the top to nothing more than the summary", async () => {
    const { dialog } = renderDialog({ condition: {
      ...ANY_OF_TWO,
      "third": { "jcr:primaryType": "cond:ConditionGroup", "sling:resourceSuperType": "cond/Condition",
        "requireAll": true, "only": single("is empty", field("due")) },
    } });

    const nested = within(dialog).getByRole("group", { name: "Group of conditions" });
    fireEvent.click(within(nested).getByRole("button", { name: "Collapse this group" }));
    expect(within(nested).getByText("the field due is empty")).toBeInTheDocument();

    fireEvent.click(within(dialog).getAllByRole("button", { name: "Collapse this group" })[0]);
    expect(within(dialog).queryByRole("group", { name: "Condition" })).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/not complete yet/)).not.toBeInTheDocument();
    expect(summary(dialog)).toMatch(/^Only when/);
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
