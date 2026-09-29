package com.tailtopia.content.autocomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.social.read.UserHideRelationReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class VirtualIdentityPickerTest {

    private static final long AUTHOR = 100L;

    private final UserHideRelationReader hides = mock(UserHideRelationReader.class);
    /** 恒取第一个：让「落在哪一档」可断言。 */
    private final VirtualIdentityPicker picker = new VirtualIdentityPicker(hides, bound -> 0);

    private static User account(long id, String species) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getAccountSpecies()).thenReturn(species);
        return u;
    }

    @Test
    void prefersSameSpecies() {
        User general = account(1, "GENERAL");
        User cat = account(2, "CAT");
        User dog = account(3, "DOG");
        assertThat(picker.pick(List.of(general, cat, dog), AUTHOR, "DOG")).isSameAs(dog);
    }

    @Test
    void fallsBackToGeneralWhenNoSameSpecies() {
        User cat = account(2, "CAT");
        User general = account(1, "GENERAL");
        assertThat(picker.pick(List.of(cat, general), AUTHOR, "DOG")).isSameAs(general);
    }

    @Test
    void nullAccountSpeciesReadsAsGeneral() {
        User cat = account(2, "CAT");
        User legacy = account(1, null);
        assertThat(picker.pick(List.of(cat, legacy), AUTHOR, "DOG")).isSameAs(legacy);
    }

    @Test
    void fallsBackToAnyoneWhenNoSpeciesMatchAndNoGeneral() {
        User cat = account(2, "CAT");
        assertThat(picker.pick(List.of(cat), AUTHOR, "DOG")).isSameAs(cat);
    }

    @Test
    void generalPostUsesGeneralAccountsFirst() {
        User cat = account(2, "CAT");
        User general = account(1, "GENERAL");
        assertThat(picker.pick(List.of(cat, general), AUTHOR, "GENERAL")).isSameAs(general);
    }

    @Test
    void skipsAccountsHiddenByAuthor() {
        User dog1 = account(3, "DOG");
        User dog2 = account(4, "DOG");
        when(hides.isHidden(AUTHOR, 3L)).thenReturn(true);
        assertThat(picker.pick(List.of(dog1, dog2), AUTHOR, "DOG")).isSameAs(dog2);
    }

    @Test
    void returnsNullWhenEveryoneIsHidden() {
        User dog = account(3, "DOG");
        when(hides.isHidden(AUTHOR, 3L)).thenReturn(true);
        assertThat(picker.pick(List.of(dog), AUTHOR, "DOG")).isNull();
    }

    @Test
    void neverPicksThePostAuthorThemself() {
        User self = account(AUTHOR, "DOG");
        assertThat(picker.pick(List.of(self), AUTHOR, "DOG")).isNull();
    }
}
