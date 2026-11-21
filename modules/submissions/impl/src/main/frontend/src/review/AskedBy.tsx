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

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

import { Stack, Tooltip, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { groupShortName } from "./reviewData";

// PRETOTYPE. Which reviewer groups ask each question, and what for, shown to the submitter beside the question:
// submitters often wonder why they are asked something.

type Node = Record<string, unknown>;

interface Asking {
  label: string;
  // The risks the answer is read for
  risks: string[];
}

const AskedByContext = createContext<Map<string, Asking[]>>(new Map());

const text = (value: unknown): string => typeof value === "string" ? value : "";
const isNode = (value: unknown): value is Node => typeof value === "object" && value !== null && !Array.isArray(value);

function parse(approvals: Node[]): Map<string, Asking[]> {
  const asked = new Map<string, Asking[]>();
  for (const approval of approvals) {
    const schema = text(approval["@path"]).split("/").slice(0, -1).join("/");
    for (const topic of Object.values(approval).filter(isNode)) {
      for (const source of Array.isArray(topic.sources) ? topic.sources : []) {
        const path = isNode(source) ? text(source["@path"]) : text(source);
        const part = path.startsWith(`${schema}/`) ? path.slice(schema.length + 1) : path;
        const known = asked.get(part) ?? [];
        const mine = known.find(one => one.label === text(approval.label));
        if (mine) {
          mine.risks.push(text(topic.text));
        } else {
          asked.set(part, [ ...known, { label: text(approval.label), risks: [ text(topic.text) ] } ]);
        }
      }
    }
  }
  return asked;
}

// Reads which groups ask what, from the approvals of the form being filled in
export function AskedByProvider({ approvals, children }: { approvals: string[]; children: ReactNode }) {
  const doFetch = useAuthenticatedFetch();
  const [ asked, setAsked ] = useState(new Map<string, Asking[]>());
  const key = approvals.join("\n");
  useEffect(() => {
    let current = true;
    void Promise.all(key.split("\n").filter(Boolean).map(path => doFetch(`${path}.2.json`)
      .then(response => (response.ok ? response.json() : {}) as Promise<Node>)))
      .then(read => current && setAsked(parse(read)));
    return () => {
      current = false;
    };
  }, [ doFetch, key ]);
  return <AskedByContext.Provider value={asked}>{children}</AskedByContext.Provider>;
}

// "Asked by REB and Digital", with what each reads the answer for on hover
export default function AskedBy({ question }: { question: string }) {
  const asking = useContext(AskedByContext).get(question);
  if (!asking) {
    return null;
  }
  return (
    <Stack direction="row" sx={{ gap: 0.5, flexWrap: "wrap", mt: 0.5, px: 1.75 }}>
      <Typography variant="caption" sx={{ color: "text.secondary" }}>Asked by</Typography>
      {asking.map((one, index) => (
        <Tooltip key={one.label} title={`${one.label} reads this for: ${one.risks.join(", ")}.`}>
          <Typography
            variant="caption" tabIndex={0}
            sx={{ color: "text.secondary", textDecoration: "underline dotted", cursor: "help" }}
          >
            {groupShortName(one.label)}{index < asking.length - 1 ? "," : ""}
          </Typography>
        </Tooltip>
      ))}
    </Stack>
  );
}
