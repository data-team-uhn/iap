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

import { screen } from "@testing-library/react";

import TextParameterField from "@iap/workflows/TextParameterField";

import { renderField, STATES } from "./parameterField.fixture";

describe("a parameter shown as text", () => {
  it("names what it holds", () => {
    renderField(TextParameterField, {}, [ "ACTIVE" ], true);

    expect(screen.getByText("To state: ACTIVE")).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("says so when it holds nothing", () => {
    renderField(TextParameterField, {}, [], true);

    expect(screen.getByText("To state: —")).toBeInTheDocument();
  });

  it("lists several values under the labels the entry gives them", () => {
    renderField(TextParameterField, { maxValues: 0, options: STATES }, [ "DRAFT", "ACTIVE" ], true);

    expect(screen.getByText("To state: Draft, Active")).toBeInTheDocument();
  });
});

describe("a parameter taking one value", () => {
  it("is typed into, and reports what was typed", async () => {
    const { onChange, user } = renderField(TextParameterField, { description: "The state this move ends in" });

    expect(screen.getByText("The state this move ends in")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox"), "X");

    expect(onChange).toHaveBeenCalledWith([ "X" ]);
  });

  it("hands what it shows on to be written once it is left", async () => {
    const { onChange, onCommit, user } = renderField(TextParameterField, {}, [ "A" ]);

    await user.type(screen.getByRole("textbox"), "B");
    expect(onChange).toHaveBeenCalledWith([ "AB" ]);
    expect(onCommit).not.toHaveBeenCalled();
    await user.tab();

    // The field is controlled, so what it hands on is the value it was given
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith([ "A" ]);
  });

  it("hands what it shows on to be written when Enter is pressed", async () => {
    const { onCommit, user } = renderField(TextParameterField, {}, [ "A" ]);

    await user.type(screen.getByRole("textbox"), "{enter}");

    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith([ "A" ]);
  });

  it("reports a cleared field as holding nothing at all", async () => {
    const { onChange, user } = renderField(TextParameterField, {}, [ "A" ]);

    await user.clear(screen.getByRole("textbox"));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("is marked as required when a value has to be given", () => {
    renderField(TextParameterField, { minValues: 1 });

    expect(screen.getByRole("textbox")).toBeRequired();
  });
});

describe("a parameter with a choice of values", () => {
  it("offers the entry's choices, and a way back to none of them", async () => {
    const { onChange, onCommit, user } = renderField(TextParameterField, { options: STATES });

    await user.click(screen.getByRole("combobox"));
    expect(screen.getByRole("option", { name: "None" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Active" }));

    expect(onChange).toHaveBeenCalledWith([ "ACTIVE" ]);
    expect(onCommit).toHaveBeenCalledWith([ "ACTIVE" ]);
  });

  it("offers no way back to none when a value has to be given", async () => {
    const { user } = renderField(TextParameterField, { options: STATES, minValues: 1 });

    await user.click(screen.getByRole("combobox"));

    expect(screen.queryByRole("option", { name: "None" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("option")).toHaveLength(2);
  });

  it("keeps showing a stored value the entry no longer offers", async () => {
    // Dropping it silently would lose it from the diagram on the next save of anything else
    const { user } = renderField(TextParameterField, { options: STATES, minValues: 1 }, [ "RETIRED" ]);
    expect(screen.getByRole("combobox")).toHaveTextContent("RETIRED");

    await user.click(screen.getByRole("combobox"));

    expect(screen.getAllByRole("option").map(option => option.textContent))
      .toEqual([ "Draft", "Active", "RETIRED" ]);
  });
});

describe("a parameter taking several values", () => {
  it("shows what it holds as chips, under the labels the entry gives them", () => {
    renderField(TextParameterField, { maxValues: 0, options: STATES }, [ "DRAFT" ]);

    expect(screen.getByText("Draft")).toBeInTheDocument();
  });

  it("shows a chip per value, each still removable, and React complains about none of them", () => {
    // The list control hands each chip its own key alongside the props that make it removable. The
    // key is taken out and passed rather than spread in with the rest
    const complaints = vi.spyOn(console, "error").mockImplementation(() => undefined);

    renderField(TextParameterField, { maxValues: 0, options: STATES }, [ "DRAFT", "ACTIVE" ]);

    expect(screen.getAllByRole("button", { name: /^(Draft|Active)$/ })).toHaveLength(2);
    expect(complaints).not.toHaveBeenCalled();
    complaints.mockRestore();
  });

  it("takes anything typed when the entry constrains nothing", async () => {
    const { onChange, onCommit, user } = renderField(TextParameterField, { name: "performers", label: "Performers", maxValues: 0 });

    await user.type(screen.getByRole("combobox"), "reviewers{enter}");

    expect(onChange).toHaveBeenCalledWith([ "reviewers" ]);
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith([ "reviewers" ]);
  });

  it("takes what was typed when it is left without Enter", async () => {
    const { onChange, onCommit, user } = renderField(TextParameterField, { maxValues: 0 }, [ "editors" ], false, true);

    await user.type(screen.getByRole("combobox"), "reviewers, chairs");
    await user.tab();

    expect(onChange).toHaveBeenCalledWith([ "editors", "reviewers", "chairs" ]);
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith([ "editors", "reviewers", "chairs" ]);
  });

  it("splits what was typed at its commas when the list is kept in one attribute", async () => {
    // Joined with commas in the diagram, a comma inside one value would come back as two
    const { onChange, user } = renderField(TextParameterField, { maxValues: 0 }, [], false, true);

    await user.type(screen.getByRole("combobox"), "editors, reviewers{enter}");

    expect(onChange).toHaveBeenCalledWith([ "editors", "reviewers" ]);
  });

  it("keeps a comma inside a value when the list is not kept in one attribute", async () => {
    const { onChange, user } = renderField(TextParameterField, { maxValues: 0 });

    await user.type(screen.getByRole("combobox"), "Smith, J{enter}");

    expect(onChange).toHaveBeenCalledWith([ "Smith, J" ]);
  });

  it("says when it holds fewer values than it needs", () => {
    renderField(TextParameterField, { minValues: 2, maxValues: 0, description: "Who decides" }, [ "editors" ]);

    expect(screen.getByText("At least 2 values are needed")).toBeInTheDocument();
    expect(screen.queryByText("Who decides")).not.toBeInTheDocument();
  });

  it("says when it holds more values than it accepts", () => {
    renderField(TextParameterField, { maxValues: 2 }, [ "a", "b", "c" ]);

    expect(screen.getByText("At most 2 values are accepted")).toBeInTheDocument();
  });

  it("takes nothing typed into a closed list when it is left", async () => {
    const { onChange, onCommit, user } = renderField(TextParameterField, { maxValues: 0, options: STATES }, [ "DRAFT" ]);

    await user.type(screen.getByRole("combobox"), "Act");
    await user.tab();

    expect(onChange).not.toHaveBeenCalled();
    expect(onCommit).not.toHaveBeenCalled();
  });

  it("takes only the entry's choices when it constrains them", async () => {
    const { onChange, user } = renderField(TextParameterField, { maxValues: 0, options: STATES }, [ "DRAFT" ]);

    await user.click(screen.getByRole("button", { name: "Open" }));
    await user.click(screen.getByRole("option", { name: "Active" }));

    expect(onChange).toHaveBeenCalledWith([ "DRAFT", "ACTIVE" ]);
  });
});
