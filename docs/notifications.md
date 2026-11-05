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

The SPI also carries the attachment keys, `TITLE_KEY`, `TEXT_KEY` and `COLOR_KEY`, and
the four colours to use, so producers agree on what a severity looks like:
`INFO_COLOR` `999`, `SUCCESS_COLOR` `393`, `WARNING_COLOR` `BA0`, `ERROR_COLOR` `900`.

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

## Workflow notifications

A workflow tells people what happened with a message event: a message intermediate throw
event its process passes, or a message end event it stops at. The event carries what
happened as its message name, who to tell, how urgent it is, and where its template
lives, so adding a notification is adding a node and a template, never Java. Who to tell
is written in the same vocabulary the workflow uses to say who may act: `@creator` is
whoever raised the thing in question, `@entityCreator` whoever raised the entity it
belongs to, and anything else is a person or a group, expanded into its members when the
moment comes. The same name always means the same people in both places, because both are
answered by the same service. The event's properties, and why nothing is sent before the
workflow's changes are committed, are in
[workflows](workflows.md#message-events-tell-people-what-happened).

What happens next deliberately splits in two. The workflow states *what happened and who it
concerns*; **how each person hears about it is decided per channel**, by whichever deliveries are
installed — an email right away, a marker in the interface, or nothing at all for somebody who
cannot be reached. A channel that cannot carry a notification simply declines it, and that is a
normal answer: the workflow has already moved on either way.

### Where the templates live

Each notification's template is a folder under `/libs/iap/notificationTemplates/`, holding one
rendering per channel. A `uiMessage` property on the folder is the one-sentence form a compact list can
show; an `email` child carries the email rendering described below. A folder with no rendering for
some channel simply says nothing through that channel — which is a choice its author made, not an
error.

## Email

Emails are built from `mail:Template` nodes in the repository, so a deployment can
reword what the platform says without touching code. A person's address is on their
account, at `profile/email`. For somebody who signs in through an identity provider, it
is copied from there at each sign-in. No workflow carries an address.

`EmailDelivery` emails a notification as soon as it is offered one, if its urgency is
`immediate`, the recipient has an address, and its template folder has an `email` child.
That child is a `mail:Template`. Besides the notification's own variables, it can use
four more:

| Variable | Value |
|---|---|
| `subjectResource` | The resource the notification is about |
| `subjectPath` | Its path |
| `subjectTitle` | Its `title` property, or an empty string |
| `event` | What happened, as the message name, such as `approved` |

### A template

| Property | Meaning |
|---|---|
| `senderAddress` | **Required.** The `From` address |
| `senderName` | The display name shown with it |
| `replyToAddress`, `replyToName` | Where replies go, the sender's if not set |
| `subject` | **Required.** A template itself |
| *any other single-valued property* | A variable for the subject and both bodies; `jcr:` and `sling:` properties are not |

| Child file | Meaning |
|---|---|
| `bodyTemplate.html` | The HTML body |
| `bodyTemplate.txt` | The plain text body |
| `bodyTemplate.header.html`, `bodyTemplate.footer.html` | Wrapped around the HTML body |
| `bodyTemplate.header.txt`, `bodyTemplate.footer.txt` | Wrapped around the plain text body |
| *any other file* | An inline attachment, which the HTML shows as `cid:<file name>` |

Either body may be left out, but an email cannot be built from a template with neither.

Shared headers and footers live under **`/libs/iap/mailTemplates/`**
(`EmailTemplate.COMMON_TEMPLATES_PATH`), and shared attachments in its
`commonAttachments` folder. The platform ships none. A template uses the shared header
and footer unless it has its own. It includes a shared attachment only if it has an
`includeAttachment_<file name>` property (`INCLUDE_ATTACHMENT_PREFIX`), whatever its
value.

**A header and footer alone do not make a body.** A template with no `bodyTemplate.txt`
has no plain text body, even when a shared plain text header and footer exist. The same
holds for HTML.

### Writing one

The subject and both bodies are
[Apache Velocity](https://velocity.apache.org/engine/devel/user-guide.html) templates, so
a template can branch and loop as well as substitute.

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

- **A variable that nobody supplied fails the whole email**, instead of mailing somebody
  `Dear ${name}`. Write `#if($note)` around a variable that may be missing. A quiet
  reference, `$!{note}`, is only for a variable supplied as `null`.
- **Values are escaped in the HTML body, and not in the subject or the plain text
  body.** A value holding `<` goes into the HTML as text, not as a tag. Markup
  belongs in the template, which is not escaped.
- **A template reaches nothing but its values.** `#include` and `#parse` find no
  files, and Velocity's `SecureUberspector` refuses calls such as
  `getClass().getClassLoader()`.
- **A template that does not parse sends nothing.** The failure is an
  `EmailTemplateException`, whose message names the line and column.

### Sending one

```java
final EmailTemplate template = EmailTemplate.builder(templateNode, resolver).build();
final Email email = template.getEmailBuilder(Map.of("name", "Alice", "submission", submission))
    .withRecipient("alice@example.invalid", "Alice")
    .build();
EmailUtils.sendEmail(email, this.mailService);
```

`getEmailBuilder(variables)` fills in the subject and both bodies. A value the caller
passes replaces the template's property of the same name. **A value can be any object,
not only a string**, such as the submission itself, and the template reads what it needs
from it. `EmailTemplate.builder()` with no arguments builds a template in code.

`sendEmail` sends an email as HTML if it has an HTML body, and as plain text otherwise.
`sendHtmlEmail` sends the HTML body, the plain text body if there is one, and the inline
attachments. `sendTextEmail` sends only the plain text body. **Each requires the body it
sends**: asking for the plain text of an HTML-only email throws
`IllegalArgumentException`.

None of them waits for the email to go out. Sling's mail service sends it on a thread
pool, and a send that fails is logged and recorded through
[error tracking](error-tracking.md).

### Checking that mail works

```
GET /content.emailtest.html?fromEmail=…&fromName=…&toEmail=…&toName=…[&isHtml=true]
```

Sends one fixed message, so an administrator can check an instance's SMTP
configuration. **Only the `admin` user may use it**, since it mails any address.

The endpoint waits up to fifteen seconds for the send to finish. It answers `200` once
the message has gone out, `500` if it failed, and `202` if it is still being sent. The
outcome reaches the instance log in every case.

An answer that the send failed does not say why. What the mail server said goes to the
log, since it can name the relay, its port and the account the instance signs in with.

The SMTP settings are the module's feature variables: `emailnotifications.smtps.host`,
`.port`, `.username`, `.password`, `.checkserveridentity` and `.from`. Sling's crypto
service decrypts the password, with the `SLING_COMMONS_CRYPTO_PASSWORD` environment
variable as its key.

## Catching mail in development

**Module:** `modules/email-catcher` · **Bundle:** `iap-email-catcher` (start-order 27)

A development, test or demo instance has no mail server, and pointing one at a real relay
can mail real people by accident. The **email catcher** is a `MailService` that files each
message under `/CaughtMail` instead of sending it.

While on, it registers at `service.ranking:Integer=1000`. Sling's `SimpleMailService` is
configured without a ranking, so it ranks at zero, and it keeps running. A reference
already bound to Sling's service moves to the catcher only if it is dynamic and greedy.
Both references in `email-notifications` are, so the email delivery and the test endpoint
send through the catcher without knowing it.

A caught message is a `mail:CaughtMessage` under a `mail:CaughtMailHomepage`, written by
the `iap-email-catcher` service user, which may write there and nowhere else. The message
is filed before `sendMessage` returns. A failure is logged, and the returned future fails
with it.

### Reading it over HTTP

```
GET /CaughtMail.messages.json
```

Bound to the homepage resource type, it answers with every caught message, newest first:

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

Addresses are kept as the message wrote them, display names included. `to`, `cc` and
`bcc` stay apart, so a reader can see which addresses the other recipients saw. A missing
subject or body is an empty string. Attachments are not stored.

`/CaughtMail` is readable by `iap-administrators` alone, since a caught message may hold
private information. A test reads it with administrator credentials.

Anyone, signed in or not, may ask whether mail is being caught:

```
GET /libs/iap/mail-catcher.catching.json
→ { "catching": true }
```

It answers on a `mail:CatcherStatus` node that the module's repoinit creates at
`/libs/iap/mail-catcher`, with an `everyone` read grant of its own. `/libs` is exempt
from `sling.auth.requirements`, so it answers before sign-in too. The answer is whether
the catcher's mail service is registered, not what its setting asks for.

**It ships everywhere and is off until somebody turns it on.** The setting is a plain
`enabled` boolean that defaults to false. Three ways change it, and all three write the
same setting:

- **On the administration dashboard**, with the switch on the *Caught mail* widget. It
  asks for confirmation in both directions, since switching off on a test instance mails
  whatever addresses its data holds. It then writes the setting through
  `ConfigurationAdmin`: `POST /CaughtMail.catching.json` with `enabled=true` or
  `enabled=false`, allowed to `iap-administrators` and the repository superuser.
- **In the Felix console**, under Configuration → *IAP Email Catcher*. Tick the box and
  mail starts being filed; untick it and real sending comes back. The instance needs no
  restart: the catcher's component restarts, and registers or withdraws its mail service.
- **In a feature**, which is how the `test_tar` and `demo_tar` aggregates catch mail from
  the start: `dev/email-catcher-enabled.json` sets `enabled` to true. The production
  aggregates do not include it.

A restart reapplies what the deployment ships. The test and demo aggregates come back
catching however they were left. A production aggregate carries no configuration for the
catcher, so a change made there survives.

Shipping the bundle everywhere means one set of artifacts, not two. The difference
between environments is a configuration that a deployment can read back, and catching
mail on a new instance needs no rebuild.

**While mail is being caught, every page says so**, the sign-in page included, in a red
banner across the top. `CatchingBanner` is an `iap/coreUI/frameTop` extension with
`ext:visibleBeforeLogin`, and reads `catching.json` when the page loads. The dashboard
switch fires an `iap:mail-catching-changed` window event, and the banner reads the status
again. Another tab updates on its next page load.

### Reading it in the administration console

People read the caught mail in the administration console, as **Caught mail**: a dashboard
summary with the switch, a table of the caught messages at `/admin/mail`, and a page per
message.

The table is the [shared entity grid](frontend-development.md#shared-components), which
pages, sorts, searches and filters through `/CaughtMail.paginate.json`. That works
because `mail:CaughtMailHomepage` extends `data:EntityHomepage` and declares
`childNodeType = mail:CaughtMessage`, which tells the pagination servlet what it lists.

The dashboard summary and the table's page read whether mail is being caught from here:

```
GET /CaughtMail.adminSummary.json
→ {
    "enabled": { "label": "Catching mail", "value": true },
    "total": { "label": "Caught so far", "value": 12 }
  }
```

`enabled` is whether a `MailService` with the catcher's `iap.mail.catcher` service
property is registered, not what the setting asks for. The two differ while the catcher
restarts. The count comes with it, because a count alone cannot tell "nothing has been
sent" from "everything sent was delivered".

A message's own page shows the addresses one per line, since a display name may contain a
comma. It draws the HTML body **in an iframe with an empty `sandbox` attribute**, which
blocks scripts, forms, navigation and same-origin access. A caught message is whatever was
handed to the mail service, and the page runs in an administrator's session. The plain
text and the HTML source are in tabs beside the rendering.

## Stored notifications

`iap-stored-notifications` (`modules/notifications/stored`) is the channel that keeps what the others
only say once: every notification it accepts becomes a small record under
`/Notifications/<recipient>`, and the interface shows each person theirs. It accepts every urgency, because storing is not interrupting —
an urgent decision and a quiet aside both belong in the list of what happened while nobody was
looking.

Each record carries the rendered sentence (the template's `uiMessage`, or the subject's title and
the event when there is none), a link to what it is about, who caused it, and whether it has been
read. The sentence is rendered at the moment of delivery, deliberately: templates can be reworded and
subjects can be deleted, but what somebody was told should read tomorrow the way it read today.

Who may see a notification is the repository's answer, not filtering code: each recipient's folder
is readable by that one account, and everything filed under it inherits the grant. A listing
therefore runs on the reader's own session and simply cannot contain anybody else's notifications. The recipient may also mark theirs read —
`POST <notification>.markRead.json` — and nobody else gets a writable view to do even that.

The bell in the application bar is where they land: it counts the unread ones, lists the latest when
opened — opening the list is what "reading" means, the same way glancing at a stack of letters takes
them off the doormat — and each entry leads to the thing it reports on.

## Future work

- **A channel that collects rather than interrupts.** Both shipped deliveries act at once,
  so a notification marked `batched` reaches the stored list and no further. A digest that
  accepts it, gathers a day of them and mails one summary is the missing half of what
  urgency already says.
- **Settings that belong to the person, not the workflow.** A notification says what
  happened and who it concerns; how loudly each of them hears about it should be theirs to
  choose, including turning a channel off. The account handle on `Recipient` is where that
  would be read from.
- **Somewhere to see all of them.** The bell shows the latest few, and nothing lists the
  rest — so a notification scrolls out of reach rather than being filed. A page over the
  same listing would also give "mark these read" a home that is not "whatever the dropdown
  happened to show".
