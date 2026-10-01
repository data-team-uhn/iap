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

import { fireEvent, render, screen } from "@testing-library/react";

import { schemaSources } from "@iap/schemas/conditionModel";
import QuestionPicker from "@iap/schemas/QuestionPicker";
import { indexQuestions } from "@iap/schemas/schemaVersionTreeModel";
import { VersionConditionsContext } from "@iap/schemas/versionConditions";

import { CONTENT, HOMEPAGE, withPaths } from "./schemaServer.fixture";

const version = withPaths("/Schemas/study/v2", { ...HOMEPAGE.study.v2, ...CONTENT["study/v2"] });
const index = indexQuestions(version);

const renderPicker = (value: string[]) => {
  const onChange = vi.fn();
  const sources = schemaSources(index);
  render(
    <VersionConditionsContext value={{ index, sources, offered: sources }}>
      <QuestionPicker label="Question" value={value} disabled={false} required onChange={onChange}
        questions={index.questions} />
    </VersionConditionsContext>
  );
  return { onChange, input: screen.getByRole("combobox", { name: "Question" }) };
};

describe("QuestionPicker", () => {
  it("shows a question named by its path as the question it is", () => {
    const { input } = renderPicker([ "basics/design/arms" ]);

    expect(input).toHaveValue("Which arms does it have?");
  });

  it("shows what names no question for what it is, and can be cleared", () => {
    const { input, onChange } = renderPicker([ "gone" ]);

    expect(input).toHaveValue("gone");
    fireEvent.click(screen.getByTitle("Clear"));
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
