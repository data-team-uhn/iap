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

import {
  getChoice,
  getGeneratedVersion,
  getOpenStep,
  getStage,
  getStageRequirements,
  isNewAnswer,
} from "@iap/submissions/routeModel";
import type { FormQuestion, Requirement } from "@iap/submissions/submissionForm";
import { COMPLETE_EVENT, type SubmissionTask } from "@iap/submissions/taskModel";

import { CHOICE, GENERATED, PROPOSAL, QUESTIONNAIRE, attaching, hasProtocol, routeForm } from "./route.fixture";

function task(name: string, overrides: Partial<SubmissionTask> = {}): SubmissionTask {
  return { path: `/i/${name}`, label: name, outcomeOptions: [], "@events": [ COMPLETE_EVENT ], ...overrides };
}

const CHOOSE = task("chooseRoute", { requirement: "protocol" });
const FILL = task("fillQuestionnaire", { requirement: "questionnaire" });
const UPLOAD = task("uploadProposal", { requirement: "proposal" });
const SEND = task("sendWithoutProtocol");

const names = (requirements: Requirement[]) => requirements.map(requirement => requirement.name);

describe("getOpenStep", () => {
  it("finds the task the reader may complete with nothing to decide", () => {
    const review = task("review", { outcomeOptions: [ "approved", "rejected" ] });
    const theirs = task("check", { "@events": [] });

    expect(getOpenStep([ review, theirs, FILL ])).toBe(FILL);
    expect(getOpenStep([ review, theirs ])).toBeUndefined();
  });
});

describe("getChoice", () => {
  it("finds the form a step names, and nothing for a name the form does not show", () => {
    const form = routeForm();

    expect(getChoice(form, "protocol")?.name).toBe("protocol");
    expect(getChoice(form, "proposal")).toBeUndefined();
    expect(getChoice(form, "missing")).toBeUndefined();
    expect(getChoice(form, undefined)).toBeUndefined();
  });
});

describe("getStage", () => {
  it("chooses while the first step is open, whatever else applies", () => {
    expect(getStage(routeForm({ readsDocuments: true }), CHOICE, CHOOSE)).toEqual({ name: "choose", step: CHOOSE });
  });

  it("reads on the way with documents to read, with or without a step of the submitter's", () => {
    const form = routeForm({ readsDocuments: true });

    expect(getStage(form, CHOICE, UPLOAD)).toEqual({ name: "read", step: UPLOAD });
    expect(getStage(form, CHOICE, undefined)).toEqual({ name: "read", step: undefined });
  });

  it("fills a form a step asks for", () => {
    const form = routeForm({ requirements: [ CHOICE, QUESTIONNAIRE, GENERATED ] });

    expect(getStage(form, CHOICE, FILL)).toEqual({ name: "fill", step: FILL, asked: QUESTIONNAIRE });
  });

  it("sends at a step naming no form, or with no step at all", () => {
    const form = routeForm({ requirements: [ CHOICE, QUESTIONNAIRE, GENERATED, PROPOSAL ] });

    expect(getStage(form, CHOICE, SEND)).toEqual({ name: "send" });
    expect(getStage(form, CHOICE, task("upload", { requirement: "proposal" }))).toEqual({ name: "send" });
    expect(getStage(form, CHOICE, undefined)).toEqual({ name: "send" });
  });
});

describe("getStageRequirements", () => {
  const form = routeForm({ requirements: [ CHOICE, QUESTIONNAIRE, GENERATED ] });

  it("shows nothing under the choice while choosing", () => {
    expect(names(getStageRequirements(form, CHOICE, { name: "choose", step: CHOOSE }))).toEqual([]);
  });

  it("leaves out what the platform writes while a form is filled in, since Next writes it", () => {
    expect(names(getStageRequirements(form, CHOICE, { name: "fill", step: FILL, asked: QUESTIONNAIRE })))
      .toEqual([ "questionnaire" ]);
  });

  it("shows everything but the choice otherwise", () => {
    expect(names(getStageRequirements(form, CHOICE, { name: "send" }))).toEqual([ "questionnaire", "generatedProposal" ]);
    expect(names(getStageRequirements(form, CHOICE, { name: "read" }))).toEqual([ "questionnaire", "generatedProposal" ]);
  });
});

describe("isNewAnswer", () => {
  const question: FormQuestion = hasProtocol([ "true" ]);

  it("takes a different answer as new", () => {
    expect(isNewAnswer(question, [ "false" ])).toBe(true);
  });

  it("does not take the same answer, or a cleared one, as new", () => {
    expect(isNewAnswer(question, [ "true" ])).toBe(false);
    expect(isNewAnswer(question, [])).toBe(false);
  });
});

describe("getGeneratedVersion", () => {
  it("finds the version the platform wrote, with its .docx", () => {
    const written = { title: "proposal.pdf", path: "/s/p/file/pdfFile", docx: "/s/p/file/generated.docx" };
    const form = routeForm({ requirements: [ CHOICE, attaching(GENERATED, written) ] });

    expect(getGeneratedVersion(form)).toBe(written);
  });

  it("finds nothing before anything was written, or once the submitter uploaded their own", () => {
    const own = { title: "mine.docx", path: "/s/p/file/uploadedFile" };

    expect(getGeneratedVersion(routeForm({ requirements: [ GENERATED ] }))).toBeUndefined();
    expect(getGeneratedVersion(routeForm({ requirements: [ attaching(GENERATED, own) ] }))).toBeUndefined();
    expect(getGeneratedVersion(routeForm({ requirements: [ attaching(PROPOSAL, own) ] }))).toBeUndefined();
  });
});
