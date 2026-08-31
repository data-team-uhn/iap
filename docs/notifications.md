# Notifications

IAP tells people about itself through **notification channels**. Something gathers what
is worth saying; a channel delivers it. The two are separate modules on purpose, so a
new channel reaches every existing source of messages and a new source reaches every
channel.

| Channel | Module | Delivers |
|---|---|---|
| Chat webhook | `modules/slack-notifications` | Scheduled posts to a webhook, assembled from producers |
| Email | `modules/email-notifications` | Emails built from templates kept in the repository |

More channels are expected. A channel is an ordinary module: it decides what triggers a
delivery, what a message looks like in its medium, and how the platform's content
becomes one. Where a channel needs to reach a remote service it uses
[`HttpRequests`](http-requests.md) rather than talking to the network itself.

## Chat webhooks

Each factory configuration of `Scheduled Slack Notifications` is one scheduled job, so
an instance can run a nightly summary and a weekly digest to different channels.

| Setting | Default | Meaning |
|---|---|---|
| `name` | — | Names the notification and its job |
| `schedule` | `%ENV%SLACK_NOTIFICATIONS_SCHEDULE` | Quartz expression — `0 0 0 * * ? *` nightly, `0 0 9 ? * MON *` Monday mornings |
| `endpoint` | `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` | The webhook address |
| `title` | empty | An optional line above the attachments |
| `include` | — | Which producers to use; empty means all |
| `notificationParameters` | — | `key=value` strings passed to the producers |
| `skipEmpty` | `true` | Post nothing when there is nothing to report, rather than "Nothing to report" |

A value starting with **`%ENV%`** names an environment variable to read instead of being
used directly — `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` reads `$SLACK_NOTIFICATIONS_ENDPOINT`.
Both the schedule and the endpoint default to that form: a webhook address is a secret
and belongs in the environment, not in a configuration file.

**A notification whose endpoint resolves to nothing is not scheduled at all**, and says
so once in the log. Scheduling a job that could only ever fail would report the same
failure every night forever.

### Providing messages

What a chat notification says comes from producers, not from the channel. Implement
`SlackNotificationProducer` (`…slacknotifications.spi`) as an OSGi component:

```java
@NotNull String           getName();
@NotNull List<JsonObject> prepareMessages(@NotNull Map<String, String> extraParameters);
```

The SPI also carries the attachment keys — `TITLE`, `TEXT`, `COLOR` — and the four
colours to use, so producers agree on what a severity looks like: `INFO` `999`,
`SUCCESS` `393`, `WARNING` `BA0`, `ERROR` `900`.

**Return an empty list to say nothing.** That counts as silence, so a notification whose
producers are all quiet posts nothing at all when `skipEmpty` is set.

### The status report producer

`StatusReportNotification` (named `status`) turns the [status report](status.md) into
attachments, one per report, coloured by level. Three extra parameters:

| Parameter | Default | Means |
|---|---|---|
| `statusReport.targetStatusLevel` | `INFO` | The lowest level to include. An unrecognized level falls back to `INFO` rather than costing the whole notification |
| `statusReport.includeTags` | all | Comma-separated status tags |
| `statusReport.unprivileged` | `false` | Generate the report for an unprivileged audience |

Because it goes through the status SPI it picks up every registered reporter
automatically — including [error tracking](error-tracking.md) — with no coupling between
those modules. A producer for another channel would get the same reports for free.

## Email

Emails are built from `mail:Template` nodes in the repository, so a deployment can
reword what the platform says without touching code.

### A template

| Property | Meaning |
|---|---|
| `senderAddress` | **Required.** The `From` address |
| `senderName` | Display name shown with it |
| `replyToAddress`, `replyToName` | Where replies go, defaulting to the sender |
| `subject` | **Required.** Itself a template |
| *anything else* | A variable available to the subject and both bodies |

| Child file | Meaning |
|---|---|
| `bodyTemplate.html` | The HTML body |
| `bodyTemplate.txt` | The plain text body |
| `bodyTemplate.header.html`, `bodyTemplate.footer.html` | Wrapped around the HTML body |
| `bodyTemplate.header.txt`, `bodyTemplate.footer.txt` | Wrapped around the plain text body |
| *any other file* | An inline attachment, referenced from the HTML as `cid:<file name>` |

Both bodies are optional, but a template needs at least one.

Headers, footers and attachments shared by every email live under
**`/libs/iap/mailTemplates/`** (`EmailTemplate.COMMON_TEMPLATES_PATH`). A template picks
up the shared headers and footers automatically, overrides them by carrying its own, and
asks for a shared attachment with an `includeAttachment_<file name>` property
(`INCLUDE_ATTACHMENT_PREFIX`).

**A header and footer never make a body on their own.** A template with no
`bodyTemplate.txt` has no plain text part at all, even where shared plain text headers
and footers exist — and the same for HTML.

### Writing one

The subject and both bodies are
[Apache Velocity](https://velocity.apache.org/engine/devel/user-guide.html) templates, so
a template can branch and loop rather than only substitute.

```
Dear ${name},

Your request "$submission.title" was $submission.status.

#if($submission.status == 'rejected')
The reason given was: $submission.reason
#else
Nothing further is needed from you.
#end

#foreach($task in $outstanding)
  - $task.label, due $task.due
#end
```

Worth knowing when writing a template:

- **A name nobody supplied fails the whole email.** Rendering it literally would mail
  somebody `Dear ${name}`, which is worse than not sending. There are two ways to say
  something is optional, and they mean different things: `#if($note)` for a name that may
  not be there at all, `$!{note}` for one that is there but may be empty.
- **Values are escaped in the HTML body and not in the plain text one.** A value carrying
  `<` goes into the HTML as text rather than as a tag — the values are free text somebody
  typed. Markup a template genuinely means to produce belongs in the template, where it is
  not escaped.
- **A template that does not parse sends nothing.** Failures raise
  `EmailTemplateException`, whose message names the line and column — never the values,
  which are somebody's answers.

### Sending one

```java
final EmailTemplate template = EmailTemplate.builder(templateNode, resolver).build();
final Email email = template.getEmailBuilder(Map.of("name", "Alice", "submission", submission))
    .withRecipient("alice@example.invalid", "Alice")
    .build();
EmailUtils.sendHtmlEmail(email, this.mailService);
```

`getEmailBuilder(variables)` fills in the subject and both bodies; what the caller passes
overrides the template's own properties. **A value can be anything, not only a string** —
the template decides how to read it — which is what lets the wording of an email stay in
the template while the caller just hands over the submission. `EmailTemplate.builder()`
also takes no arguments, for a template assembled in code rather than read from a node.

`sendTextEmail` sends only the plain text part. `sendHtmlEmail` sends the HTML one, the
plain text part as a fallback for clients that cannot show it, and the inline
attachments. Since either part may be missing, **each method requires the part it sends**:
asking for the text form of an HTML-only template raises `IllegalArgumentException`
rather than sending an email with no body.

### Checking that mail works

```
GET /content.emailtest.html?fromEmail=…&fromName=…&toEmail=…&toName=…[&isHtml=true]
```

Sends one fixed message, so an administrator can tell whether an instance's SMTP
configuration works without waiting for the platform to have a reason to write to
somebody. **Restricted to `admin`**, since it mails an arbitrary address.

Sending is asynchronous, so the endpoint waits for it to finish before answering, for up
to fifteen seconds. It reports `200` once the message has gone out, `500` if it was
refused, and `202` if the send is still going when the wait runs out — a relay that never
answers must not park the request thread indefinitely. The outcome reaches the instance
log either way, which is the only place a send that outlives the wait can report to.

A failure answers that it failed and nothing more: what the mail server said goes to the
log, because that text routinely names the relay, its port and the account the instance
authenticates with.

SMTP settings come from the module's feature configuration —
`emailnotifications.smtps.host`, `.port`, `.username`, `.password`,
`.checkserveridentity`, `.from` — with the password decrypted through Sling's crypto
service, keyed by the `SLING_COMMONS_CRYPTO_PASSWORD` environment variable.

## Catching mail in development

**Module:** `modules/email-catcher` · **Bundle:** `iap-email-catcher` (start-order 25)

A development, test or demo instance has no mail server, and pointing one at a real relay
to see what the platform writes is both awkward and a way to mail real people by accident.
So the platform carries an **email catcher**: a `MailService` that files each message under
`/CaughtMail` instead of delivering it.

Nothing has to be configured to use it. Once switched on it registers at
`service.ranking:Integer=1000`, above Sling's own mail service, which registers without a
configuration and so ranks at zero — so every `@Reference MailService`, the test endpoint
and `EmailUtils` included, is handed the catcher without knowing it. The real service still
starts and stays reachable for anything that deliberately asks for it.

A caught message is a `mail:CaughtMessage` under a `mail:CaughtMailHomepage`, written by
the `iap-email-catcher` service user, which may write there and nowhere else. Filing
happens on the way out of `sendMessage`; a failure is logged loudly and returned as a
failed future, because nothing consumes the future a mail service reports into.

### Reading it over HTTP

```
GET /CaughtMail.messages.json
```

Bound to the homepage resource type, and answers with what has been caught, newest
first:

```json
{
  "total": 1,
  "messages": [
    {
      "path": "/CaughtMail/6f3a…",
      "caughtAt": "2026-08-23T11:04:17+00:00",
      "subject": "Your request was approved",
      "from": [ "IAP <noreply@example.com>" ],
      "to": [ "Priya <priya@example.com>" ],
      "cc": [], "bcc": [], "replyTo": [],
      "headers": [ "X-Reason: a reminder" ],
      "textBody": "…", "htmlBody": "…"
    }
  ]
}
```

Addresses are kept as they were written, display names and all, and `to`, `cc` and `bcc`
stay apart — whether an address was visible to the others is usually the point of
looking. Attachments are not stored: what they were is in the headers, and their bytes
are not what anybody reads a caught message to check.

`/CaughtMail` is readable by `everyone`, so a developer or an integration test can read
what was sent without being handed a second set of credentials. That is only tolerable
because of the next paragraph.

**It ships everywhere and is off everywhere until somebody turns it on.** The setting is
a plain `enabled` boolean that defaults to false, so the bundle publishes no mail service
until it is switched on. Two ways to do that, and they are the same setting:

- **In the Felix console**, under Configuration → *IAP Email Catcher*. Tick the box and
  mail starts being filed; untick it and real sending comes back. Neither needs a
  restart, because the consumers bind dynamically and the registration follows the
  setting.
- **In a feature**, which is how the `test_tar` and `demo_tar` aggregates get it without
  anybody clicking anything: `dev/email-catcher-enabled.json` sets `enabled` to true. The
  production aggregates do not include it.

That is deliberately not the same as leaving the bundle out of production. One set of
artifacts is built rather than two, the difference between environments is a
configuration a deployment can read back, and catching mail on some new instance is a
configuration rather than a rebuild — which is exactly what somebody debugging a staging
environment would want.

### Reading it in the administration console

`.messages.json` above is for a test to assert on. A person gets **Caught mail** in the
administration console: a dashboard summary, a table of what has been filed at
`/admin/mail`, and a page per message.

The table is the [shared entity grid](frontend-development.md#shared-components), which
pages, sorts, searches and filters through
`/CaughtMail.paginate.json` — so the module serves no listing of its own. That works
because `mail:CaughtMailHomepage` extends `data:EntityHomepage` and declares
`childNodeType = mail:CaughtMessage`, which is what tells the pagination servlet what it
is listing.

The one thing the grid cannot answer is whether mail is being caught **right now**:

```
GET /CaughtMail.status.json
→ { "enabled": true, "total": 12 }
```

`enabled` is the presence of a registered `MailService` carrying the catcher's own
`iap.mail.catcher` service property — not a reading of the configuration. The
configuration says what somebody asked for; the registry says what is in force, and those
differ while a component is settling or if something outranks the catcher in turn. The
count travels with it because the two are useless apart: a count alone cannot tell
"nothing has been sent" from "everything sent was delivered by mail".

A message's own page shows the addresses one per line — a comma is legal inside a display
name, so a joined list is ambiguous exactly where somebody is checking the addressing —
and draws the HTML body **in an iframe with an empty `sandbox` attribute**, which blocks
scripts, forms, navigation and same-origin access at once. These bodies come from the
platform's own templates today, but a caught message is whatever was handed to the mail
service, and this page renders it inside an administrator's session. It costs the page
nothing: what is being checked is how the message reads, and a link in it is worth seeing
rather than following. The plain text and the HTML source sit beside the rendering.

## Future work

- **Nothing produces messages yet** beyond the status report, and nothing sends an email yet.
  Both are wiring, and the workflow engine is the natural place for it: an email belongs to
  a submission changing state. The catcher above is what makes that testable — a workflow
  that mails somebody can be asserted on without a mail server.
- **Filling a template in from a submission.** The engine can already reach into whatever
  a caller passes, so what is left is deciding what a caller *should* pass — the
  submission, its answers, the actor, the workflow instance — and that is best designed
  alongside the workflow actions that will trigger these emails rather than guessed at now.
- **A shared notion of a message.** Chat producers return webhook attachments, and email
  is written as templates; a third channel would need one or the other, or something
  above both. Worth settling when there is a second source of messages to design it
  against.
