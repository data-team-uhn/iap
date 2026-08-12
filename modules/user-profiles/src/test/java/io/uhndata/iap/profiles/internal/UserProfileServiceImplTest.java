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
package io.uhndata.iap.profiles.internal;

import java.lang.annotation.Annotation;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.profiles.api.ProfileField;
import io.uhndata.iap.profiles.api.ProfileProjection;
import io.uhndata.iap.profiles.api.Requester;
import io.uhndata.iap.profiles.api.UpdateOutcome;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition;
import io.uhndata.iap.profiles.models.ProfileFieldsHomepage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserProfileServiceImpl}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class UserProfileServiceImplTest
{
    private static final String RESOURCE_TYPE = "sling:resourceType";

    private static final String ME = "jdoe";

    private static final String SOMEBODY_ELSE = "asmith";

    private static final String ADMIN_GROUP = "iap-user-administrators";

    private static final String EMAIL = "email";

    private static final String PROFILE_EMAIL = "profile/email";

    private final SlingContext context = new SlingContext();

    private UserProfileServiceImpl service;

    private Authorizable account;

    private UserManager users;

    private ValueFactory values;

    private Map<String, String[]> stored;

    private JackrabbitSession session;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, ProfileFieldsHomepage.class, ProfileFieldDefinition.class);
        this.context.create().resource(ProfileFieldsHomepage.PATH, Map.of(
            RESOURCE_TYPE, ProfileFieldsHomepage.RESOURCE_TYPE, "title", "Fields", "description", "What we record"));
        this.stored = new HashMap<>();

        this.account = account(ME, null);
        this.users = mock(UserManager.class);
        when(this.users.getAuthorizable(anyString())).thenReturn(null);
        when(this.users.getAuthorizable(ME)).thenReturn(this.account);
        this.values = mock(ValueFactory.class);
        when(this.values.createValue(anyString())).thenAnswer(call -> value(call.getArgument(0)));
        this.session = mock(JackrabbitSession.class);
        when(this.session.getUserManager()).thenReturn(this.users);
        when(this.session.getValueFactory()).thenReturn(this.values);
        this.service = new UserProfileServiceImpl(accounts(this.session, false));
        this.service.activate(config());
    }

    /**
     * Account access over a resolver whose session is the given one, so that everything in {@link Accounts} runs for
     * real against Jackrabbit's interfaces rather than being stood in for.
     *
     * @param jcrSession what the resolver adapts to
     * @param sessionless whether the resolver should adapt to nothing at all
     * @return account access
     */
    private Accounts accounts(final Session jcrSession, final boolean sessionless)
    {
        return new Accounts(new TestResolverFactory(this.context.resourceResolver(), jcrSession, sessionless));
    }

    /** The component's configuration, as OSGi would hand it over. */
    private static UserProfilesConfig config()
    {
        return new UserProfilesConfig()
        {
            @Override
            public Class<? extends Annotation> annotationType()
            {
                return UserProfilesConfig.class;
            }

            @Override
            public String[] administratorPrincipals()
            {
                return new String[] { ADMIN_GROUP };
            }
        };
    }

    private Value value(final String text) throws RepositoryException
    {
        final Value created = mock(Value.class);
        when(created.getString()).thenReturn(text);
        return created;
    }

    private Authorizable account(final String id, final String externalId) throws RepositoryException
    {
        final Authorizable created = mock(Authorizable.class);
        when(created.getID()).thenReturn(id);
        when(created.isGroup()).thenReturn(false);
        final Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(id);
        when(created.getPrincipal()).thenReturn(principal);
        when(created.memberOf()).thenAnswer(call -> List.<Group>of().iterator());
        when(created.getProperty(anyString())).thenAnswer(call -> {
            final String[] recorded = this.stored.get(call.<String>getArgument(0));
            if (recorded == null) {
                return null;
            }
            final List<Value> asValues = new ArrayList<>();
            for (final String each : recorded) {
                asValues.add(value(each));
            }
            return asValues.toArray(Value[]::new);
        });
        if (externalId != null) {
            this.stored.put(AccountFacts.REP_EXTERNAL_ID, new String[] { externalId });
        }
        return created;
    }

    private void field(final String nodeName, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(RESOURCE_TYPE, ProfileFieldDefinition.RESOURCE_TYPE);
        this.context.create().resource(ProfileFieldsHomepage.PATH + "/" + nodeName, all);
    }

    private ProfileField projected(final ProfileProjection profile, final String name)
    {
        return profile.getFields().stream().filter(f -> name.equals(f.getName())).findFirst()
            .orElseThrow();
    }

    private Requester me()
    {
        return new Requester(ME);
    }

    private Requester administrator()
    {
        return new Requester(SOMEBODY_ELSE, Set.of(SOMEBODY_ELSE, ADMIN_GROUP));
    }

    @Test
    void hasNothingToSayAboutAnAccountThatDoesNotExist()
    {
        assertTrue(this.service.read("nobody", me()).isEmpty());
        assertTrue(this.service.update("nobody", me(), Map.of()).isEmpty());
    }

    @Test
    void hasNothingToSayAboutAGroup() throws RepositoryException
    {
        final Authorizable group = mock(Authorizable.class);
        when(group.isGroup()).thenReturn(true);
        when(this.users.getAuthorizable("reviewers")).thenReturn(group);

        assertTrue(this.service.read("reviewers", me()).isEmpty());
    }

    @Test
    void survivesAMissingServiceUser()
    {
        final UserProfileServiceImpl broken =
            new UserProfileServiceImpl(new Accounts(new TestResolverFactory(null)));
        broken.activate(config());

        assertTrue(broken.read(ME, me()).isEmpty());
        assertTrue(broken.update(ME, me(), Map.of()).isEmpty());
        assertTrue(broken.getValue(this.account, EMAIL).isEmpty());
    }

    @Test
    void survivesAnInstanceWithNoCatalogueAtAll() throws Exception
    {
        this.context.resourceResolver().delete(
            this.context.resourceResolver().getResource(ProfileFieldsHomepage.PATH));

        assertTrue(this.service.read(ME, me()).isEmpty());
        assertTrue(this.service.update(ME, me(), Map.of()).isEmpty());
        assertTrue(this.service.getValue(this.account, EMAIL).isEmpty());
    }

    @Test
    void survivesARepositoryThatWillNotAnswer() throws RepositoryException
    {
        when(this.users.getAuthorizable(ME)).thenThrow(new RepositoryException("no"));

        assertTrue(this.service.read(ME, me()).isEmpty());
        assertTrue(this.service.update(ME, me(), Map.of()).isEmpty());
    }

    @Test
    void describesAnEmptyProfileOnABarePlatform()
    {
        final ProfileProjection profile = this.service.read(ME, me()).orElseThrow();

        assertEquals(ME, profile.getAccountId());
        assertFalse(profile.isExternal());
        assertEquals(List.of(), profile.getFields());
        assertEquals(Set.of(ME), profile.getPrincipalNames());
    }

    @Test
    void readsWhatIsRecorded()
    {
        field(EMAIL, Map.of("readableBy", "authenticated"));
        this.stored.put(PROFILE_EMAIL, new String[] { "jdoe@example.org" });

        final ProfileField email = projected(this.service.read(ME, me()).orElseThrow(), EMAIL);

        assertTrue(email.isReadable());
        assertTrue(email.isEditable());
        assertEquals(List.of("jdoe@example.org"), email.getValues());
        assertEquals(ProfileField.Provenance.LOCAL, email.getProvenance());
    }

    @Test
    void withholdsAValueTheRequesterMayNotSee()
    {
        field("note", Map.of("readableBy", "admin"));
        this.stored.put("profile/note", new String[] { "a private remark" });

        final ProfileField note = projected(this.service.read(ME, me()).orElseThrow(), "note");

        assertFalse(note.isReadable());
        assertEquals(List.of(), note.getValues());
        // Described as if nothing were recorded: that something is would already be telling them something
        assertEquals(ProfileField.Provenance.UNSET, note.getProvenance());
    }

    @Test
    void letsAnAdministratorSeeWhatThePersonCannot()
    {
        field("note", Map.of("readableBy", "admin"));
        this.stored.put("profile/note", new String[] { "a private remark" });

        final ProfileField note = projected(this.service.read(ME, administrator()).orElseThrow(), "note");

        assertTrue(note.isReadable());
        assertEquals(List.of("a private remark"), note.getValues());
    }

    @Test
    void letsAPersonSeeTheirOwnSelfReadableFieldButNotSomebodyElses()
    {
        field("phone", Map.of("readableBy", "self"));

        assertTrue(projected(this.service.read(ME, me()).orElseThrow(), "phone").isReadable());
        assertFalse(projected(this.service.read(ME, new Requester(SOMEBODY_ELSE)).orElseThrow(), "phone")
            .isReadable());
    }

    @Test
    void treatsABrokenDefinitionAsNeitherReadableNorEditable()
    {
        field("broken", Map.of("dataType", "nonsense"));

        final ProfileField broken = projected(this.service.read(ME, me()).orElseThrow(), "broken");

        assertFalse(broken.isReadable());
        assertFalse(broken.isEditable());
    }

    @Test
    void reportsAnImportedFieldAsReadOnlyForASynchronizedAccount() throws RepositoryException
    {
        field(EMAIL, Map.of("idpClaim", EMAIL, "readableBy", "authenticated"));
        this.account = account(ME, "jdoe%3Bkc;keycloak");
        when(this.users.getAuthorizable(ME)).thenReturn(this.account);
        this.stored.put(PROFILE_EMAIL, new String[] { "jdoe@example.org" });

        final ProfileProjection profile = this.service.read(ME, me()).orElseThrow();
        final ProfileField email = projected(profile, EMAIL);

        assertTrue(profile.isExternal());
        assertEquals("keycloak", profile.getIdpName());
        assertTrue(email.isReadable());
        assertFalse(email.isEditable());
        assertEquals(ProfileField.Provenance.IDP, email.getProvenance());
    }

    @Test
    void reportsTheSameFieldAsEditableForALocalAccount()
    {
        // The whole point of deriving rather than storing "imported": one catalogue serves both kinds of account
        field(EMAIL, Map.of("idpClaim", EMAIL, "readableBy", "authenticated"));
        this.stored.put(PROFILE_EMAIL, new String[] { "jdoe@example.org" });

        final ProfileField email = projected(this.service.read(ME, me()).orElseThrow(), EMAIL);

        assertTrue(email.isEditable());
        assertEquals(ProfileField.Provenance.LOCAL, email.getProvenance());
    }

    @Test
    void treatsAnUnparseableExternalRecordAsExternalAnyway() throws RepositoryException
    {
        this.account = account(ME, "no-separator-here");
        when(this.users.getAuthorizable(ME)).thenReturn(this.account);
        field(EMAIL, Map.of("idpClaim", EMAIL));

        final ProfileProjection profile = this.service.read(ME, me()).orElseThrow();

        assertTrue(profile.isExternal());
        assertEquals("", profile.getIdpName());
        assertFalse(projected(profile, EMAIL).isEditable());
    }

    @Test
    void neverLetsAnybodyChangeASystemField()
    {
        field("computed", Map.of("system", true));
        this.stored.put("profile/computed", new String[] { "worked out by the platform" });

        assertFalse(projected(this.service.read(ME, administrator()).orElseThrow(), "computed").isEditable());
        assertEquals(ProfileField.Provenance.PLATFORM,
            projected(this.service.read(ME, administrator()).orElseThrow(), "computed").getProvenance());
    }

    @Test
    void listsEveryPrincipalTheAccountHolds() throws RepositoryException
    {
        final Group local = mock(Group.class);
        final Principal localPrincipal = mock(Principal.class);
        when(localPrincipal.getName()).thenReturn("iap-reviewers");
        when(local.getPrincipal()).thenReturn(localPrincipal);
        when(this.account.memberOf()).thenAnswer(call -> List.of(local).iterator());
        this.stored.put(AccountFacts.REP_EXTERNAL_PRINCIPAL_NAMES, new String[] { "reviewer" });

        // A local group and a dynamic identity provider role, in one list and indistinguishable, which is the point
        assertEquals(Set.of(ME, "iap-reviewers", "reviewer"),
            this.service.read(ME, me()).orElseThrow().getPrincipalNames());
    }

    @Test
    void readsOneValueWithoutCaringWhoIsAsking()
    {
        field("locale", Map.of("kind", "preference"));
        this.stored.put("preferences/locale", new String[] { "fr" });

        assertEquals(Optional.of("fr"), this.service.getValue(this.account, "locale"));
        assertTrue(this.service.getValue(this.account, "nothing-like-it").isEmpty());
    }

    @Test
    void hasNoValueForAFieldNothingIsRecordedIn()
    {
        field("locale", Map.of("kind", "preference"));

        assertTrue(this.service.getValue(this.account, "locale").isEmpty());
    }

    @Test
    void writesWhatIsAskedFor() throws RepositoryException
    {
        field(EMAIL, Map.of());

        final UpdateOutcome outcome =
            this.service.update(ME, me(), Map.of(EMAIL, new String[] { " jdoe@example.org " })).orElseThrow();

        assertFalse(outcome.isRefused());
        assertEquals(List.of(EMAIL), outcome.getChanged());
        verify(this.account).setProperty(anyString(), any(Value.class));
        verify(this.session).save();
    }

    @Test
    void writesNothingWhenNothingChanged() throws RepositoryException
    {
        field(EMAIL, Map.of());
        this.stored.put(PROFILE_EMAIL, new String[] { "jdoe@example.org" });

        final UpdateOutcome outcome =
            this.service.update(ME, me(), Map.of(EMAIL, new String[] { "jdoe@example.org" })).orElseThrow();

        assertEquals(List.of(), outcome.getChanged());
        verify(this.account, never()).setProperty(anyString(), any(Value.class));
        verify(this.session, never()).save();
    }

    @Test
    void writesEveryValueOfAMultiValuedField() throws RepositoryException
    {
        field("expertise", Map.of("multiple", true));

        this.service.update(ME, me(), Map.of("expertise", new String[] { "oncology", "genomics" })).orElseThrow();

        verify(this.account).setProperty(anyString(), any(Value[].class));
    }

    @Test
    void unsetsAFieldClearedOnTheForm() throws RepositoryException
    {
        field("phone", Map.of());
        this.stored.put("profile/phone", new String[] { "555" });

        final UpdateOutcome outcome =
            this.service.update(ME, me(), Map.of("phone", new String[] { "" })).orElseThrow();

        assertEquals(List.of("phone"), outcome.getChanged());
        verify(this.account).removeProperty("profile/phone");
    }

    @Test
    void refusesTheWholeRequestWhenAnythingIsWrong() throws RepositoryException
    {
        field(EMAIL, Map.of());
        field("age", Map.of("dataType", "long"));

        final UpdateOutcome outcome = this.service.update(ME, me(), Map.of(
            EMAIL, new String[] { "jdoe@example.org" },
            "age", new String[] { "not a number" })).orElseThrow();

        assertTrue(outcome.isRefused());
        assertEquals(List.of(), outcome.getChanged());
        assertTrue(outcome.getRefused().containsKey("age"));
        // The acceptable half is not written either: a profile half saved is worse than one turned down
        verify(this.account, never()).setProperty(anyString(), any(Value.class));
        verify(this.session, never()).save();
    }

    @Test
    void refusesAFieldTheInstanceDoesNotRecord()
    {
        final UpdateOutcome outcome =
            this.service.update(ME, me(), Map.of("invented", new String[] { "x" })).orElseThrow();

        assertEquals("this instance records no such thing", outcome.getRefused().get("invented"));
    }

    @Test
    void refusesSomebodyElsesProfileOutright()
    {
        field(EMAIL, Map.of());

        final UpdateOutcome outcome = this.service
            .update(ME, new Requester(SOMEBODY_ELSE), Map.of(EMAIL, new String[] { "x@example.org" })).orElseThrow();

        assertTrue(outcome.isForbidden());
        assertTrue(outcome.isRefused());
    }

    @Test
    void letsAnAdministratorChangeSomebodyElsesProfile()
    {
        field(EMAIL, Map.of());

        final UpdateOutcome outcome = this.service
            .update(ME, administrator(), Map.of(EMAIL, new String[] { "x@example.org" })).orElseThrow();

        assertFalse(outcome.isRefused());
    }

    @Test
    void letsTheSuperuserChangeAnybodysProfile()
    {
        field(EMAIL, Map.of());

        assertFalse(this.service.update(ME, new Requester("admin"), Map.of(EMAIL, new String[] { "x@example.org" }))
            .orElseThrow().isRefused());
    }

    @Test
    void refusesAnAdminOnlyFieldForThePersonThemselves()
    {
        field("verified", Map.of("writableBy", "admin"));

        assertEquals("only a user administrator may change this field",
            this.service.update(ME, me(), Map.of("verified", new String[] { "true" })).orElseThrow()
                .getRefused().get("verified"));
    }

    @Test
    void letsAnAdministratorChangeAnAdminOnlyField()
    {
        field("verified", Map.of("writableBy", "admin"));

        assertFalse(this.service.update(ME, administrator(), Map.of("verified", new String[] { "true" }))
            .orElseThrow().isRefused());
    }

    @Test
    void buildsItsOwnAccountAccessWhenActivatedByOsgi()
    {
        // The OSGi path: the no-argument constructor, and activation building the account access around the factory
        // that was injected into the field, rather than one handed to a constructor as the tests do
        final UserProfileServiceImpl injected = new UserProfileServiceImpl();

        assertDoesNotThrow(() -> injected.activate(config()));
    }

    @Test
    void refusesAFieldNobodyMayChange()
    {
        field("computed", Map.of("writableBy", "nobody"));

        assertEquals("nothing may change this field",
            this.service.update(ME, administrator(), Map.of("computed", new String[] { "x" })).orElseThrow()
                .getRefused().get("computed"));
    }

    @Test
    void refusesASystemFieldWithItsOwnReason()
    {
        field("computed", Map.of("system", true));

        assertEquals("this field is maintained by the platform",
            this.service.update(ME, administrator(), Map.of("computed", new String[] { "x" })).orElseThrow()
                .getRefused().get("computed"));
    }

    @Test
    void refusesAnImportedFieldWithAnExplanationOfWhereToChangeIt() throws RepositoryException
    {
        field(EMAIL, Map.of("idpClaim", EMAIL));
        this.account = account(ME, "jdoe;keycloak");
        when(this.users.getAuthorizable(ME)).thenReturn(this.account);

        assertTrue(this.service.update(ME, me(), Map.of(EMAIL, new String[] { "x@example.org" })).orElseThrow()
            .getRefused().get(EMAIL).contains("institutional account"));
    }

    @Test
    void refusesABrokenFieldSayingWhatIsWrongWithIt()
    {
        field("broken", Map.of("dataType", "nonsense"));

        assertTrue(this.service.update(ME, me(), Map.of("broken", new String[] { "x" })).orElseThrow()
            .getRefused().get("broken").contains("misconfigured"));
    }

    @Test
    void refusesMoreThanOneValueForASingleValuedField()
    {
        field(EMAIL, Map.of());

        assertEquals("only one value is accepted here",
            this.service.update(ME, me(), Map.of(EMAIL, new String[] { "a@b.c", "d@e.f" })).orElseThrow()
                .getRefused().get(EMAIL));
    }

    @Test
    void refusesAnEmptyValueForARequiredField()
    {
        field(EMAIL, Map.of("required", true));

        assertEquals("a value is required",
            this.service.update(ME, me(), Map.of(EMAIL, new String[] { "  " })).orElseThrow()
                .getRefused().get(EMAIL));
        assertEquals("a value is required",
            this.service.update(ME, me(), nullValued(EMAIL)).orElseThrow().getRefused().get(EMAIL));
    }

    /** A parameter map with no values at all for a field, which is what a form omitting a control produces. */
    private Map<String, String[]> nullValued(final String name)
    {
        final Map<String, String[]> asked = new HashMap<>();
        asked.put(name, null);
        return asked;
    }

    @Test
    void refusesAValueOutsideAClosedSet()
    {
        field("locale", Map.of("allowedValues", new String[] { "en", "fr" }));

        assertEquals("must be one of: en, fr",
            this.service.update(ME, me(), Map.of("locale", new String[] { "de" })).orElseThrow()
                .getRefused().get("locale"));
    }

    @Test
    void refusesAValueThatDoesNotMatchThePattern()
    {
        field(EMAIL, Map.of("pattern", ".+@.+"));

        assertEquals("is not in the expected format",
            this.service.update(ME, me(), Map.of(EMAIL, new String[] { "not-an-address" })).orElseThrow()
                .getRefused().get(EMAIL));
    }

    @Test
    void refusesValuesOfTheWrongType()
    {
        field("age", Map.of("dataType", "long"));
        field("score", Map.of("dataType", "double"));
        field("active", Map.of("dataType", "boolean"));
        field("since", Map.of("dataType", "date"));

        assertTrue(refusalFor("age", "x").contains("must be a long"));
        assertTrue(refusalFor("score", "x").contains("must be a double"));
        assertEquals("must be true or false", refusalFor("active", "yes"));
        assertTrue(refusalFor("since", "last Tuesday").contains("YYYY-MM-DD"));
    }

    private String refusalFor(final String name, final String value)
    {
        return this.service.update(ME, me(), Map.of(name, new String[] { value })).orElseThrow()
            .getRefused().get(name);
    }

    @Test
    void acceptsValuesOfTheRightType()
    {
        field("age", Map.of("dataType", "long"));
        field("score", Map.of("dataType", "double"));
        field("active", Map.of("dataType", "boolean"));
        field("since", Map.of("dataType", "date"));

        final UpdateOutcome outcome = this.service.update(ME, me(), Map.of(
            "age", new String[] { "42" },
            "score", new String[] { "1.5" },
            "active", new String[] { "false" },
            "since", new String[] { "2026-08-11" })).orElseThrow();

        assertFalse(outcome.isRefused());
        assertEquals(4, outcome.getChanged().size());
    }
}
