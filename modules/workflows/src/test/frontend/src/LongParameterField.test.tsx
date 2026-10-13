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

import { useState } from "react";

import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import LongParameterField from "@iap/workflows/LongParameterField";

import { renderField, STATES, Themed } from "./parameterField.fixture";
import { parameter } from "./vocabulary.fixture";

describe("a parameter holding a whole number", () => {
  it("shows the number it holds, offering a keyboard of digits", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    const input = screen.getByRole("textbox");
    expect(input).toHaveValue("3");
    expect(input).toHaveAttribute("inputmode", "numeric");
  });

  it("refuses a decimal where a whole number is stored, and takes a sign either way", () => {
    const { onChange } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);
    const input = screen.getByRole("textbox");

    fireEvent.change(input, { target: { value: "1.5" } });
    fireEvent.change(input, { target: { value: "1e3" } });
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "-12" } });
    expect(onChange).toHaveBeenCalledWith([ "-12" ]);
    fireEvent.change(input, { target: { value: "+" } });
    expect(onChange).toHaveBeenCalledWith([ "+" ]);
  });

  it("takes a number written with a plus sign, as the server does", () => {
    renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "+5" ]);

    expect(screen.getByRole("textbox")).toHaveValue("+5");
    expect(screen.queryByText(/will be ignored/)).not.toBeInTheDocument();
  });

  it("flags a sign with no digits after it", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "-" ]);

    expect(screen.getByText('"-" is not a whole number, so it will be ignored')).toBeInTheDocument();
  });

  it("hands what it shows on to be written once it is left, or when Enter is pressed", async () => {
    const { onCommit, user } = renderField(LongParameterField, { dataType: "long" }, [ "3" ]);

    await user.type(screen.getByRole("textbox"), "{enter}");
    await user.tab();

    expect(onCommit).toHaveBeenCalledTimes(2);
    expect(onCommit).toHaveBeenLastCalledWith([ "3" ]);
  });

  it("reports a cleared field as holding nothing at all, and is marked when a value is required", () => {
    const { onChange } = renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "3" ]);
    const input = screen.getByRole("textbox");

    expect(input).toBeRequired();
    fireEvent.change(input, { target: { value: "" } });

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("starts empty when it holds nothing", () => {
    renderField(LongParameterField, { dataType: "long" });

    expect(screen.getByRole("textbox")).toHaveValue("");
  });

  it("is shown as text when read only", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "3" ], true);

    expect(screen.getByText("To state: 3")).toBeInTheDocument();
  });

  it("takes several values as chips, the way text does", () => {
    renderField(LongParameterField, { dataType: "long", maxValues: 0 }, [ "1", "2" ]);

    expect(screen.getAllByRole("button", { name: /^(1|2)$/ })).toHaveLength(2);
  });

  it("offers the entry's choices when it constrains them", () => {
    renderField(LongParameterField, { dataType: "long", options: STATES }, [ "DRAFT" ]);

    expect(screen.getByRole("combobox")).toHaveTextContent("Draft");
  });

  it("shows a value that is no number as written, and says it will be ignored", async () => {
    const { onChange, user } = renderField(LongParameterField, { dataType: "long", minValues: 1 }, [ "abc" ]);
    const input = screen.getByRole("textbox");

    expect(input).toHaveValue("abc");
    expect(screen.getByText('"abc" is not a whole number, so it will be ignored')).toBeInTheDocument();
    // Not held to whole numbers while it holds none
    fireEvent.change(input, { target: { value: "ab" } });
    expect(onChange).toHaveBeenCalledWith([ "ab" ]);
    await user.click(input);
    await user.tab();

    expect(screen.getByText('"abc" is not a whole number, so it will be ignored. A value is required'))
      .toBeInTheDocument();
  });

  it("stays a text field while a value that is no number is typed over", async () => {
    // Fed what it reports, as the panel does, so that clearing the value is what the field then shows
    function Edited() {
      const [ values, setValues ] = useState([ "abc" ]);
      return <LongParameterField parameter={parameter("count", { label: "Count", dataType: "long" })}
        values={values} onChange={setValues} onCommit={() => undefined} />;
    }
    const user = userEvent.setup();
    render(<Edited />, { wrapper: Themed });
    const input = screen.getByRole("textbox");

    await user.clear(input);
    expect(input).not.toHaveAttribute("type", "number");
    await user.type(input, "12x");

    expect(input).toHaveValue("12");
    expect(input).toHaveFocus();
  });

  it("flags a number too large to be stored", () => {
    renderField(LongParameterField, { dataType: "long" }, [ "9223372036854775808" ]);

    expect(screen.getByText('"9223372036854775808" is not a whole number, so it will be ignored'))
      .toBeInTheDocument();
  });

  it("asks for a required value once it has been left empty, and nothing of an optional one", async () => {
    const { user } = renderField(LongParameterField, { dataType: "long", minValues: 1 });

    await user.click(screen.getByRole("textbox"));
    await user.tab();

    expect(screen.getByText("A value is required")).toBeInTheDocument();
  });

  it("takes the extremes of what can be stored", () => {
    renderField(LongParameterField, { dataType: "long", maxValues: 0 },
      [ "9223372036854775807", "-9223372036854775808", "007" ]);

    expect(screen.queryByText(/will be ignored/)).not.toBeInTheDocument();
  });
});
