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

import { ExtensionList, type Extension } from "@iap/ui-extension/ExtensionList";

// An extension whose render component reports both its own name and the data it was handed, so the
// tests can tell that the extension itself is passed through to it.
const extension = (name: string, extra: Record<string, unknown> = {}): Extension => ({
  "ext:name": name,
  "ext:render": ({ extension: own }: { extension: Extension }) => (
    <div>{`${name}:${String(own["ext:data"] ?? "")}`}</div>
  ),
  ...extra,
});

describe("ExtensionList", () => {
  it("renders each extension's component, in the order given", () => {
    const { container } = render(<ExtensionList extensions={[extension("First"), extension("Second")]} />);

    expect(container.textContent).toBe("First:Second:");
  });

  it("hands each component its own extension", () => {
    render(<ExtensionList extensions={[extension("Widget", { "ext:data": 7 })]} />);

    expect(screen.getByText("Widget:7")).toBeInTheDocument();
  });

  it("skips an extension that has no render component", () => {
    const { container } = render(<ExtensionList extensions={[{ "ext:name": "Broken" }, extension("Fine")]} />);

    expect(container.textContent).toBe("Fine:");
  });

  it("renders nothing for an empty list", () => {
    const { container } = render(<ExtensionList extensions={[]} />);

    expect(container).toBeEmptyDOMElement();
  });
});
