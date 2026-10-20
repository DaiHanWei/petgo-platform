package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClientAppVersionTest {

    @Test
    void multiPetSupportedFrom140IncludingSuffixes() {
        assertThat(ClientAppVersion.supportsMultiPet("1.4.0")).isTrue();
        assertThat(ClientAppVersion.supportsMultiPet("1.4.0-stag")).isTrue();
        assertThat(ClientAppVersion.supportsMultiPet("1.4.12")).isTrue();
        assertThat(ClientAppVersion.supportsMultiPet("1.10.0")).isTrue();
        assertThat(ClientAppVersion.supportsMultiPet("2.0.0")).isTrue();
    }

    @Test
    void olderMissingOrGarbageIsLegacy() {
        assertThat(ClientAppVersion.supportsMultiPet(null)).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("")).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("1.3.4")).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("1.3.99")).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("0.9.0")).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("1.4")).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("abc")).isFalse();
        assertThat(ClientAppVersion.supportsMultiPet("99999999999.0.0")).isFalse();
    }
}
