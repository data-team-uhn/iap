# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Writes the retrospective data study schema as initial content, from the outline below.

PRETOTYPE: the questions come from the reviewer groups' draft lists (not final). Run it after editing:
    python3 demos/institutional-review/tools/build_schema.py
"""

import json
import pathlib
import uuid

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/SLING-INF/content/Schemas/retrospectiveStudy.json"
NS = uuid.UUID("6a1f3c52-0b7e-4d8e-9a51-6c4f2e7d9b10")

# ---------------------------------------------------------------------------------------------------------
# Questions: (name, text, kind, options-or-None, extras)
# kind: yesno, one, many, text, long, date, number
# extras: description, when=(path, comparator, value), required (default True), listing
# ---------------------------------------------------------------------------------------------------------


def q(name, text, kind="text", options=None, **extras):
    return {"name": name, "text": text, "kind": kind, "options": options or [], **extras}


def section(name, title, items, description=None, when=None):
    return {"section": name, "title": title, "items": items, "description": description, "when": when}


YN = None

FORMS = [
    ("study", "About the study",
     "The study at a glance. Every reviewer reads this part.",
     [
         q("pi", "Who is the principal investigator?", "text",
           description="Name, title and department.", listing="pi"),
         q("department", "Which department and programs are involved?", "long"),
         q("summary", "Give a brief summary of the study.", "long",
           description="A few sentences a reviewer outside your field can follow."),
         q("sponsor", "Who sponsors the study?", "one",
           [("none", "No external sponsor"), ("academic", "An academic institution"), ("industry", "Industry")]),
         q("sponsorName", "Name the sponsor.", "text", when=("study/sponsor", "not equals", "none")),
     ]),
    ("classification", "Study classification",
     "What kind of study this is decides which rules apply to it.",
     [
         q("purpose", "What is the primary purpose of the work?", "one",
           [("research", "Research"), ("qi", "Quality improvement or quality assurance"),
            ("evaluation", "Program evaluation"), ("qiToResearch", "Began as quality improvement, now proposed as research"),
            ("caseStudy", "Case study")]),
         q("contact", "Will the study contact anyone, change anyone's care, or collect anything new?", "one",
           [("recordsOnly", "No. Records only."), ("laterContact", "No new collection, but contact may be needed later"),
            ("yes", "Yes")]),
         q("design", "What is the study design?", "one",
           [("chartReview", "Retrospective chart review"), ("cohort", "Retrospective cohort"),
            ("caseControl", "Case-control"), ("crossSectional", "Cross-sectional"),
            ("registry", "Registry or administrative data analysis"), ("model", "Prediction model development or validation"),
            ("mixed", "Mixed methods with a chart review component"), ("other", "Other")]),
         q("designOther", "Describe the design.", "long", when=("classification/design", "equals", "other")),
         q("question", "What question is the study trying to answer?", "long"),
         section("cohort", "Cohort", [
             q("population", "Who is in the cohort?", "long",
               description="The population, and the inclusion and exclusion criteria."),
             q("startDate", "From which date should records be included?", "date"),
             q("endDate", "Up to which date should records be included?", "date"),
             q("dateRationale", "Why this date range?", "long",
               description="An end date in the recent past means some people could still be asked for consent."),
             q("records", "About how many records will the study use?", "number"),
             q("exclusions", "Are any groups excluded for reasons other than the clinical question?", "many",
               [("none", "No exclusions beyond the clinical criteria"), ("language", "Language or interpreter availability"),
                ("age", "Age"), ("capacity", "Capacity to consent"), ("completeness", "Completeness of records"),
                ("other", "Other")]),
             q("exclusionsWhy", "Why are they excluded?", "long",
               when=("classification/cohort/exclusions", "excludes", "none")),
             q("minimum", "Are the date range, cohort and variables the minimum that answers the question?", "one",
               [("yes", "Yes"), ("notAssessed", "Not assessed")]),
         ]),
         section("sources", "Where the data comes from", [
             q("originalPurpose", "Why was the information originally collected?", "many",
               [("care", "Clinical care"), ("administration", "Administration"), ("qi", "Quality improvement"),
                ("researchConsent", "Earlier research, with consent"), ("researchNoConsent", "Earlier research, without consent"),
                ("unclear", "Unclear")]),
             q("dataSources", "What are the data sources?", "many",
               [("emr", "Electronic medical record"), ("registry", "Registry"), ("administrative", "Administrative data"),
                ("imaging", "Imaging"), ("priorResearch", "A prior research dataset"), ("public", "A public dataset"),
                ("vendor", "A company's product or service"), ("specimens", "Specimens")]),
             q("sourceDetails", "Name each source, and give its CAPCR number where it has one.", "long"),
             q("external", "Do any of the sources come from outside UHN?", "yesno"),
         ]),
     ]),
    ("identifiability", "Identifiability and linkage",
     "How identifiable the data is decides whether consent is needed at all.",
     [
         q("arrival", "In what form will the data reach the study team?", "one",
           [("direct", "Directly identifying"), ("indirect", "Indirectly identifying"),
            ("codedTeamKey", "Coded, with the key accessible to the team"), ("codedOutsideKey", "Coded, with the key held outside the team"),
            ("anonymized", "Anonymized, no key retained"), ("never", "Never identifiable")]),
         q("keyHolder", "Who holds the linking key?", "one",
           [("custodian", "The data custodian"), ("thirdParty", "An independent third party"),
            ("team", "The study team"), ("none", "No key exists")]),
         q("fields", "Which identifiers and fields are included, and why is each one needed?", "long",
           description="Attach the full list as the variable list document. Summarize the identifiers here."),
         q("recognizable", "Could someone be recognized from the combination of variables?", "one",
           [("likely", "Likely"), ("mitigated", "Possible, and mitigated"), ("unlikely", "Unlikely")],
           description="Think of the cohort size, how rare the diagnosis is, and the geographic scope."),
         q("lessIdentifiable", "Could the study be done with less identifiable data?", "one",
           [("yesWill", "Yes, and it will be"), ("no", "No, for the reason given below"), ("notAssessed", "Not assessed")]),
         q("lessIdentifiableWhy", "Why not?", "long",
           when=("identifiability/lessIdentifiable", "equals", "no")),
         q("reidentification", "What have you done to keep people from being re-identified?", "long",
           description="Both for one person and for the dataset as a whole."),
         q("linkage", "Will any datasets be combined?", "many",
           [("no", "No"), ("internal", "Yes, with another internal dataset"), ("external", "Yes, with an external dataset"),
            ("public", "Yes, with a public dataset")]),
         q("linkageRisk", "Is the combined data more identifying than either source, and how is that handled?", "long",
           when=("identifiability/linkage", "excludes", "no")),
     ]),
    ("consent", "Consent",
     "Using health information without consent needs every condition for a waiver to hold.",
     [
         q("sought", "Is consent being sought?", "one",
           [("all", "Yes, from everyone"), ("subset", "Yes, from some"), ("waiver", "No. A waiver is requested."),
            ("notApplicable", "Not applicable, the data is not identifiable")]),
         q("priorConsent", "Does consent already obtained cover this use of the data?", "yesno",
           description="If it does, attach the consent form and give its study's CAPCR number."),
         q("priorConsentCapcr", "Which CAPCR numbers does that consent belong to?", "text",
           when=("consent/priorConsent", "equals", True)),
         section("waiver", "Waiver of consent", [
             q("whyIdentifiable", "Why is identifiable information essential to the question?", "long"),
             q("welfare", "Could using the data without consent affect anyone's welfare?", "one",
               [("no", "No foreseeable effect"), ("mitigated", "Possibly, and it is mitigated"), ("notAssessed", "Not assessed")]),
             q("impracticable", "Why is asking for consent impossible or unreasonably burdensome?", "many",
               [("cohortSize", "The size of the cohort"), ("deceased", "Many are deceased"),
                ("lost", "Many are lost to follow-up"), ("exposure", "Contacting them would itself expose private information"),
                ("law", "Privacy law bars contacting them"), ("bias", "Asking would bias the results")],
               description="Inconvenience alone is not enough."),
         ], when=("consent/sought", "equals", "waiver")),
     ]),
    ("access", "Access to the data",
     "The study can only do what the data custodian allows.",
     [
         q("custodian", "Who is the data custodian?", "text"),
         q("custodianApproval", "Has the custodian approved access?", "one",
           [("obtained", "Yes"), ("pending", "Requested, still pending"), ("notRequested", "Not yet requested"),
            ("self", "We are the custodian")]),
         q("authority", "What permits access without asking each person?", "many",
           [("phipa44", "A research plan under PHIPA section 44"), ("agreement", "A data sharing agreement"),
            ("policy", "Institutional policy"), ("originalConsent", "Consent at the original collection"),
            ("public", "The data is publicly available"), ("unsure", "Unsure")]),
     ]),
    ("sensitivity", "Sensitive information and communities",
     "Some data needs more care, and some needs a community's agreement before it is used.",
     [
         q("categories", "Does the dataset include any of these?", "many",
           [("mentalHealth", "Mental health or substance use"), ("sexualHealth", "Sexual health or sexual violence"),
            ("stigmatized", "HIV, hepatitis B or C, or another stigmatized status"), ("institutionalization", "Institutionalization"),
            ("imprisonment", "Current or prior imprisonment"), ("militaryPolice", "Military or police employment"),
            ("transgender", "Transgender status"), ("undocumented", "Undocumented status"), ("genetic", "Genetic or genomic data"),
            ("ipv", "Intimate partner violence"), ("reproductive", "Reproductive care"), ("children", "Children"),
            ("cognitive", "Cognitive impairment"), ("homelessness", "Homelessness"), ("none", "None of these")]),
         q("indigenous", "Could the data or results be identified as coming from a First Nations, Inuit or Métis community?", "one",
           [("no", "No"), ("yes", "Yes"), ("possibly", "Possibly. An identity variable or community-linked geography is included."),
            ("unknown", "Unknown")]),
         section("community", "Community engagement", [
             q("covered", "Is the use covered by a research agreement or by consent given at the original collection?", "one",
               [("agreement", "A research agreement"), ("consent", "The original consent"), ("no", "No"), ("unsure", "Unsure")]),
             q("engaged", "Has the community been engaged, and what was agreed?", "one",
               [("completed", "Yes. The outcome is described below."), ("underway", "Underway"), ("notBegun", "Not begun")]),
             q("engagedOutcome", "What was agreed?", "long",
               when=("sensitivity/community/engaged", "equals", "completed")),
         ], when=("sensitivity/indigenous", "not equals", "no")),
         q("input", "Has anyone placed to see the risks been asked for their view, and what came of it?", "one",
           [("completed", "Yes. The outcome is described below."), ("planned", "Planned"), ("notSought", "Not sought")],
           when=("sensitivity/categories", "excludes", "none")),
         q("inputOutcome", "What came of it?", "long", when=("sensitivity/input", "equals", "completed")),
         q("equity", "Have you taken the UHN research IDEA courses and applied the IHP standard?", "yesno"),
     ]),
    ("handling", "Storing, sharing and reporting the data",
     "Where the data goes, who sees it, and what is left of it at the end.",
     [
         section("leaving", "Data leaving UHN", [
             q("leaves", "Will the data leave UHN systems at any point?", "many",
               [("no", "No"), ("institution", "Yes, to another participating institution"),
                ("provider", "Yes, to an external service provider or processing tool"), ("abroad", "Yes, outside Canada"),
                ("undetermined", "Not determined yet")],
               description="Transcription services, statistical consultants and automated document processing count."),
             q("destination", "Where does it go, how is it protected, and under which agreement?", "long",
               when=("handling/leaving/leaves", "excludes", "no")),
             q("anonymizedOut", "Will data shared outside be anonymized, and any linkage log destroyed?", "yesno",
               when=("handling/leaving/leaves", "excludes", "no")),
             q("otherUses", "Is any other use of the data anticipated?", "many",
               [("none", "None"), ("futureResearch", "Future research"), ("repository", "Deposit in a repository"),
                ("teaching", "Teaching"), ("software", "Developing or evaluating software or models"),
                ("sharing", "Sharing outside the listed team")]),
         ]),
         section("storage", "Storage and security", [
             q("location", "Where will the data be stored and analyzed?", "many",
               [("server", "An institutional secure server"), ("uhnCloud", "A UHN-managed cloud"),
                ("enclave", "A secure analytics environment with no export"), ("externalCloud", "An external cloud"),
                ("workstation", "A study team workstation"), ("paper", "On paper")]),
             q("access", "Who has access, and how is it controlled?", "long"),
             q("policyDeviation", "Do storage, transport or destruction deviate from the UHN records policy (1.30.007)?",
               "yesno"),
             q("retention", "How long will the data be kept, and what happens to it at the end?", "one",
               [("destroyed", "Securely destroyed"), ("deidentified", "Identifiers destroyed, de-identified data kept"),
                ("archived", "Archived"), ("returned", "Returned to the custodian"),
                ("indefinite", "Kept indefinitely for future research")]),
             q("retentionPeriod", "For how long?", "text"),
             q("breachHarm", "What harm could follow a breach?", "many",
               [("reidentification", "Re-identification"), ("stigma", "Stigma"),
                ("employment", "Employment or insurance consequences"), ("legal", "Legal or immigration consequences"),
                ("minimal", "Minimal")]),
             q("pia", "Has a privacy impact assessment been done?", "one",
               [("completed", "Completed"), ("inProgress", "In progress"), ("notRequired", "Determined not required"),
                ("notAssessed", "Not assessed")]),
             q("disclosure", "Is there any foreseeable obligation to disclose data to a third party?", "many",
               [("none", "None"), ("mandatory", "Mandatory reporting"), ("reportable", "Reportable disease"),
                ("regulatory", "Regulatory"), ("subpoena", "Subpoena")]),
         ]),
         section("results", "Results", [
             q("reporting", "How will results be reported so that no person or small group can be identified?", "one",
               [("suppressed", "Aggregate, with small cells suppressed"), ("aggregate", "Aggregate, no suppression rule yet"),
                ("caseLevel", "Case-level detail"), ("undetermined", "Not determined")]),
             q("threshold", "Below how many people is a cell suppressed?", "number",
               when=("handling/results/reporting", "equals", "suppressed")),
             q("actionable", "Could the results reveal something about a person's health they would want to know?", "one",
               [("no", "No, results are aggregate only"), ("possible", "Possibly, clinically actionable"),
                ("likely", "Likely, such as genetic findings or imaging review")]),
             q("incidental", "What will you do if you come across an incidental finding?", "long",
               description="Contacting anyone needs a separate REB approval first."),
         ]),
     ]),
    ("digital", "Digital tools and services",
     "The software and services the study uses, and the data that goes into them.",
     [
         q("uhnHosted", "Is the study using an application or IT service hosted and maintained by UHN Digital?", "yesno",
           description="For example Epic, CRR, Medidata, DADOS, UHN's REDCap, UHN Fileshare, SharePoint or Office 365."),
         q("integration", "Does it need to integrate with an external tool?", "yesno",
           when=("digital/uhnHosted", "equals", True),
           description="If it does, the study team will be asked to submit a Digital Demand."),
         q("external", "Is the study using an application or IT service not hosted and maintained by UHN Digital?",
           "yesno"),
         section("externalTool", "The external tool", [
             q("patientData", "Does it need any patient data entered into it? It need not be health information.", "yesno"),
             q("bigPharma", "Is the sponsor on the large pharmaceutical companies list?", "yesno",
               when=("study/sponsor", "equals", "industry")),
             q("software", "What is the software called?", "text"),
             q("url", "What is its web address?", "text", required=False),
             q("license", "Who holds the license for it?", "text"),
             q("sponsorDevice", "Does the study use a device the sponsor provides, which needs internet access only?",
               "yesno",
               description="If it needs Wi-Fi, a Service Desk ticket has to move it to the internet-only network."),
         ], when=("digital/external", "equals", True)),
         q("build", "Does the study need UHN Digital to build a website, application or database, or to help transfer data?",
           "yesno", description="Something beyond a regular catalogue request."),
         q("onNetwork", "Does an application or IT service need to be set up on the UHN network?", "yesno"),
         q("alreadyOnNetwork", "Is it already on the UHN network, built by UHN staff or approved for an earlier use?",
           "yesno", when=("digital/onNetwork", "equals", True)),
         section("input", "Data put into the tools", [
             q("kind", "What kind of data will be put in?", "many",
               [("aggregate", "Aggregate data"), ("patient", "Patient data"), ("public", "Public data"), ("none", "No data")]),
             q("who", "Who will put it in?", "many", [("team", "The study team"), ("participants", "Participants")]),
         ], when=("digital/external", "equals", True)),
         q("previouslyApproved", "Was the tool approved by Digital Security or Privacy for another study?", "yesno"),
         q("different", "Is anything different about how this study uses it?", "yesno",
           when=("digital/previouslyApproved", "equals", True),
           description="New features, workflow, data transfers or access."),
         q("usage", "How is it being used?", "long", when=("digital/previouslyApproved", "equals", True)),
         section("ai", "Artificial intelligence", [
             q("used", "Is artificial intelligence used in any part of the study?", "yesno",
               description="Machine learning, natural language processing, large language models or other AI tools, "
                           "whether the study builds them or uses them."),
             q("details", "What is it used for?", "long", when=("digital/ai/used", "equals", True)),
             q("where", "Where does the processing run?", "one",
               [("local", "Only locally, on HPC4Health or UHN-managed systems"), ("external", "On an external service"),
                ("undetermined", "Not determined yet")], when=("digital/ai/used", "equals", True)),
             q("training", "Is the study's data used to train or refine a model?", "yesno",
               when=("digital/ai/used", "equals", True)),
             q("phase", "Which phase is the AI work in?", "one",
               [("development", "Development"), ("silent", "Silent trial"), ("implementation", "Clinical implementation"),
                ("none", "Not applicable, AI only helps carry out the research")], when=("digital/ai/used", "equals", True)),
             q("governance", "Have you read the UHN AI governance documents?", "yesno",
               when=("digital/ai/used", "equals", True)),
         ]),
     ]),
    ("team", "Study team",
     "Who runs the study, and anything that could pull them in another direction.",
     [
         q("gcpDate", "When did the principal investigator complete Good Clinical Practice training?", "date"),
         q("healthProfessionals", "Are any health professionals involved as investigators, study team members or "
                                  "participants?", "yesno", routing="involvesHealthProfessionals"),
         q("hpRoles", "What are their roles?", "many",
           [("pi", "Principal investigator"), ("coi", "Co-investigator"), ("team", "Study team member"),
            ("participant", "Participant")], when=("team/healthProfessionals", "equals", True)),
         q("hpIdentifiable", "Does the study use identifiable data about health professionals?", "yesno",
           when=("team/healthProfessionals", "equals", True)),
         q("conflict", "Does anyone on the team have a conflict of interest to declare?", "yesno"),
         q("conflictDetails", "What is it, is it perceived, potential or actual, and how is it managed?", "long",
           when=("team/conflict", "equals", True)),
         q("rmp", "Is a relationship management plan in place?", "yesno", when=("team/conflict", "equals", True)),
         q("glossary", "Has the study team checked its terms against the UHN glossary?", "yesno"),
     ]),
    ("resources", "Resources",
     "What the study needs from the department.",
     [
         q("funding", "Is funding required?", "yesno"),
         q("staff", "Are departmental staff required?", "yesno"),
         q("extraction", "Is help with extracting or collecting data required?", "yesno"),
         q("analyst", "Is dedicated analyst support required?", "yesno"),
         q("other", "Are any other resources requested from the department?", "long", required=False),
         q("similar", "Has a similar study recently been completed or started?", "yesno"),
         q("overlap", "Do its datasets or objectives overlap with this one?", "long",
           when=("resources/similar", "equals", True)),
         q("collaboration", "Have you considered collaborating?", "yesno", when=("resources/similar", "equals", True)),
     ]),
    ("lab", "Laboratory data",
     "Data the Laboratory Medicine Program would extract for the study.",
     [
         q("needed", "Does the study need the Laboratory Medicine Program to extract data from one or more laboratory "
                     "divisions?", "yesno", routing="needsLab"),
         section("request", "The extraction request", [
             q("divisions", "Which laboratory divisions?", "many",
               [("core", "Core lab test results: hematology, biochemistry, coagulation, immunology"),
                ("flow", "Flow cytometry"), ("hla", "Histocompatibility (HLA)"), ("genome", "Genome diagnostics"),
                ("cytogenetics", "Cancer cytogenetics"), ("transfusion", "Blood transfusion"),
                ("pathology", "Pathology or hematopathology"), ("microbiology", "Microbiology"),
                ("other", "Other, or not sure which")]),
             q("population", "Which diagnosis or patient population?", "long", required=False,
               description="For example, patients who underwent a liver biopsy at UHN."),
             q("parameters", "Which data or results?", "long", required=False,
               description="For example surgical number, biopsy site, tumour grade, test name and test date."),
             q("identifiers", "Which patient identifiers should the report include?", "text", required=False),
             q("contactPerson", "Who should receive the results?", "text", required=False,
               description="First name, last name and email address."),
             q("method", "How should the results be sent?", "one",
               [("email", "Secure email"), ("usb", "USB transfer"), ("other", "Other")], required=False),
             q("details", "Anything else about the request?", "long", required=False),
         ], when=("lab/needed", "equals", True)),
     ]),
    ("imaging", "Imaging data",
     "Images the Joint Department of Medical Imaging would provide.",
     [
         q("needed", "Does the study need images from the Joint Department of Medical Imaging?", "yesno", routing="needsImaging"),
         section("request", "The imaging request", [
             q("parameters", "Which images: disease type, modality and so on?", "long"),
             q("count", "About how many images?", "number"),
             q("period", "Which time period?", "text", description="Images older than 2009 do not exist."),
             q("deadline", "By when are they needed?", "date"),
             q("anonymization", "How must the images be anonymized?", "one",
               [("full", "Fully anonymized"), ("coded", "Coded"), ("none", "Not anonymized")]),
             q("transfer", "How will they be transferred, and where to?", "long"),
             q("access", "Who will access them?", "long"),
             q("company", "Is there an existing relationship between the investigator and an outside company?", "yesno"),
         ], when=("imaging/needed", "equals", True)),
     ]),
    ("oncology", "Princess Margaret data",
     "Oncology data from the Princess Margaret Cancer Centre.",
     [
         q("pmData", "Is the study using data from Princess Margaret to look at patient outcomes, treatment patterns or "
                     "cancer care?", "yesno", routing="usesPmData"),
         q("sites", "Which disease sites does it focus on?", "many",
           [("breast", "Breast"), ("gi", "Gastrointestinal"), ("gu", "Genitourinary"), ("gyne", "Gynecologic"),
            ("headNeck", "Head and neck"), ("heme", "Hematology"), ("lung", "Lung"), ("melanoma", "Melanoma"),
            ("neuro", "Neuro-oncology"), ("sarcoma", "Sarcoma"), ("other", "Other")],
           when=("oncology/pmData", "equals", True)),
         q("pmCoInvestigator", "Is there a Princess Margaret co-investigator for those disease sites?", "yesno",
           when=("oncology/pmData", "equals", True)),
         q("priorTrial", "Does the study use data already collected by an earlier clinical trial or study at UHN?",
           "yesno"),
     ]),
    ("agreements", "External organizations and agreements",
     "Anyone outside UHN taking part, and the agreements that cover them.",
     [
         q("involved", "Is an external organization involved?", "yesno", routing="externalInvolved"),
         section("organizations", "The external organizations", [
             q("names", "Which organizations, and what is each one's role?", "long"),
             q("accessUhnData", "Will they access UHN-held data?", "yesno"),
             q("flow", "Which way does data flow?", "one",
               [("out", "From UHN to them"), ("in", "From them to UHN"), ("both", "Both ways")]),
             q("patientLevel", "Is patient-level data involved?", "yesno"),
             q("stores", "Will an external party store study data?", "yesno"),
             q("jurisdiction", "Which organization stores it, and in which country?", "text",
               when=("agreements/organizations/stores", "equals", True)),
             q("funds", "Are funds transferred between the organizations?", "yesno"),
             q("existing", "Does an agreement already exist?", "yesno",
               description="If it does, attach it as the data sharing agreement."),
             q("related", "Are there related agreements, grants or arrangements?", "long", required=False),
         ], when=("agreements/involved", "equals", True)),
     ]),
]

# The forms every reviewer reads
EVERYONE = {"study"}

DOCUMENTS = [
    ("protocol", "Study protocol", "The full protocol: background, objectives, methods, analysis plan.", True, None),
    ("variables", "Variable list",
     "Every field the study will collect, and why each one is needed. A data collection form works too.", True, None),
    ("dsa", "Data sharing agreement", "The agreement that covers data leaving UHN.", True,
     ("handling/leaving/leaves", "excludes", "no")),
    ("priorConsentForm", "Original consent form", "The consent form this study relies on.", True,
     ("consent/priorConsent", "equals", True)),
]

# ---------------------------------------------------------------------------------------------------------
# Approvals: group, label, description, condition, topics = (name, text, description, sources)
# ---------------------------------------------------------------------------------------------------------

REB_TOPICS = [
    ("classification", "Study misclassification",
     "Work that is research going ahead as quality improvement, or a \"retrospective\" study that in fact contacts "
     "people or collects new data, which changes the consent obligation entirely.\n\n"
     "Look at: the stated purpose against the protocol's methods; any contact or new collection.\n\n"
     "Policy: TCPS 2 Art. 2.5 (p. 21); Ch. 5 §D (p. 87); Art. 5.6 (p. 90).\n\n"
     "Consult QIRC when the answer is quality improvement, and Data Services to confirm the source systems.",
     ["classification/purpose", "classification/contact", "classification/sources/originalPurpose",
      "classification/sources/dataSources", "classification/sources/sourceDetails", "classification/cohort/startDate",
      "classification/cohort/endDate", "protocol"]),
    ("design", "Study design",
     "Some designs are not a straightforward chart review, such as mixed methods with a chart review component, and "
     "need more than this form asks.",
     ["classification/design", "classification/designOther", "protocol"]),
    ("identifiability", "Identifiability",
     "Whether the data is identifiable decides whether consent is required at all. Coded data treated as "
     "non-identifiable while the team can reach the key lets a study proceed without consent and without the waiver "
     "conditions ever being tested. Small cohorts, rare diagnoses and narrow geography make re-identification easier.\n\n"
     "Policy: TCPS 2 Ch. 5 §A (pp. 79-80) and Glossary (pp. 271-272); Art. 5.5A, 5.5B; PHIPA; UHN Privacy and Access "
     "Policy 1.40.007.\n\n"
     "Consult Data Services, who build the extract and can state key custody and the field list, and the Privacy "
     "Office when data arrives directly identifying.",
     ["identifiability/arrival", "identifiability/keyHolder", "identifiability/fields", "identifiability/recognizable",
      "identifiability/lessIdentifiable", "identifiability/lessIdentifiableWhy", "variables"]),
    ("waiver", "Waiver of consent",
     "Every condition of a waiver is mandatory: a study approved on partial satisfaction is not approved. Use without a "
     "lawful basis exposes the institution and costs public trust.\n\n"
     "Look at: whether each condition is argued, not just asserted; inconvenience alone is not a reason. Art. 3.7A does "
     "not apply to secondary use.\n\n"
     "Policy: TCPS 2 Art. 5.5A (p. 88), six conditions; Art. 5.5B (p. 90); PHIPA s. 44.\n\n"
     "Only the REB can grant a waiver.",
     ["consent/sought", "consent/priorConsent", "consent/priorConsentCapcr", "consent/waiver/whyIdentifiable",
      "consent/waiver/welfare", "consent/waiver/impracticable", "priorConsentForm"]),
    ("authority", "Authority for the disclosure",
     "Data released without the custodian's authorization, or used beyond the conditions the custodian attached. The "
     "REB cannot approve what the custodian has forbidden.\n\n"
     "Policy: TCPS 2 Art. 5.5A(f) (pp. 88-89); PHIPA; UHN Privacy and Access Policy 1.40.007; data sharing agreement "
     "requirements.\n\n"
     "Consult Data Services and the Privacy Office, and Legal when an external agreement is involved.",
     ["access/custodian", "access/custodianApproval", "access/authority", "dsa"]),
    ("linkage", "Re-identification through linkage",
     "Linking datasets can make identifiable what was not identifiable in either. Linkage needs approval before it is "
     "done: approval after the merge is not a remedy.\n\n"
     "Look at: whether the safeguards are proportionate to how likely re-identification is, and how bad it would be, "
     "across collection, use, linkage, analysis, storage, sharing and dissemination.\n\n"
     "Policy: TCPS 2 Art. 5.7 (pp. 91-92); Art. 2.4 (p. 19); Art. 5.3; PHIPA.\n\n"
     "Consult Data Services, who perform the linkage, and the Privacy Office for an external dataset.",
     ["identifiability/linkage", "identifiability/linkageRisk", "identifiability/reidentification", "protocol"]),
    ("indigenous", "First Nations, Inuit and Métis data governance",
     "Secondary use of data identifiable as coming from an Indigenous community needs engagement before it begins. This "
     "is about community authority over data, not individual vulnerability. Geography alone can trigger it.\n\n"
     "Policy: TCPS 2 Ch. 9, Art. 9.20-9.22 (pp. 179-180); OCAP principles; UHN Indigenous Health Policy.\n\n"
     "Consult the Indigenous health and community partnership function.",
     ["sensitivity/indigenous", "sensitivity/community/covered", "sensitivity/community/engaged",
      "sensitivity/community/engagedOutcome"]),
    ("sensitive", "Sensitive information and vulnerable populations",
     "Stigma, discrimination, and consequences for employment, insurance, immigration or legal status, which the "
     "applicant is often not the right person to recognize.\n\n"
     "Policy: TCPS 2 Art. 5.5A (p. 89); Ch. 4 (p. 67); PHIPA; UHN Privacy and Access Policy 1.40.007.",
     ["sensitivity/categories", "sensitivity/input", "sensitivity/inputOutcome", "protocol"]),
    ("transfer", "Data leaving institutional control",
     "Health information going to a partner, a service provider, an automated or AI processing tool, or outside "
     "Canada, without that transfer being assessed. Applicants often don't see transcription, statistical consultants "
     "or document processing as a transfer at all. Destination and safeguard are what this turns on.\n\n"
     "Policy: TCPS 2 Art. 5.3 (pp. 85-86), 5.4 (p. 87); PHIPA; UHN Information Security Policy 1.40.028; UHN Cloud "
     "Security Standard; PIA and TRA requirements.\n\n"
     "Consult Digital (demand intake), the AI Hub for automated processing, the Privacy Office for a PIA and Security "
     "for a TRA.",
     ["handling/leaving/leaves", "handling/leaving/destination", "handling/leaving/anonymizedOut",
      "handling/leaving/otherUses", "digital/ai/used", "digital/ai/where", "dsa"]),
    ("safeguards", "Safeguards over the data's life",
     "A breach, unauthorized access, or no way to show proper handling on audit. Data kept without limit, or destroyed "
     "without a record.\n\n"
     "Policy: TCPS 2 Art. 5.1-5.4; PHIPA; UHN Records Policy 1.30.007; UHN Information Security Policy 1.40.028.\n\n"
     "Consult the Privacy Office for a PIA, Security for a TRA, and Records Management for any deviation from 1.30.007.",
     ["handling/storage/location", "handling/storage/access", "handling/storage/policyDeviation",
      "handling/storage/retention", "handling/storage/retentionPeriod", "handling/storage/breachHarm",
      "handling/storage/pia", "handling/storage/disclosure"]),
    ("rigour", "Scientific rigour",
     "Research waste and harm at the population level when the question, cohort or variables don't fit.\n\n"
     "Look at: whether the inclusion and exclusion criteria suit the question; whether identifiable information is "
     "essential.\n\n"
     "Policy: TCPS 2 Art. 4.1 (p. 67); Art. 2.7 (p. 21); Art. 5.5A(a) (p. 88).",
     ["classification/question", "classification/cohort/population", "classification/cohort/records",
      "classification/cohort/minimum", "classification/cohort/exclusions", "classification/cohort/exclusionsWhy",
      "protocol"]),
    ("equity", "Inclusion, exclusion and equity",
     "Groups included or left out for reasons unrelated to the question, by language, age, race or ethnicity, sex or "
     "gender, which can perpetuate harm and bias the results.\n\n"
     "Look at: whether the criteria could reproduce disparities in who takes part in research, and what that means for "
     "validity.\n\n"
     "Policy: TCPS 2 Art. 4.1, 4.7.\n\n"
     "Consult Research IDEA and IHP.",
     ["classification/cohort/exclusions", "classification/cohort/exclusionsWhy", "sensitivity/equity"]),
    ("reporting", "Identification through results",
     "People or small groups identifiable in published results, even from an aggregate analysis.\n\n"
     "Policy: TCPS 2 Art. 5.3 (p. 86); Art. 5.2 (p. 84).",
     ["handling/results/reporting", "handling/results/threshold"]),
    ("incidental", "Incidental findings",
     "A clinically actionable finding with no consent relationship to disclose it through, or a worrying pattern with "
     "no plan for what happens next.\n\n"
     "Policy: TCPS 2 Art. 3.4 (p. 41); Art. 5.6 (p. 90).\n\n"
     "Clinical services need to be involved where actionable findings are foreseeable.",
     ["handling/results/actionable", "handling/results/incidental"]),
    ("terminology", "Terminology",
     "Terms used loosely can describe the methods inaccurately and mislead the review.\n\n"
     "Policy: TCPS 2 Art. 6.11; Art. 2.7.",
     ["team/glossary", "classification/design", "protocol"]),
    ("conflict", "Conflict of interest",
     "Undue influence, compromised privacy or confidentiality, or bias in decisions that affect participants or the "
     "study's integrity.\n\n"
     "Look at: whether the conflict is real, potential or perceived, and whether it could affect welfare or integrity.\n\n"
     "Policy: TCPS 2 Art. 7.4; Art. 3.2(e).",
     ["team/conflict", "team/conflictDetails", "team/rmp", "study/sponsor"]),
    ("dateRange", "Extending the collection period",
     "A date range reaching into the recent past may use data beyond what the waiver was granted for: some of those "
     "people could still be asked.\n\n"
     "Look at: whether the end date is far enough in the past; whether patients are still being seen.\n\n"
     "Policy: TCPS 2 Ch. 1, Ch. 3.",
     ["classification/cohort/startDate", "classification/cohort/endDate", "classification/cohort/dateRationale"]),
    ("ai", "AI and the risks above",
     "Studies building AI tools, and studies using AI to carry out research, sharpen every risk above. TCPS 2 has not "
     "caught up, and guidance for REBs is thin.\n\n"
     "Consult the AI Hub and Digital.",
     ["digital/ai/used", "digital/ai/details", "digital/ai/where", "digital/ai/training", "digital/ai/phase",
      "digital/ai/governance", "protocol"]),
]

DIGITAL_TOPICS = [
    ("hosted", "UHN-hosted services and data staying internal",
     "Data exposure and cyber risk from the services the study runs on, and whether data stays within UHN.\n\n"
     "An integration with an external tool needs a Digital Demand.",
     ["digital/uhnHosted", "digital/integration", "handling/leaving/leaves"]),
    ("externalSoftware", "External software and sponsors",
     "Software hosted outside UHN that patient data goes into. Academic sponsors' agreements need security language "
     "added; industry sponsors outside the large pharmaceutical companies list need a manual check.\n\n"
     "Look at: the software and its license holder, and decide whether to approve.",
     ["digital/external", "digital/externalTool/patientData", "study/sponsor", "study/sponsorName",
      "digital/externalTool/bigPharma", "digital/externalTool/software", "digital/externalTool/url",
      "digital/externalTool/license"]),
    ("data", "Data put into the tools",
     "Patient data, or data entered by participants, needs a Digital Demand.",
     ["digital/input/kind", "digital/input/who"]),
    ("network", "Devices and the UHN network",
     "Anything set up on the UHN network, and sponsor devices that need internet access.",
     ["digital/build", "digital/onNetwork", "digital/alreadyOnNetwork", "digital/externalTool/sponsorDevice"]),
    ("reuse", "Tools approved before",
     "A tool approved for another study may be used differently here: new features, workflow, transfers or access.",
     ["digital/previouslyApproved", "digital/different", "digital/usage"]),
    ("policy", "Storage, transport and destruction",
     "Deviations from the policies on storage, transport and destruction need Privacy's view.",
     ["handling/storage/policyDeviation", "handling/storage/location", "handling/storage/retention"]),
    ("ai", "Artificial intelligence",
     "Generative AI, NLP and other AI tools, and above all the study's data used to train or refine a model, which "
     "needs a Digital Demand and the AI Hub intake.",
     ["digital/ai/used", "digital/ai/details", "digital/ai/where", "digital/ai/training"]),
]

APPROVALS = [
    ("reb", "Research ethics", "reb-reviewers",
     "The Research Ethics Board reviews every study.", None, REB_TOPICS),
    ("digitalReview", "Digital review", "digital-reviewers",
     "UHN Digital reviews the software, services and data flows the study relies on.", None, DIGITAL_TOPICS),
    ("deptHead", "Department head", "department-heads",
     "The head of the department or division confirms the study fits and can be supported.", None, [
         ("awareness", "Leadership awareness", "What the department is taking on, and with whom.",
          ["study/pi", "study/summary", "study/department", "classification/sources/dataSources"]),
         ("standing", "Investigator standing and scope",
          "Look at: the investigator's appointment status, whether they are in good standing, and whether the study "
          "falls within their expertise and responsibilities.", ["study/pi", "team/gcpDate"]),
         ("resources", "Resources and feasibility", "What the study needs from the department.",
          ["resources/funding", "resources/staff", "resources/extraction", "resources/analyst", "resources/other"]),
         ("landscape", "Other research and the participants",
          "Overlapping studies, datasets and objectives, and chances to collaborate.",
          ["resources/similar", "resources/overlap", "resources/collaboration"]),
         ("conflict", "Conflict of interest", "Whether a relationship management plan is in place and mitigations are "
          "documented.", ["team/conflict", "team/conflictDetails", "team/rmp"]),
     ]),
    ("cta", "Agreements", "cta-reviewers",
     "Clinical Trial Agreements reviews the agreements with any outside organization.",
     ("agreements/involved", "equals", True), [
         ("external", "Compliance of external involvement",
          "Who is involved from outside UHN, what flows where, and what agreements cover it.",
          ["agreements/organizations/names", "agreements/organizations/accessUhnData", "agreements/organizations/flow",
           "agreements/organizations/patientLevel", "agreements/organizations/stores",
           "agreements/organizations/jurisdiction", "agreements/organizations/funds", "agreements/organizations/existing",
           "agreements/organizations/related", "handling/leaving/leaves", "study/sponsor", "classification/design",
           "dsa"]),
     ]),
    ("lmp", "Laboratory Medicine", "lmp-reviewers",
     "The Laboratory Medicine Program confirms it can extract the data asked for.",
     ("lab/needed", "equals", True), [
         ("extraction", "Data availability and staff to pull it", "Whether the divisions can provide what is asked, "
          "and when.",
          ["lab/needed", "lab/request/divisions", "lab/request/population", "lab/request/parameters",
           "lab/request/identifiers", "lab/request/contactPerson", "lab/request/method", "lab/request/details"]),
     ]),
    ("jdmi", "Medical Imaging", "jdmi-reviewers",
     "The Joint Department of Medical Imaging confirms it can provide the images asked for.",
     ("imaging/needed", "equals", True), [
         ("effort", "Staff to pull the images", "How many images, which ones, how anonymized and by when.",
          ["imaging/request/parameters", "imaging/request/count", "imaging/request/deadline",
           "imaging/request/anonymization"]),
         ("privacy", "Patient privacy", "How images are transferred, where they go and who sees them.",
          ["imaging/request/transfer", "imaging/request/access", "imaging/request/anonymization"]),
         ("commercial", "Research or commercial venture", "Any relationship between the investigator and an outside "
          "company.", ["imaging/request/company", "imaging/request/count"]),
         ("availability", "Data availability", "Nothing older than 2009 exists.", ["imaging/request/period"]),
     ]),
    ("crdac", "Princess Margaret data", "crdac-reviewers",
     "The Clinical Research Data Access Committee reviews uses of Princess Margaret oncology data.",
     ("oncology/pmData", "equals", True), [
         ("use", "Use and interpretation of oncology data",
          "Appropriate use, interpretation and context of clinical oncology data, and collaboration with the disease "
          "site.", ["oncology/pmData", "oncology/sites", "oncology/pmCoInvestigator", "study/pi"]),
         ("secondary", "Secondary use of research data", "Data from an earlier trial or study at UHN.",
          ["oncology/priorTrial"]),
     ]),
    ("rqi", "Research quality", "rqi-reviewers",
     "Research Quality and Integrity checks the principal investigator's training.", None, [
         ("gcp", "Investigator training", "Good Clinical Practice training, and when it was completed.",
          ["study/pi", "team/gcpDate"]),
     ]),
    ("healthProfessions", "Health Professions", "health-professions",
     "Health Professions is made aware of studies involving health professionals. Acknowledging is enough.",
     ("team/healthProfessionals", "equals", True), [
         ("reidentification", "Re-identification of health professionals",
          "Health professionals taking part, and whether the data identifies them.",
          ["team/healthProfessionals", "team/hpRoles", "team/hpIdentifiable"]),
     ]),
]

# ---------------------------------------------------------------------------------------------------------

KINDS = {
    "yesno": {"dataType": "boolean"},
    "one": {"dataType": "text"},
    "many": {"dataType": "text", "maxAnswers": 0},
    "text": {"dataType": "text"},
    "long": {"dataType": "text", "displayMode": "multiline"},
    "date": {"dataType": "date"},
    "number": {"dataType": "long", "minValue": 0},
}

paths = {}


def ident(path):
    return str(uuid.uuid5(NS, path))


def condition(when):
    path, comparator, value = when
    return {
        "jcr:primaryType": "cond:SingleCondition",
        "comparator": comparator,
        "operandA": {"jcr:primaryType": "cond:ConditionOperand", "source": "answer", "value": [path]},
        "operandB": {"jcr:primaryType": "cond:ConditionOperand", "value": [value]},
    }


def build_items(prefix, items):
    out = {}
    for item in items:
        if "section" in item:
            path = f"{prefix}/{item['section']}"
            node = {"jcr:primaryType": "sch:Section", "title": item["title"]}
            if item.get("description"):
                node["description"] = item["description"]
            node.update(build_items(path, item["items"]))
            if item.get("when"):
                node["cond:condition"] = condition(item["when"])
            out[item["section"]] = node
            continue
        path = f"{prefix}/{item['name']}"
        paths[path] = ident(path)
        node = {"jcr:primaryType": "sch:Question",
                "text": item["text"], **KINDS[item["kind"]]}
        node["minAnswers"] = 1 if item.get("required", True) else 0
        if item.get("description"):
            node["description"] = item["description"]
        for extra in ("listing", "routing"):
            if item.get(extra):
                node[extra] = item[extra]
        for value, label in item["options"]:
            node[value] = {"jcr:primaryType": "sch:AnswerOption", "value": value, "label": label}
        if item.get("when"):
            node["cond:condition"] = condition(item["when"])
        out[item["name"]] = node
    return out


def document(name, label, description, required, when):
    node = {"jcr:primaryType": "sch:DocumentRequirement", "label": label,
            "description": description, "required": required, "acceptedFileTypes": ["application/pdf"]}
    if when:
        node["cond:condition"] = condition(when)
    return node


def build():
    version = {
        "jcr:primaryType": "sch:SchemaVersion",
        "version": "1.0",
        "tags": ["active"],
        "jcr:reference:workflow": "/Workflows/retrospectiveStudy/v1",
        "description": "Ask for institutional authorization of a study that uses data already collected: chart "
                       "reviews, registry and administrative data analyses, prediction models built on records.",
    }
    # The protocol first: reviewers start from it
    for name, label, description, required, when in DOCUMENTS[:1]:
        paths[name] = ident(name)
        version[name] = document(name, label, description, required, when)
    for name, label, description, items in FORMS:
        node = {"jcr:primaryType": "sch:FormRequirement", "label": label, "description": description}
        if name in EVERYONE:
            # PRETOTYPE: every reviewer reads it, whichever groups' risks also draw on it
            node["audience"] = "everyone"
        node.update(build_items(name, items))
        version[name] = node
    for name, label, description, required, when in DOCUMENTS[1:]:
        paths[name] = ident(name)
        version[name] = document(name, label, description, required, when)
    for name, label, group, description, when, topics in APPROVALS:
        node = {"jcr:primaryType": "sch:ApprovalRequirement", "label": label, "description": description,
                "approverGroup": group}
        if when:
            node["cond:condition"] = condition(when)
        for topic, text, topic_description, sources in topics:
            missing = [s for s in sources if s not in paths]
            if missing:
                raise SystemExit(f"{name}/{topic}: unknown sources {missing}")
            node[topic] = {"jcr:primaryType": "sch:Topic", "text": text, "description": topic_description,
                           "jcr:reference:sources": [f"/Schemas/retrospectiveStudy/v1/{s}" for s in sources]}
        version[name] = node
    return {"jcr:primaryType": "sch:Schema", "title": "Retrospective data study", "v1": version}


if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(build(), indent=2, ensure_ascii=False) + "\n")
    print(f"{len([p for p in paths if '/' in p])} questions, {len(DOCUMENTS)} documents, {len(APPROVALS)} approvals")


def outline():
    """The schema as plain text, for whoever writes a study's answers."""
    lines = []

    def walk(prefix, items, depth):
        for item in items:
            pad = "  " * depth
            if "section" in item:
                when = f"  [only when {item['when'][0]} {item['when'][1]} {item['when'][2]!r}]" if item.get("when") else ""
                lines.append(f"{pad}## {item['title']}{when}")
                walk(f"{prefix}/{item['section']}", item["items"], depth + 1)
                continue
            path = f"{prefix}/{item['name']}"
            when = f"  [only when {item['when'][0]} {item['when'][1]} {item['when'][2]!r}]" if item.get("when") else ""
            kind = {"yesno": "true/false", "one": "ONE of", "many": "LIST of", "text": "short text", "long": "long text",
                    "date": "date YYYY-MM-DD", "number": "whole number"}[item["kind"]]
            opts = (" " + ", ".join(f"{v} ({label})" for v, label in item["options"])) if item["options"] else ""
            optional = " (optional)" if not item.get("required", True) else ""
            lines.append(f"{pad}- {path}: {item['text']}  -> {kind}{opts}{optional}{when}")
    for name, label, _, items in FORMS:
        lines.append(f"\n# {label}")
        walk(name, items, 0)
    lines.append("\n# Documents")
    for name, label, description, _, when in DOCUMENTS:
        lines.append(f"- {name}: {label}. {description}" + (f"  [only when {when[0]} {when[1]} {when[2]!r}]" if when else ""))
    lines.append("\n# Approvals and their risks (topics)")
    for name, label, group, _, when, topics in APPROVALS:
        lines.append(f"\n## {name}: {label} ({group})" + (f"  [only when {when[0]} {when[1]} {when[2]!r}]" if when else ""))
        for topic, text, description, sources in topics:
            lines.append(f"- {name}/{topic}: {text}. {' '.join(description.split())}  Sources: {', '.join(sources)}")
    return "\n".join(lines) + "\n"
