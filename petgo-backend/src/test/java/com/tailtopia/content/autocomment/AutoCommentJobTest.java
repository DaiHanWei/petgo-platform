package com.tailtopia.content.autocomment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.ai.PetCommentGenerator;
import org.junit.jupiter.api.Test;

class AutoCommentJobTest {

    private final AutoCommentService service = mock(AutoCommentService.class);
    private final PetCommentGenerator generator = mock(PetCommentGenerator.class);
    private final AutoCommentProperties props = new AutoCommentProperties();
    private final AutoCommentJob job = new AutoCommentJob(service, props, generator);

    @Test
    void enabledByDefault_andSchedulesAt1030And2030() {
        AutoCommentProperties defaults = new AutoCommentProperties();
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.getMorningCron()).isEqualTo("0 30 10 * * *");
        assertThat(defaults.getEveningCron()).isEqualTo("0 30 16 * * *");
    }

    @Test
    void disabled_doesNothing() {
        props.setEnabled(false);
        when(generator.live()).thenReturn(true);
        job.morning();
        verify(service, never()).runOnce();
    }

    @Test
    void stubAi_neverPostsEvenWhenEnabled() {
        props.setEnabled(true);
        when(generator.live()).thenReturn(false);
        job.evening();
        verify(service, never()).runOnce();
    }

    @Test
    void enabledWithLiveAi_runs() {
        props.setEnabled(true);
        when(generator.live()).thenReturn(true);
        job.morning();
        verify(service).runOnce();
    }
}
