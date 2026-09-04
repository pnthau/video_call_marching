package com.example.videocall_marching_language.service;

import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.dto.MatchRequestDTO;
import com.example.videocall_marching_language.dto.MatchResultDTO;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.SessionStatus;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.IUserRepository;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.ILearningSessionService;
import com.example.videocall_marching_language.service.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatchMakingServiceTests {

    @Mock
    private IUserRepository userRepository;
    @Mock
    private ITagRepository tagRepository;
    @Mock
    private ILearningSessionService learningSessionService;
    @Mock
    private MatchingProperties matchingProperties;
    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;
    @Mock
    private TimeProvider timeProvider;

    private MatchMakingService matchMakingService;

    private Tag topicTag1;
    private Tag levelTagN5;
    private Tag activityTag1;
    private TagCategory topicCategory;
    private TagCategory levelCategory;
    private TagCategory activityCategory;

    @BeforeEach
    void setUp() {
        when(matchingProperties.getAdjacentLevelAfterSeconds()).thenReturn(120);
        when(matchingProperties.getMatchTimeoutSeconds()).thenReturn(600);

        matchMakingService = new MatchMakingService(
                userRepository, tagRepository, learningSessionService, matchingProperties, simpMessagingTemplate, timeProvider);

        topicCategory = TagCategory.builder().id(1L).name("Topic").type(TagCategoryType.TOPIC).active(true).build();
        levelCategory = TagCategory.builder().id(2L).name("Level").type(TagCategoryType.LEVEL).active(true).build();
        activityCategory = TagCategory.builder().id(3L).name("Activity").type(TagCategoryType.ACTIVITY).active(true).build();

        topicTag1 = Tag.builder().id(10L).tagCategory(topicCategory).name("Daily").build();
        levelTagN5 = Tag.builder().id(20L).tagCategory(levelCategory).name("N5").build();
        activityTag1 = Tag.builder().id(30L).tagCategory(activityCategory).name("Conversation").build();

        when(tagRepository.findSelectableById(10L)).thenReturn(Optional.of(topicTag1));
        when(tagRepository.findSelectableById(20L)).thenReturn(Optional.of(levelTagN5));
        when(tagRepository.findSelectableById(30L)).thenReturn(Optional.of(activityTag1));

        when(userRepository.findById(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return Optional.of(user(id, "user" + id));
        });
        when(timeProvider.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(learningSessionService.hasActiveSession(anyLong())).thenReturn(false);
        when(learningSessionService.createSession(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> LearningSession.builder()
                        .id(100L)
                        .channelName("room-test")
                        .levelSnapshot(JapaneseLevel.N5)
                        .tagSnapshot("test")
                        .status(SessionStatus.MATCHED)
                        .matchedAt(LocalDateTime.now())
                        .user1(inv.getArgument(0))
                        .user2(inv.getArgument(1))
                        .user1Uid(1)
                        .user2Uid(2)
                        .build());
    }

    private MatchRequestDTO basicRequest(Long userId) {
        return MatchRequestDTO.builder()
                .userId(userId)
                .topicTagId(10L)
                .levelTagId(20L)
                .activityTagId(30L)
                .level(JapaneseLevel.N5)
                .build();
    }

    @Test
    void sameLevelAndCommonTagShouldMatch() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(2L), "session2");

        ArgumentCaptor<User> user1Captor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<User> user2Captor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<JapaneseLevel> levelCaptor = ArgumentCaptor.forClass(JapaneseLevel.class);
        ArgumentCaptor<String> tagCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);

        verify(learningSessionService).createSession(user1Captor.capture(), user2Captor.capture(), levelCaptor.capture(), tagCaptor.capture(), channelCaptor.capture());
        assertEquals(1L, user1Captor.getValue().getId());
        assertEquals(2L, user2Captor.getValue().getId());
        assertEquals(JapaneseLevel.N5, levelCaptor.getValue());
        assertNotNull(tagCaptor.getValue());
        assertNotNull(channelCaptor.getValue());
        assertTrue(channelCaptor.getValue().startsWith("room_"));
    }

    @Test
    void noCommonTagShouldNotMatch() {
        Tag topicTag2 = Tag.builder().id(11L).tagCategory(topicCategory).name("Travel").build();
        Tag levelTagN4 = Tag.builder().id(21L).tagCategory(levelCategory).name("N4").build();
        Tag activityTag2 = Tag.builder().id(31L).tagCategory(activityCategory).name("Roleplay").build();
        when(tagRepository.findSelectableById(11L)).thenReturn(Optional.of(topicTag2));
        when(tagRepository.findSelectableById(21L)).thenReturn(Optional.of(levelTagN4));
        when(tagRepository.findSelectableById(31L)).thenReturn(Optional.of(activityTag2));

        MatchRequestDTO request1 = MatchRequestDTO.builder()
                .userId(1L)
                .topicTagId(10L)
                .levelTagId(20L)
                .activityTagId(30L)
                .level(JapaneseLevel.N5)
                .build();
        matchMakingService.joinQueue(request1, "session1");

        MatchRequestDTO request2 = MatchRequestDTO.builder()
                .userId(2L)
                .topicTagId(11L)
                .levelTagId(21L)
                .activityTagId(31L)
                .level(JapaneseLevel.N4)
                .build();
        matchMakingService.joinQueue(request2, "session2");

        verify(learningSessionService, never()).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void adjacentLevelExact120SecondsShouldMatch() {
        Tag levelTagN4 = Tag.builder().id(21L).tagCategory(levelCategory).name("N4").build();
        when(tagRepository.findSelectableById(21L)).thenReturn(Optional.of(levelTagN4));

        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");
        when(timeProvider.instant()).thenReturn(baseTime);

        matchMakingService.joinQueue(basicRequest(1L), "session1");

        when(timeProvider.instant()).thenReturn(baseTime.plusSeconds(120));

        MatchRequestDTO request2 = MatchRequestDTO.builder()
                .userId(2L)
                .topicTagId(10L)
                .levelTagId(21L)
                .activityTagId(30L)
                .level(JapaneseLevel.N4)
                .build();
        matchMakingService.joinQueue(request2, "session2");

        verify(learningSessionService).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void adjacentLevelBefore120SecondsShouldNotMatch() {
        Tag levelTagN4 = Tag.builder().id(21L).tagCategory(levelCategory).name("N4").build();
        when(tagRepository.findSelectableById(21L)).thenReturn(Optional.of(levelTagN4));

        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");
        when(timeProvider.instant()).thenReturn(baseTime);

        matchMakingService.joinQueue(basicRequest(1L), "session1");

        when(timeProvider.instant()).thenReturn(baseTime.plusSeconds(119));

        MatchRequestDTO request2 = MatchRequestDTO.builder()
                .userId(2L)
                .topicTagId(10L)
                .levelTagId(21L)
                .activityTagId(30L)
                .level(JapaneseLevel.N4)
                .build();
        matchMakingService.joinQueue(request2, "session2");

        verify(learningSessionService, never()).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void selectBestCandidatePrefersMostCommonTags() {
        // Test the comparator logic directly
        MatchingQueueEntry anchor = new MatchingQueueEntry(1L, "anchor", JapaneseLevel.N5, Set.of(10L, 20L, 30L), Instant.now(), "s1");

        MatchingQueueEntry candidate1 = new MatchingQueueEntry(2L, "c1", JapaneseLevel.N5, Set.of(10L, 20L, 31L), Instant.now().plusSeconds(1), "s2"); // 2 common
        MatchingQueueEntry candidate2 = new MatchingQueueEntry(3L, "c2", JapaneseLevel.N5, Set.of(10L, 20L, 30L), Instant.now().plusSeconds(2), "s3"); // 3 common

        List<MatchingQueueEntry> candidates = List.of(candidate1, candidate2);

        // Use reflection to test private method
        MatchingQueueEntry best = invokeSelectBestCandidate(anchor, candidates);

        assertEquals(3L, best.getUserId()); // candidate2 has 3 common tags
    }

    private MatchingQueueEntry invokeSelectBestCandidate(MatchingQueueEntry anchor, List<MatchingQueueEntry> candidates) {
        try {
            java.lang.reflect.Method method = MatchMakingService.class.getDeclaredMethod("selectBestCandidate", MatchingQueueEntry.class, List.class);
            method.setAccessible(true);
            return (MatchingQueueEntry) method.invoke(matchMakingService, anchor, candidates);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void ifSameCommonTagsOlderWaitTimePreferred() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(2L), "session2");
        matchMakingService.joinQueue(basicRequest(3L), "session3");

        ArgumentCaptor<User> user1Captor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<User> user2Captor = ArgumentCaptor.forClass(User.class);
        verify(learningSessionService).createSession(user1Captor.capture(), user2Captor.capture(), any(), any(), any());
        assertEquals(1L, user1Captor.getValue().getId());
        assertEquals(2L, user2Captor.getValue().getId());
    }

    @Test
    void ifStillTiedLowerUserIdPreferred() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(2L), "session2");
        matchMakingService.joinQueue(basicRequest(3L), "session3");

        ArgumentCaptor<User> user1Captor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<User> user2Captor = ArgumentCaptor.forClass(User.class);
        verify(learningSessionService).createSession(user1Captor.capture(), user2Captor.capture(), any(), any(), any());
        assertEquals(1L, user1Captor.getValue().getId());
        assertEquals(2L, user2Captor.getValue().getId());
    }

    @Test
    void noSelfMatch() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(1L), "session2");

        verify(learningSessionService, never()).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void noDuplicateQueueEntry() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(1L), "session2");

        verify(simpMessagingTemplate, times(2)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void userWithActiveSessionCannotJoinQueue() {
        when(learningSessionService.hasActiveSession(1L)).thenReturn(true);

        matchMakingService.joinQueue(basicRequest(1L), "session1");

        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/1"), argThat((Object dto) ->
                "ACTIVE_SESSION_EXISTS".equals(((MatchResultDTO)dto).getStatus())));
        verify(learningSessionService, never()).createSession(any(), any(), any(), any(), any());
        assertTrue(queueEntries().isEmpty());
    }

    @Test
    void reloadRecoveryUsesSameSessionAndNotifiesBothParticipants() {
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L).channelName("room-existing").status(SessionStatus.IN_PROGRESS)
                .levelSnapshot(JapaneseLevel.N5).tagSnapshot("[10,20,30]")
                .user1(user1).user2(user2).build();
        when(learningSessionService.findActiveSessionByUserId(1L)).thenReturn(Optional.of(session));
        when(learningSessionService.reportLeaveAgora(100L, 1L)).thenReturn(session);

        matchMakingService.recoverSession(1L, "reloaded-connection");

        verify(learningSessionService).reportLeaveAgora(100L, 1L);
        verify(userRepository).findById(2L);
        verify(learningSessionService, never()).createSession(any(), any(), any(), any(), any());
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/1"), argThat((Object dto) -> {
            MatchResultDTO result = (MatchResultDTO) dto;
            return "RECOVERY_READY".equals(result.getStatus()) && Long.valueOf(100L).equals(result.getSessionId());
        }));
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/2"), argThat((Object dto) ->
                "PEER_RECONNECTING".equals(((MatchResultDTO) dto).getStatus())));
    }

    @Test
    void duplicateRecoveryForSameConnectionDoesNotCreateAnotherPresenceCycle() {
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L).channelName("room-existing").status(SessionStatus.IN_PROGRESS)
                .user1(user1).user2(user2).build();
        when(learningSessionService.findActiveSessionByUserId(1L)).thenReturn(Optional.of(session));
        when(learningSessionService.reportLeaveAgora(100L, 1L)).thenReturn(session);

        matchMakingService.recoverSession(1L, "same-connection");
        matchMakingService.recoverSession(1L, "same-connection");

        verify(learningSessionService, times(1)).reportLeaveAgora(100L, 1L);
        verify(learningSessionService, times(1)).finalizeIfNeeded(100L);
    }

    @Test
    void recoveryFinalizesExpiredDeadlineBeforeReportingLeave() {
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession active = LearningSession.builder()
                .id(100L).status(SessionStatus.IN_PROGRESS).user1(user1).user2(user2).build();
        LearningSession terminal = LearningSession.builder()
                .id(100L).status(SessionStatus.INCOMPLETE).user1(user1).user2(user2).build();
        when(learningSessionService.findActiveSessionByUserId(1L)).thenReturn(Optional.of(active));
        when(learningSessionService.findById(100L)).thenReturn(Optional.of(terminal));

        matchMakingService.recoverSession(1L, "reloaded-after-deadline");

        verify(learningSessionService).finalizeIfNeeded(100L);
        verify(learningSessionService, never()).reportLeaveAgora(anyLong(), anyLong());
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/1"), argThat((Object dto) ->
                "SESSION_ENDED".equals(((MatchResultDTO) dto).getStatus())));
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/2"), argThat((Object dto) ->
                "SESSION_ENDED".equals(((MatchResultDTO) dto).getStatus())));
    }

    @Test
    void registeredActiveConnectionDisconnectClosesPresenceAndNotifiesPeer() {
        User user1 = user(1L, "user1");
        User user2 = user(2L, "user2");
        LearningSession session = LearningSession.builder()
                .id(100L).status(SessionStatus.IN_PROGRESS).user1(user1).user2(user2).build();
        when(learningSessionService.hasActiveSession(1L)).thenReturn(true);
        when(learningSessionService.findActiveSessionByUserId(1L)).thenReturn(Optional.of(session));
        matchMakingService.joinQueue(basicRequest(1L), "connection-1");
        clearInvocations(learningSessionService, simpMessagingTemplate);
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getSessionId()).thenReturn("connection-1");

        matchMakingService.handleWebSocketDisconnectListener(event);

        verify(learningSessionService).reportLeaveAgora(100L, 1L);
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/2"), argThat((Object dto) ->
                "PEER_RECONNECTING".equals(((MatchResultDTO) dto).getStatus())));
    }

    @Test
    void persistFailureKeepsEntriesInQueueWithOriginalEnqueuedAt() {
        Instant originalEnqueuedAt = Instant.parse("2026-01-01T00:00:00Z");
        when(timeProvider.instant()).thenReturn(originalEnqueuedAt);
        when(learningSessionService.createSession(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB error"));

        matchMakingService.joinQueue(basicRequest(1L), "session1");

        assertThrows(RuntimeException.class, () -> matchMakingService.joinQueue(basicRequest(2L), "session2"));

        verify(learningSessionService, times(1)).createSession(any(), any(), any(), any(), any());
        Map<String, MatchingQueueEntry> queue = queueEntries();
        assertEquals(Set.of("1", "2"), queue.keySet());
        assertEquals(originalEnqueuedAt, queue.get("1").getEnqueuedAt());
        assertEquals(originalEnqueuedAt, queue.get("2").getEnqueuedAt());
    }

    @Test
    void concurrentJoinRequestsNoDoubleMatch() throws InterruptedException {
        int numUsers = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numUsers);
        CountDownLatch latch = new CountDownLatch(numUsers);
        AtomicInteger matchCount = new AtomicInteger(0);

        when(learningSessionService.createSession(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    matchCount.incrementAndGet();
                    return LearningSession.builder()
                            .id((long) matchCount.get())
                            .channelName("room-test")
                            .levelSnapshot(JapaneseLevel.N5)
                            .tagSnapshot("test")
                            .status(SessionStatus.MATCHED)
                            .matchedAt(LocalDateTime.now())
                            .user1(inv.getArgument(0))
                            .user2(inv.getArgument(1))
                            .user1Uid(1)
                            .user2Uid(2)
                            .build();
                });

        for (int i = 1; i <= numUsers; i++) {
            final long userId = i;
            executor.submit(() -> {
                try {
                    matchMakingService.joinQueue(basicRequest(userId), "session" + userId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(matchCount.get() <= 10);
    }

    @Test
    void anchorIsGloballyOldestWithUserIdTieBreak() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(2L), "session2");
        matchMakingService.joinQueue(basicRequest(3L), "session3");

        ArgumentCaptor<User> user1Captor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<User> user2Captor = ArgumentCaptor.forClass(User.class);
        verify(learningSessionService).createSession(user1Captor.capture(), user2Captor.capture(), any(), any(), any());
        assertEquals(1L, user1Captor.getValue().getId());
        assertEquals(2L, user2Captor.getValue().getId());
    }

    @Test
    void activeSessionRecheckPreventsMatchWithAnyPeer() {
        when(learningSessionService.hasActiveSession(1L)).thenReturn(false);
        when(learningSessionService.hasActiveSession(2L)).thenReturn(true);
        when(learningSessionService.hasActiveSession(3L)).thenReturn(true);

        matchMakingService.joinQueue(basicRequest(1L), "session1");
        matchMakingService.joinQueue(basicRequest(2L), "session2");

        verify(learningSessionService, never()).createSession(any(), any(), any(), any(), any());
    }

    @Test
    void webSocketDisconnectWhileWaitingCancelsSearch() {
        matchMakingService.joinQueue(basicRequest(1L), "session1");

        SessionDisconnectEvent disconnectEvent = mock(SessionDisconnectEvent.class);
        when(disconnectEvent.getSessionId()).thenReturn("session1");

        matchMakingService.handleWebSocketDisconnectListener(disconnectEvent);

        verify(simpMessagingTemplate, never()).convertAndSend(eq("/topic/match/1"), argThat((Object dto) ->
                "PEER_DISCONNECTED".equals(((MatchResultDTO) dto).getStatus())));
    }

    @Test
    void webSocketDisconnectWhileMatchedCallsReportLeaveNotFinalize() throws Exception {
        // Setup internal state directly to simulate matched session
        MatchingQueueEntry entry1 = new MatchingQueueEntry(1L, "user1", JapaneseLevel.N5, Set.of(10L, 20L, 30L), Instant.now(), "session1");
        MatchingQueueEntry entry2 = new MatchingQueueEntry(2L, "user2", JapaneseLevel.N5, Set.of(10L, 20L, 30L), Instant.now(), "session2");

        // Set up sessionUserMap
        java.lang.reflect.Field sessionUserMapField = MatchMakingService.class.getDeclaredField("sessionUserMap");
        sessionUserMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, MatchingQueueEntry> sessionUserMap = (ConcurrentHashMap<String, MatchingQueueEntry>) sessionUserMapField.get(matchMakingService);
        sessionUserMap.put("session1", entry1);
        sessionUserMap.put("session2", entry2);

        // Set up matchResults with MATCHED status
        MatchResultDTO result1 = MatchResultDTO.builder()
                .status("MATCHED")
                .sessionId(100L)
                .peerId(2L)
                .build();
        MatchResultDTO result2 = MatchResultDTO.builder()
                .status("MATCHED")
                .sessionId(100L)
                .peerId(1L)
                .build();

        java.lang.reflect.Field matchResultsField = MatchMakingService.class.getDeclaredField("matchResults");
        matchResultsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, MatchResultDTO> matchResults = (ConcurrentHashMap<Long, MatchResultDTO>) matchResultsField.get(matchMakingService);
        matchResults.put(1L, result1);
        matchResults.put(2L, result2);

        // Create disconnect event
        SessionDisconnectEvent disconnectEvent = mock(SessionDisconnectEvent.class);
        when(disconnectEvent.getSessionId()).thenReturn("session1");

        // Handle disconnect
        matchMakingService.handleWebSocketDisconnectListener(disconnectEvent);

        // Verify reportLeaveAgora was called (sets reconnect deadline, doesn't finalize)
        verify(learningSessionService).reportLeaveAgora(eq(100L), eq(1L));
        // Verify endSession (which finalizes) was NOT called
        verify(learningSessionService, never()).endSession(anyLong(), anyLong(), any());
        verify(learningSessionService, never()).cancelSession(anyLong(), anyLong(), any());

        // Verify peer is notified
        verify(simpMessagingTemplate).convertAndSend(eq("/topic/match/2"), argThat((Object dto) ->
                "PEER_DISCONNECTED".equals(((MatchResultDTO) dto).getStatus())));
    }

    @Test
    void webSocketDisconnectEscalatesReportLeaveFailureWithoutFinalizing() throws Exception {
        // Setup internal state directly
        MatchingQueueEntry entry1 = new MatchingQueueEntry(1L, "user1", JapaneseLevel.N5, Set.of(10L, 20L, 30L), Instant.now(), "session1");
        MatchingQueueEntry entry2 = new MatchingQueueEntry(2L, "user2", JapaneseLevel.N5, Set.of(10L, 20L, 30L), Instant.now(), "session2");

        java.lang.reflect.Field sessionUserMapField = MatchMakingService.class.getDeclaredField("sessionUserMap");
        sessionUserMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, MatchingQueueEntry> sessionUserMap = (ConcurrentHashMap<String, MatchingQueueEntry>) sessionUserMapField.get(matchMakingService);
        sessionUserMap.put("session1", entry1);
        sessionUserMap.put("session2", entry2);

        MatchResultDTO result1 = MatchResultDTO.builder()
                .status("MATCHED")
                .sessionId(100L)
                .peerId(2L)
                .build();
        MatchResultDTO result2 = MatchResultDTO.builder()
                .status("MATCHED")
                .sessionId(100L)
                .peerId(1L)
                .build();

        java.lang.reflect.Field matchResultsField = MatchMakingService.class.getDeclaredField("matchResults");
        matchResultsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<Long, MatchResultDTO> matchResults = (ConcurrentHashMap<Long, MatchResultDTO>) matchResultsField.get(matchMakingService);
        matchResults.put(1L, result1);
        matchResults.put(2L, result2);

        // Simulate error in reportLeaveAgora
        doThrow(new RuntimeException("DB error")).when(learningSessionService).reportLeaveAgora(eq(100L), eq(1L));

        SessionDisconnectEvent disconnectEvent = mock(SessionDisconnectEvent.class);
        when(disconnectEvent.getSessionId()).thenReturn("session1");

        assertThrows(IllegalStateException.class,
                () -> matchMakingService.handleWebSocketDisconnectListener(disconnectEvent));
        verify(learningSessionService, never()).endSession(anyLong(), anyLong(), any());
        verify(simpMessagingTemplate, never()).convertAndSend(eq("/topic/match/2"), any(Object.class));
    }

    private User user(Long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .email(username + "@example.com")
                .currentLevel(JapaneseLevel.N5)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, MatchingQueueEntry> queueEntries() {
        try {
            java.lang.reflect.Field field = MatchMakingService.class.getDeclaredField("userQueueMap");
            field.setAccessible(true);
            return Map.copyOf((Map<String, MatchingQueueEntry>) field.get(matchMakingService));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
