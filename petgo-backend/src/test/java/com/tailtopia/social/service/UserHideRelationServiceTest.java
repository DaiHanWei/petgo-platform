package com.tailtopia.social.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.social.domain.HideSource;
import com.tailtopia.social.repository.UserHideRelationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * L0：拉黑联动（V1.4.2 social-topic · Story 1.1 · AC6 · D-11）—— {@code block} 同事务先取消关注、再删追更；
 * {@code hideByReport} / {@code unblock} 不碰追更。
 */
class UserHideRelationServiceTest {

    private static final long HOLDER = 1L;
    private static final long TARGET = 2L;

    private final UserHideRelationRepository relations = mock(UserHideRelationRepository.class);
    private final AccountQueryService accounts = mock(AccountQueryService.class);
    private final FollowService followService = mock(FollowService.class);
    private final PetFollowService petFollowService = mock(PetFollowService.class);
    private final UserHideRelationService service =
            new UserHideRelationService(relations, accounts, followService, petFollowService);

    @BeforeEach
    void setUp() {
        when(accounts.findUserById(TARGET)).thenReturn(Optional.of(mock(User.class)));
    }

    @Test
    void blockRemovesFollowThenPetFollowsInOrder() {
        service.block(HOLDER, TARGET);

        InOrder order = inOrder(relations, followService, petFollowService);
        order.verify(relations).insertIfAbsent(HOLDER, TARGET, HideSource.BLOCK.name());
        order.verify(followService).removeOnBlock(HOLDER, TARGET);
        order.verify(petFollowService).removeOnBlock(HOLDER, TARGET);
        // 只删 holder → target 一个方向
        verify(petFollowService, never()).removeOnBlock(TARGET, HOLDER);
    }

    /** 重复拉黑照常执行一次联动（删 0 行即无副作用）。 */
    @Test
    void repeatedBlockStillCallsPetFollowRemoval() {
        service.block(HOLDER, TARGET);
        service.block(HOLDER, TARGET);

        verify(petFollowService, org.mockito.Mockito.times(2)).removeOnBlock(HOLDER, TARGET);
    }

    @Test
    void hideByReportDoesNotTouchPetFollows() {
        service.hideByReport(HOLDER, TARGET);

        verifyNoInteractions(petFollowService);
        verify(followService, never()).removeOnBlock(anyLong(), anyLong());
    }

    @Test
    void unblockDoesNotTouchPetFollows() {
        service.unblock(HOLDER, TARGET);

        verifyNoInteractions(petFollowService);
        verify(followService, never()).removeOnBlock(anyLong(), anyLong());
    }
}
