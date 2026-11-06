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

// PRETOTYPE. What the review pages compute, kept out of the components.

export const DAY = 24 * 60 * 60 * 1000;

export interface Countdown {
  // Whole days left, or overdue when negative
  days: number;
  paused: boolean;
  label: string;
}

function asDate(value: unknown): Date | undefined {
  if (typeof value !== "string" || value === "") {
    return undefined;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

function daysText(days: number): string {
  return days === 1 ? "1 day" : `${days} days`;
}

// How long the reviewers have left: counted to the deadline, or frozen where the clock was paused while the
// study is back with its team. A day not yet over still counts as left.
export function getCountdown(due: unknown, pausedAt: unknown, now: Date = new Date()): Countdown | undefined {
  const deadline = asDate(due);
  if (!deadline) {
    return undefined;
  }
  const paused = asDate(pausedAt);
  const from = paused ?? now;
  const days = Math.ceil((deadline.getTime() - from.getTime()) / DAY);
  const left = days >= 0 ? `${daysText(days)} left` : `${daysText(-days)} overdue`;
  return { days, paused: Boolean(paused), label: paused ? `Paused, ${left}` : left };
}

export interface Message {
  path: string;
  text: string;
  author: string;
  created: string;
  replies: Message[];
  // PRETOTYPE: whether the person reading wrote it, when that is known
  mine?: boolean;
}

type Node = Record<string, unknown>;

function children(node: Node, type: string): Node[] {
  return Object.values(node)
    .filter((child): child is Node => typeof child === "object" && child !== null && !Array.isArray(child)
      && (child as Node)["jcr:primaryType"] === type)
    .sort((a, b) => Date.parse(text(a["jcr:created"])) - Date.parse(text(b["jcr:created"])));
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function message(node: Node): Message {
  return {
    path: text(node["@path"]),
    text: text(node.text),
    author: text(node.author),
    created: text(node["jcr:created"]),
    replies: children(node, "sub:Reply").map(message),
  };
}

// The comments a feedback node holds, oldest first, with their replies
export function getMessages(feedback: Node | undefined): Message[] {
  return feedback ? children(feedback, "sub:Comment").map(message) : [];
}

// Every message of a thread, comments and replies, oldest first
export function flatten(messages: Message[]): Message[] {
  return messages.flatMap(one => [ one, ...one.replies ])
    .sort((a, b) => Date.parse(a.created) - Date.parse(b.created));
}

// The messages someone has not seen: written by others after they last looked
export function countUnread(messages: Message[], me: string, seenAt: string | undefined): number {
  const seen = seenAt ? Date.parse(seenAt) : Number.NEGATIVE_INFINITY;
  return flatten(messages).filter(one => one.author !== me && Date.parse(one.created) > seen).length;
}
