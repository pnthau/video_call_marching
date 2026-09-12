package com.example.videocall_marching_language.controller;

import com.example.videocall_marching_language.config.MatchingProperties;
import com.example.videocall_marching_language.controller.user.WebController;
import com.example.videocall_marching_language.dto.TagOptionDTO;
import com.example.videocall_marching_language.dto.script.ScriptResponse;
import com.example.videocall_marching_language.entity.Tag;
import com.example.videocall_marching_language.entity.TagCategory;
import com.example.videocall_marching_language.entity.User;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.TagCategoryType;
import com.example.videocall_marching_language.repository.ITagRepository;
import com.example.videocall_marching_language.service.IUserService;
import com.example.videocall_marching_language.service.script.PracticeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.Model;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class WebControllerTests {

    private MockMvc mockMvc;

    @Mock
    private IUserService userService;

    @Mock
    private ITagRepository tagRepository;

    private MatchingProperties matchingProperties;

    @Mock
    private PracticeService practiceService;

    private WebController controller;

    @BeforeEach
    void setUp() {
        matchingProperties = new MatchingProperties();
        controller = new WebController(userService, tagRepository, matchingProperties, practiceService);
        ReflectionTestUtils.setField(controller, "agoraAppId", "test-app-id");

        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/templates/");
        viewResolver.setSuffix(".html");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(viewResolver)
                .build();
    }

    // =========================================================================
    // 1. Direct Unit Tests (Model Mocking)
    // =========================================================================

    @Test
    @DisplayName("VideoCall Page: tải tag category types, available tags và thông số matching")
    void videoCallPageLoadsCategoryTypesAndTheirTags() {
        Model model = mock(Model.class);
        TagCategory topic = TagCategory.builder()
                .id(1L).name("Chủ đề bài học").type(TagCategoryType.TOPIC).build();
        List<Tag> tags = List.of(Tag.builder().id(10L).name("Giới thiệu bản thân").tagCategory(topic).build());
        when(tagRepository.findAllForActiveCategories()).thenReturn(tags);

        String viewName = controller.showVideoCallPage(null, model);

        assertEquals("users/video_call", viewName);
        verify(model).addAttribute("agoraAppId", "test-app-id");
        verify(model).addAttribute(eq("tagCategoryTypes"), any(TagCategoryType[].class));
        verify(model).addAttribute("availableTags", List.of(
                new TagOptionDTO(10L, "Giới thiệu bản thân", "TOPIC")));
        verify(model).addAttribute("adjacentLevelAfterSeconds", 120);
    }

    @Test
    @DisplayName("VideoCall Page: người dùng đã xác thực được gán currentUserId và currentUserLevel")
    void videoCallPageWithAuthenticatedUser_SetsUserIdAndCurrentLevel() {
        Model model = mock(Model.class);
        Authentication auth = mock(Authentication.class);

        when(auth.getName()).thenReturn("user@example.com");
        User user = User.builder()
                .id(5L)
                .email("user@example.com")
                .currentLevel(JapaneseLevel.N3)
                .build();
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tagRepository.findAllForActiveCategories()).thenReturn(List.of());

        String viewName = controller.showVideoCallPage(auth, model);

        assertEquals("users/video_call", viewName);
        verify(model).addAttribute("currentUserId", 5L);
        verify(model).addAttribute("currentUserLevel", JapaneseLevel.N3);
        verify(model).addAttribute("agoraAppId", "test-app-id");
    }

    @Test
    @DisplayName("Script Study Page: tải kịch bản và gán alreadyRead=true")
    void scriptStudyPage_LoadsScriptAndSetsAttributes() {
        Model model = mock(Model.class);
        Authentication auth = mock(Authentication.class);

        when(auth.getName()).thenReturn("learner@example.com");
        User user = User.builder()
                .id(12L)
                .email("learner@example.com")
                .build();
        when(userService.findByEmail("learner@example.com")).thenReturn(Optional.of(user));

        ScriptResponse script = ScriptResponse.builder()
                .id(42L)
                .title("Hội thoại chào hỏi")
                .build();
        when(practiceService.findScriptById(42)).thenReturn(script);

        String viewName = controller.showScriptStudyPage(42L, auth, model);

        assertEquals("users/scripts/study", viewName);
        verify(model).addAttribute("script", script);
        verify(model).addAttribute("currentUserId", 12L);
        verify(model).addAttribute("alreadyRead", true);
    }

    @Test
    @DisplayName("Landing Page Unit: khách vãng lai (null auth) gán isAuthenticated=false")
    void landingPage_unauthenticated_setsIsAuthenticatedFalse() {
        Model model = mock(Model.class);

        String viewName = controller.showLandingPage(null, model);

        assertEquals("landing", viewName);
        verify(model).addAttribute("isAuthenticated", false);
    }

    @Test
    @DisplayName("Landing Page Unit: AnonymousAuthenticationToken gán isAuthenticated=false")
    void landingPage_anonymousUser_setsIsAuthenticatedFalse() {
        Model model = mock(Model.class);
        AnonymousAuthenticationToken anonToken = mock(AnonymousAuthenticationToken.class);
        when(anonToken.isAuthenticated()).thenReturn(true);

        String viewName = controller.showLandingPage(anonToken, model);

        assertEquals("landing", viewName);
        verify(model).addAttribute("isAuthenticated", false);
    }

    @Test
    @DisplayName("Landing Page Unit: người dùng đã đăng nhập gán đầy đủ thông tin profile")
    void landingPage_authenticatedUser_setsAttributes() {
        Model model = mock(Model.class);
        Authentication auth = mock(Authentication.class);

        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("john@example.com");

        User user = User.builder()
                .id(1L)
                .username("john_doe")
                .email("john@example.com")
                .avatarUrl("https://example.com/avatar.png")
                .build();
        when(userService.findByEmail("john@example.com")).thenReturn(Optional.of(user));

        String viewName = controller.showLandingPage(auth, model);

        assertEquals("landing", viewName);
        verify(model).addAttribute("isAuthenticated", true);
        verify(model).addAttribute("username", "john_doe");
        verify(model).addAttribute("avatarUrl", "https://example.com/avatar.png");
        verify(model).addAttribute("email", "john@example.com");
    }

    @Test
    @DisplayName("Landing Page Unit: người dùng đã đăng nhập nhưng không tìm thấy trong DB gán isAuthenticated=false")
    void landingPage_authenticatedUser_userNotFound_setsIsAuthenticatedFalse() {
        Model model = mock(Model.class);
        Authentication auth = mock(Authentication.class);

        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("missing@example.com");
        when(userService.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        String viewName = controller.showLandingPage(auth, model);

        assertEquals("landing", viewName);
        verify(model).addAttribute("isAuthenticated", false);
    }

    // =========================================================================
    // 2. MockMvc HTTP Controller Layer Tests (GET "/", GET "/landing", etc.)
    // =========================================================================

    @Test
    @DisplayName("MockMvc GET / - Người dùng vãng lai (unauthenticated) trả về status 200 OK và view landing")
    void mockMvc_getRoot_unauthenticated_returnsOkAndLandingView() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attribute("isAuthenticated", false));
    }

    @Test
    @DisplayName("MockMvc GET /landing - Người dùng vãng lai (unauthenticated) trả về status 200 OK và view landing")
    void mockMvc_getLanding_unauthenticated_returnsOkAndLandingView() throws Exception {
        mockMvc.perform(get("/landing"))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attribute("isAuthenticated", false));
    }

    @Test
    @DisplayName("MockMvc GET / - Người dùng đã đăng nhập trả về status 200 OK, view landing và attributes người dùng")
    void mockMvc_getRoot_authenticated_returnsOkAndLandingViewWithUserAttributes() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("john@example.com");

        User user = User.builder()
                .id(1L)
                .username("john_doe")
                .email("john@example.com")
                .avatarUrl("https://example.com/avatar.png")
                .build();
        when(userService.findByEmail("john@example.com")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attribute("isAuthenticated", true))
                .andExpect(model().attribute("username", "john_doe"))
                .andExpect(model().attribute("avatarUrl", "https://example.com/avatar.png"))
                .andExpect(model().attribute("email", "john@example.com"));
    }

    @Test
    @DisplayName("MockMvc GET /landing - Người dùng đã đăng nhập trả về status 200 OK, view landing và attributes người dùng")
    void mockMvc_getLanding_authenticated_returnsOkAndLandingViewWithUserAttributes() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("john@example.com");

        User user = User.builder()
                .id(1L)
                .username("john_doe")
                .email("john@example.com")
                .avatarUrl("https://example.com/avatar.png")
                .build();
        when(userService.findByEmail("john@example.com")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/landing").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attribute("isAuthenticated", true))
                .andExpect(model().attribute("username", "john_doe"))
                .andExpect(model().attribute("avatarUrl", "https://example.com/avatar.png"))
                .andExpect(model().attribute("email", "john@example.com"));
    }

    @Test
    @DisplayName("MockMvc GET / - Anonymous user trả về status 200 OK và isAuthenticated=false")
    void mockMvc_getRoot_anonymousUser_returnsOkAndLandingViewWithFalseAuth() throws Exception {
        AnonymousAuthenticationToken anonToken = mock(AnonymousAuthenticationToken.class);
        when(anonToken.isAuthenticated()).thenReturn(true);

        mockMvc.perform(get("/").principal(anonToken))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attribute("isAuthenticated", false));
    }

    @Test
    @DisplayName("MockMvc GET / - Authenticated user không tồn tại trong DB trả về 200 OK và isAuthenticated=false")
    void mockMvc_getRoot_authenticated_userNotFoundInDb_returnsOkAndFalseAuth() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("missing@example.com");
        when(userService.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(get("/").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("landing"))
                .andExpect(model().attribute("isAuthenticated", false));
    }

    @Test
    @DisplayName("MockMvc GET /video-call - Unauthenticated request trả về view users/video_call với tags và cấu hình")
    void mockMvc_getVideoCall_unauthenticated_returnsOkAndVideoCallView() throws Exception {
        TagCategory topic = TagCategory.builder()
                .id(1L).name("Chủ đề bài học").type(TagCategoryType.TOPIC).build();
        List<Tag> tags = List.of(Tag.builder().id(10L).name("Giới thiệu bản thân").tagCategory(topic).build());
        when(tagRepository.findAllForActiveCategories()).thenReturn(tags);

        mockMvc.perform(get("/video-call"))
                .andExpect(status().isOk())
                .andExpect(view().name("users/video_call"))
                .andExpect(model().attribute("agoraAppId", "test-app-id"))
                .andExpect(model().attributeExists("tagCategoryTypes"))
                .andExpect(model().attributeExists("availableTags"))
                .andExpect(model().attribute("adjacentLevelAfterSeconds", 120));
    }

    @Test
    @DisplayName("MockMvc GET /video-call - Authenticated request trả về currentUserId và currentUserLevel")
    void mockMvc_getVideoCall_authenticated_returnsOkWithUserData() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("user@example.com");
        User user = User.builder()
                .id(5L)
                .email("user@example.com")
                .currentLevel(JapaneseLevel.N3)
                .build();
        when(userService.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tagRepository.findAllForActiveCategories()).thenReturn(List.of());

        mockMvc.perform(get("/video-call").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("users/video_call"))
                .andExpect(model().attribute("currentUserId", 5L))
                .andExpect(model().attribute("currentUserLevel", JapaneseLevel.N3));
    }

    @Test
    @DisplayName("MockMvc GET /scripts/{scriptId}/study - Authenticated request trả về script và alreadyRead")
    void mockMvc_getScriptStudy_authenticated_returnsOkWithScriptData() throws Exception {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("learner@example.com");
        User user = User.builder()
                .id(12L)
                .email("learner@example.com")
                .build();
        when(userService.findByEmail("learner@example.com")).thenReturn(Optional.of(user));

        ScriptResponse script = ScriptResponse.builder()
                .id(42L)
                .title("Hội thoại chào hỏi")
                .build();
        when(practiceService.findScriptById(42)).thenReturn(script);

        mockMvc.perform(get("/scripts/42/study").principal(auth))
                .andExpect(status().isOk())
                .andExpect(view().name("users/scripts/study"))
                .andExpect(model().attribute("script", script))
                .andExpect(model().attribute("currentUserId", 12L))
                .andExpect(model().attribute("alreadyRead", true));
    }
}
