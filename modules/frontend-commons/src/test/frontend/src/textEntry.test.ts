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
import { TEXT_ENTRY } from "@iap/frontend-commons/textEntry";

function matches(html: string): boolean {
  const holder = document.createElement("div");
  holder.innerHTML = html;
  return holder.firstElementChild!.matches(TEXT_ENTRY);
}

describe("TEXT_ENTRY", () => {
  it("matches where text is typed", () => {
    expect(matches("<textarea></textarea>")).toBe(true);
    expect(matches("<input>")).toBe(true);
    expect(matches("<input type=\"text\">")).toBe(true);
    expect(matches("<input type=\"search\">")).toBe(true);
    expect(matches("<div contenteditable></div>")).toBe(true);
    expect(matches("<div contenteditable=\"true\"></div>")).toBe(true);
  });

  it("leaves out inputs with nothing to type in, and what is not editable", () => {
    expect(matches("<input type=\"checkbox\">")).toBe(false);
    expect(matches("<input type=\"radio\">")).toBe(false);
    expect(matches("<input type=\"hidden\">")).toBe(false);
    expect(matches("<input type=\"submit\">")).toBe(false);
    expect(matches("<div contenteditable=\"false\"></div>")).toBe(false);
    expect(matches("<select></select>")).toBe(false);
  });

  it("leaves out what is hidden from people, such as the input behind a select menu", () => {
    expect(matches("<input aria-hidden=\"true\" tabindex=\"-1\">")).toBe(false);
    expect(matches("<textarea aria-hidden=\"true\" readonly></textarea>")).toBe(false);
  });
});
