package com.example.videocall_marching_language.controller.user;

import com.example.videocall_marching_language.dto.user.UpdateProfileRequest;
import com.example.videocall_marching_language.dto.user.UserProfileResponse;
import com.example.videocall_marching_language.enums.JapaneseLevel;
import com.example.videocall_marching_language.enums.UserRole;
import com.example.videocall_marching_language.exception.AvatarUploadException;
import com.example.videocall_marching_language.exception.InvalidAvatarException;
import com.example.videocall_marching_language.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class ProfileControllerTests {

    private final IUserService userService = mock(IUserService.class);
    private final UserProfileResponse currentProfile = new UserProfileResponse(
            7L, "Học viên", "learner@example.com", JapaneseLevel.N5, 1.5f,
            "https://example.com/old.png", UserRole.USER
    );
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProfileController(userService)).build();
        when(userService.getCurrentProfile("learner@example.com")).thenReturn(currentProfile);
    }

    @Test
    void updateProfile_whenAvatarIsInvalid_returnsFormWithAvatarBindingErrorAndPreservesFields()
            throws Exception {
        MockMultipartFile avatar = avatar();
        doThrow(new InvalidAvatarException("AVATAR_INVALID_CONTENT", "Nội dung avatar không hợp lệ"))
                .when(userService).updateCurrentProfile(eq("learner@example.com"), any(UpdateProfileRequest.class));

        MvcResult result = submit(avatar);

        assertProfileForm(result, "Nội dung avatar không hợp lệ");
        verify(userService).updateCurrentProfile(eq("learner@example.com"), any(UpdateProfileRequest.class));
    }

    @Test
    void updateProfile_whenStorageFails_returnsFormWithSanitizedAvatarBindingError()
            throws Exception {
        MockMultipartFile avatar = avatar();
        doThrow(new AvatarUploadException("Không thể tải avatar lên Cloudinary", null))
                .when(userService).updateCurrentProfile(eq("learner@example.com"), any(UpdateProfileRequest.class));

        MvcResult result = submit(avatar);

        assertProfileForm(result, "Không thể tải avatar lên Cloudinary");
        verify(userService).updateCurrentProfile(eq("learner@example.com"), any(UpdateProfileRequest.class));
    }

    private MvcResult submit(MockMultipartFile avatar) throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "learner@example.com", "unused", List.of()
        );
        return mockMvc.perform(multipart("/profile/edit")
                        .file(avatar)
                        .param("username", "Tên đã giữ")
                        .param("currentLevel", "N5")
                        .principal(authentication))
                .andExpect(view().name("users/profile_edit"))
                .andReturn();
    }

    private void assertProfileForm(MvcResult result, String expectedErrorMessage) throws Exception {
        ModelAndView modelAndView = result.getModelAndView();
        assertNotNull(modelAndView);
        assertEquals(currentProfile, modelAndView.getModel().get("profile"));

        UpdateProfileRequest form = (UpdateProfileRequest) modelAndView.getModel().get("updateProfileRequest");
        assertNotNull(form);
        assertEquals("Tên đã giữ", form.getUsername());
        assertEquals(JapaneseLevel.N5, form.getCurrentLevel());

        BindingResult bindingResult = (BindingResult) modelAndView.getModel().get(
                BindingResult.MODEL_KEY_PREFIX + "updateProfileRequest"
        );
        assertNotNull(bindingResult);
        assertTrue(bindingResult.hasFieldErrors("avatar"));
        assertEquals(expectedErrorMessage, bindingResult.getFieldError("avatar").getDefaultMessage());
        assertFalse(result.getResponse().getContentAsString().trim().startsWith("{"));
    }

    private MockMultipartFile avatar() {
        return new MockMultipartFile("avatar", "avatar.png", "image/png", new byte[]{1, 2, 3});
    }
}
