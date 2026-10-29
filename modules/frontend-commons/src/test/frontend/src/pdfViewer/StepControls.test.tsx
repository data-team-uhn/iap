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
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import StepControls from "@iap/frontend-commons/pdfViewer/StepControls";

describe("StepControls", () => {
  it("steps either way, as far as the ends", async () => {
    const onStep = vi.fn();
    render(<StepControls position={1} count={3} itemName="change" onStep={onStep} />);

    expect(screen.getByText("1 of 3")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Previous change" })).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: "Next change" }));
    expect(onStep).toHaveBeenCalledWith(2);
  });

  it("keeps room for the widest text it can show, out of the page's text", () => {
    const { container } = render(
      <StepControls position={1} count={12} itemName="change" heading="12 changes" widest={[ "Change 12 of 12" ]} onStep={vi.fn()} />,
    );

    expect(screen.getByText("12 changes")).toBeInTheDocument();
    expect(screen.queryByText("Change 12 of 12")).not.toBeInTheDocument();
    expect(container.querySelector("[data-size='Change 12 of 12']")).not.toBeNull();
    expect(container.querySelector("[data-size='12 of 12']")).toBeNull();
  });

  it("keeps room for each widest text once, though given twice", () => {
    const { container } = render(
      <StepControls position={1} count={2} itemName="change" heading="2 changes" widest={[ "Note 1 of 1", "Note 1 of 1" ]} onStep={vi.fn()} />,
    );

    expect(container.querySelectorAll("[data-size='Note 1 of 1']")).toHaveLength(1);
  });

  it("names each button after the item it goes to, where they are of more than one sort", () => {
    render(<StepControls position={2} count={3} itemName={{ previous: "note", next: "change" }} onStep={vi.fn()} />);

    expect(screen.getByRole("button", { name: "Previous note" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Next change" })).toBeEnabled();
  });

  it("keeps room for the count at its last position", () => {
    const { container } = render(<StepControls position={2} count={12} itemName="passage" onStep={vi.fn()} />);

    expect(container.querySelector("[data-size='12 of 12']")).not.toBeNull();
  });
});
