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

// Covers typing text: when a value is committed, and how the description and problems are shown.

import { fireEvent, screen } from "@testing-library/react";

import TextInput from "@iap/frontend-commons/inputs/TextInput";
import { renderValueInput } from "@iap/frontend-commons/inputs/valueInput.fixture";
import { REQUIRED_PROBLEM } from "@iap/frontend-commons/inputs/values";

const renderInput = (props: Partial<Parameters<typeof TextInput>[0]> = {}) =>
  renderValueInput(TextInput, { label: "Name", values: [ "Ann" ] }, props);

describe("TextInput", () => {
  it("reports what is typed, and nothing once emptied", () => {
    const { onChange, input } = renderInput();

    fireEvent.change(input!, { target: { value: "Bo" } });
    fireEvent.change(input!, { target: { value: "" } });

    expect(onChange.mock.calls).toEqual([ [ [ "Bo" ] ], [ [] ] ]);
  });

  it("commits a changed value once, on Enter or when left", () => {
    const { onCommit, input, retype } = renderInput();

    fireEvent.blur(input!);
    retype([ "Anne" ]);
    fireEvent.keyDown(input!, { key: "Enter" });
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [ "Anne" ] ] ]);
  });

  it("does not commit on the Enter that confirms a word in an input method", () => {
    const { onCommit, input, retype } = renderInput();

    retype([ "Anne" ]);
    fireEvent.keyDown(input!, { key: "Enter", isComposing: true });

    expect(onCommit).not.toHaveBeenCalled();
  });

  it("commits a value changed back to the last one committed, after a change from outside", () => {
    const { onCommit, input, retype } = renderInput();

    retype([ "Bo" ]);
    fireEvent.focus(input!);
    retype([ "Ann" ]);
    fireEvent.blur(input!);

    expect(onCommit.mock.calls).toEqual([ [ [ "Ann" ] ] ]);
  });

  it("commits a multiline value only when left", () => {
    const { onCommit, input, retype } = renderInput({ multiline: true });

    retype([ "Anne" ]);
    fireEvent.keyDown(input!, { key: "Enter" });
    expect(onCommit).not.toHaveBeenCalled();
    fireEvent.blur(input!);
    expect(onCommit).toHaveBeenCalledWith([ "Anne" ]);
  });

  it("finishes a changed value with no one listening", () => {
    const { input, retype } = renderInput({ onCommit: undefined });
    // React reports an error thrown by an event handler to the window, not to whoever fired the event
    const errors = vi.fn();
    window.addEventListener("error", errors);

    retype([ "Anne" ]);
    fireEvent.blur(input!);
    window.removeEventListener("error", errors);

    expect(errors).not.toHaveBeenCalled();
  });

  it("shows its description", () => {
    renderInput({ description: "Given name" });
    expect(screen.getByText("Given name")).toBeInTheDocument();
  });

  it("shows problems in place of the description", () => {
    const { input } = renderInput({ description: "Given name", problems: [ "Too short.", "Taken." ] });

    expect(screen.getByText("Too short. Taken.")).toBeInTheDocument();
    expect(screen.queryByText("Given name")).not.toBeInTheDocument();
    expect(input).toHaveAttribute("aria-invalid", "true");
  });

  it("flags a missing value only once the field is left", () => {
    const { input } = renderInput({ values: [], required: true });

    expect(screen.queryByText(REQUIRED_PROBLEM)).not.toBeInTheDocument();
    fireEvent.blur(input!);
    expect(screen.getByText(REQUIRED_PROBLEM)).toBeInTheDocument();
  });

  it("shows the value as text when read-only", () => {
    const { input } = renderInput({ readOnly: true });

    expect(input).toBeNull();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Ann")).toBeInTheDocument();
  });
});
