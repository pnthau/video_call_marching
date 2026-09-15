package com.example.videocall_marching_language.controller.user.api;

import com.example.videocall_marching_language.dto.PeerRatingRequest;
import com.example.videocall_marching_language.entity.LearningSession;
import com.example.videocall_marching_language.entity.PeerRating;
import com.example.videocall_marching_language.entity.Rubric;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.repository.IPeerRatingRepository;
import com.example.videocall_marching_language.service.ILearningSessionService;
import com.example.videocall_marching_language.service.IRubricService;
import com.example.videocall_marching_language.service.IUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/peer-ratings")
@RequiredArgsConstructor
public class PeerRatingApiController {

    private final IRubricService rubricService;
    private final IPeerRatingRepository peerRatingRepository;
    private final IUserService userService;
    private final ILearningSessionService learningSessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/rubrics")
    public ResponseEntity<List<Rubric>> getActiveRubrics() {
        return ResponseEntity.ok(rubricService.findAllActive());
    }

    @PostMapping
    public ResponseEntity<?> submitRating(@RequestBody PeerRatingRequest request, Authentication authentication) throws JsonProcessingException {
        User currentUser = userService.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
        Long currentUserId = currentUser.getId();

        if (peerRatingRepository.existsByRaterIdAndSessionId(currentUserId, request.getSessionId())) {
            return ResponseEntity.badRequest().body("Already rated for this session");
        }

        LearningSession session = learningSessionService.findById(request.getSessionId())
                .orElseThrow(() -> new RuntimeException("Session not found"));

        // Validate user is in session
        if (!currentUserId.equals(session.getUser1().getId()) && !currentUserId.equals(session.getUser2().getId())) {
            return ResponseEntity.status(403).body("Not a participant");
        }

        User ratee = userService.findById(request.getRateeId())
                .orElseThrow(() -> new RuntimeException("Ratee not found"));

        int totalScore = 0;
        if (request.getScores() != null) {
            for (Integer score : request.getScores().values()) {
                totalScore += score;
            }
        }

        String detailScoresJson = objectMapper.writeValueAsString(request.getScores());

        PeerRating peerRating = new PeerRating();
        peerRating.setRater(currentUser);
        peerRating.setRatee(ratee);
        peerRating.setSession(session);
        peerRating.setTotalScore(totalScore);
        peerRating.setDetailScores(detailScoresJson);
        peerRating.setComment(request.getComment());

        peerRatingRepository.save(peerRating);

        return ResponseEntity.ok().build();
    }
}
