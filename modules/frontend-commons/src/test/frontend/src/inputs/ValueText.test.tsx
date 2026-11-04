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

import ValueText from "@iap/frontend-commons/inputs/ValueText";

describe("ValueText", () => {
  it("shows the values by their labels", () => {
    render(<ValueText label="Formats" values={[ "pdf", "odt" ]} choices={[ { value: "pdf", label: "PDF" } ]} />);

    expect(screen.getByText("Formats: PDF, odt")).toBeInTheDocument();
  });

  it("shows a dash for no value", () => {
    render(<ValueText label="Formats" values={[]} />);

    expect(screen.getByText("Formats: —")).toBeInTheDocument();
  });
});
