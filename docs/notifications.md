# Notifications

IAP tells people about itself through **notification channels**. Something gathers what
is worth saying; a channel delivers it. A workflow's notification is offered to every
channel that delivers notifications. The chat webhook posts only what its own producers
gather.

| Channel | Module | Delivers |
|---|---|---|
| Chat webhook | `modules/slack-notifications` | Scheduled posts to a webhook, assembled from producers |
| Email | `modules/email-notifications` | Emails built from templates kept in the repository |
| In the application | `modules/notifications/stored` | Notifications kept for their recipients, shown under a bell in the app bar |

More channels are expected. A channel is an ordinary module: it decides what triggers a
delivery, what a message looks like in its medium, and how the platform's content
becomes one. A channel that calls a web service does so through
[`HttpRequests`](http-requests.md).

## Chat webhooks

Each factory configuration of `Scheduled Slack Notifications` is one notification,
posted on its own schedule. An instance can have several, such as a nightly one to one
channel and a weekly one to another.

| Setting | Default | Meaning |
|---|---|---|
| `name` | — | The notification's name, which labels it in the logs. Best kept different for each notification; each gets a job of its own either way |
| `schedule` | `%ENV%SLACK_NOTIFICATIONS_SCHEDULE` | A Quartz cron expression, such as `0 0 0 * * ? *` for every night or `0 0 9 ? * MON *` for Monday mornings. Every night at midnight if it resolves to nothing |
| `endpoint` | `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` | The webhook URL |
| `title` | empty | A line posted above the attachments |
| `include` | — | The names of the producers to ask, such as `status`. Empty asks every one |
| `notificationParameters` | — | `key=value` strings for the producers |
| `skipEmpty` | `true` | Post nothing when no producer has an attachment. With `false`, post "Nothing to report" |

A schedule or endpoint starting with **`%ENV%`** is read from the environment variable
it names: `%ENV%SLACK_NOTIFICATIONS_ENDPOINT` reads `$SLACK_NOTIFICATIONS_ENDPOINT`.
Both default to that form. A webhook URL is a secret, and belongs in the environment
rather than in a configuration file.

**A notification whose endpoint resolves to nothing is not scheduled.** Its activation
logs and records that once, instead of a failed post every night.

### Providing messages

A chat notification's attachments come from producers. A producer is an OSGi component
implementing `SlackNotificationProducer` (`…slacknotifications.spi`):

```java
@NotNull String           getName();
@NotNull List<JsonObject> prepareMessages(@NotNull Map<String, String> extraParameters);
```

`getName()` is the name a configuration's `include` lists. The SPI also holds the
attachment keys, `TITLE_KEY`, `TEXT_KEY` and `COLOR_KEY`, and one colour per severity:
`INFO_COLOR` `999`, `SUCCESS_COLOR` `393`, `WARNING_COLOR` `BA0`, `ERROR_COLOR` `900`.

**Return an empty list to say nothing.** When every producer returns one and `skipEmpty`
is set, nothing is posted. A producer must not throw: an exception loses the whole
message, the other producers' attachments included.

### The status report producer

`StatusReportNotification` (named `status`) turns the [status reports](status.md) into
attachments, one per report, coloured by its level. It reads three extra parameters:

| Parameter | Default | Means |
|---|---|---|
| `statusReport.targetStatusLevel` | `INFO` | The lowest level to include, in upper or lower case. An unknown level is logged, and `INFO` is used instead |
| `statusReport.includeTags` | all | The tags of the reporters to include, comma-separated, such as `problems` |
| `statusReport.unprivileged` | `false` | `true` asks the reporters to leave sensitive information out. With `false`, a report can quote error messages and stack traces |

It reads the reports through `StatusReportManager`, so it includes every registered
reporter, such as the one [error tracking](error-tracking.md) registers.

## Workflow notifications

A workflow tells people what happened with a message event: a message intermediate throw
event its process passes, or a message end event it stops at. The event carries what
happened as its message name, who to tell, how urgent it is, and its template. Adding a
notification takes a node and a template, and no Java. Who to tell is written in the
vocabulary the workflow uses to say who may act. `@creator` is whoever created the
resource the notification is about, and `@entityCreator` whoever created the entity it
belongs to. Any other name is a user or a group, and a group is expanded into its
members when the notification is sent. A name means the same people in both places,
because the same service answers both. The event's properties, and why nothing is sent
before the workflow's changes are committed, are in
[workflows](workflows.md#message-events-tell-people-what-happened).

**The workflow says what happened and who it concerns. Each delivery decides how a
person hears about it.** `NotificationService` (`modules/notifications/impl`) finds each
person and their account, and offers the notification to every registered delivery for
each of them. A delivery is an OSGi service implementing `NotificationDelivery`
(`…notifications.spi`), and decides from the notification and the account whether to
carry it. It can email the person at once, keep the notification in their list, or
decline. Declining is a normal answer, and somebody every delivery declines is not told.
A delivery that throws is logged and recorded, and the others are still offered the
notification.

### Where the templates live

A notification's template is a folder, kept under `/libs/iap/notificationTemplates/`. It
holds one rendering per channel. Its `uiMessage` property is the sentence the stored
notifications show, and its `email` child is the email described below. A folder with no
`email` child sends no email, and that is not an error. Without a `uiMessage`, a stored
notification says the subject's title and the event, such as `A long weekend: approved`.

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

`iap-stored-notifications` (`modules/notifications/stored`) keeps each notification for
its recipient to read in the application. It stores a `notif:Notification` in the
recipient's folder, `/Notifications/<user id>`. It accepts a notification of any
urgency, if it has a message to store: the template's `uiMessage`, or else the subject's
title and the event. It declines a notification with neither.

Each record holds the message, the event, the path of the subject, who caused it, the
urgency, and whether the recipient has marked it read. The message is rendered when the
notification is stored. It reads the same after the template is reworded or the subject
is deleted.

The delivery grants each recipient's folder to that recipient alone, the first time it
stores a notification for them. Everything in the folder inherits the grant. If the
grant fails, the notification is not stored. A recipient marks a notification read with
`POST <notification>.markRead.json`, on their own session. The servlet refuses anybody
the notification does not name, an administrator included.

**The bell in the app bar shows them.** Every minute it asks the server how many of the
current user's notifications are unread. Opening it lists the ten newest, and marks the
unread ones among them as read. An entry leads to what it is about. The listing asks for
the caller's own notifications by name. Access control does not apply to an
administrator, whose bell would otherwise list, and mark read, everybody's
notifications.

## Future work

- **A channel that collects notifications instead of sending each at once.** Both
  deliveries act at once, and a notification marked `batched` reaches only the stored
  list. A digest would accept it, gather a day of them and email one summary.
- **Settings that belong to the person, not the workflow.** Each person could choose how
  they hear about a notification, and turn a channel off. A delivery would read the
  settings from the account on `Recipient`.
- **Somewhere to see all of them.** The bell shows the ten newest, and nothing lists the
  others. A page over the same listing could also mark chosen notifications read.
