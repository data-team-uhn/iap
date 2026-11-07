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

import { useEffect, useState } from "react";

import HourglassEmptyOutlined from "@mui/icons-material/HourglassEmptyOutlined";
import PersonOutlined from "@mui/icons-material/PersonOutlined";
import { Chip } from "@mui/material";

import { useAuthenticatedFetch, type AuthenticatedFetch } from "@iap/frontend-commons/reLogin";

type Turn = "yours" | "studyTeam" | "others";

type Node = Record<string, unknown>;

const OPEN = [ "created", "active" ];

// The study's tasks, read once per page and study
const tasks = new Map<string, Promise<Node[]>>();

function tasksOf(doFetch: AuthenticatedFetch, path: string): Promise<Node[]> {
  let read = tasks.get(path);
  if (!read) {
    read = doFetch(`${path}/wf:instances.2.json`)
      .then(response => (response.ok ? response.json() : {}) as Promise<Node>)
      .then(instances => Object.values(instances).flatMap(instance => typeof instance === "object" && instance
        ? Object.entries(instance as Node).filter(([ , task ]) => typeof task === "object" && task !== null
          && (task as Node)["jcr:primaryType"] === "wf:TaskInstance")
          .map(([ name, task ]) => ({ ...task as Node, "@name": name }))
        : []))
      .catch(() => []);
    tasks.set(path, read);
  }
  return read;
}

// Whose move it is on a study, for a reviewer: theirs while their group's review task is open; the study team's while
// the team is answering what that group sent back; otherwise the other reviewers'
function turnOf(studyTasks: Node[], myGroups: string[]): Turn {
  const mine = studyTasks.find(task => Array.isArray(task.performers)
    && task.performers.some(group => myGroups.includes(String(group))));
  if (!mine) {
    return "others";
  }
  if (OPEN.includes(String(mine.status))) {
    return "yours";
  }
  // The group's own answer task: "respond" for the REB, "<approval>Respond" for the others
  const name = String(mine["@name"]);
  const answer = name === "rebTask" ? "respond" : name.replace(/Task$/, "Respond");
  const answering = studyTasks.find(task => task["@name"] === answer);
  return answering && OPEN.includes(String(answering.status)) ? "studyTeam" : "others";
}

const SAID: Record<Turn, { label: string; tip: string }> = {
  yours: { label: "Your turn", tip: "Your review is waiting on your decision" },
  studyTeam: { label: "With the study team", tip: "You asked for changes; the study team is answering" },
  others: { label: "With other reviewers", tip: "Your part is done for now; other groups are still reviewing" },
};

// PRETOTYPE. Whose move it is on a study in the reviewer's queue, read from the study's open tasks
export default function WhoseTurn({ path, myGroups }: { path: string; myGroups: string[] }) {
  const doFetch = useAuthenticatedFetch();
  const [ turn, setTurn ] = useState<Turn>();
  useEffect(() => {
    if (myGroups.length === 0) {
      return;
    }
    let current = true;
    void tasksOf(doFetch, path).then(found => current && setTurn(turnOf(found, myGroups)));
    return () => {
      current = false;
    };
  }, [ doFetch, path, myGroups ]);
  if (!turn) {
    return null;
  }
  const { label, tip } = SAID[turn];
  return (
    <Chip
      size="small" title={tip} label={label} variant={turn === "yours" ? "filled" : "outlined"}
      color={turn === "yours" ? "primary" : "default"}
      icon={turn === "yours" ? <PersonOutlined /> : <HourglassEmptyOutlined />}
    />
  );
}
