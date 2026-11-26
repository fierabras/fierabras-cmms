package com.grash.security;

import com.grash.model.User;
import com.grash.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void loadUserByUsername_returnsCustomUserDetailForUser() {
        User user = new User();
        user.setEmail("john@test.com");
        when(userService.findByEmail("john@test.com")).thenReturn(Optional.of(user));

        CustomUserDetail result = customUserDetailsService.loadUserByUsername("john@test.com");

        assertNotNull(result);
        assertSame(user, result.getUser());
        assertEquals("john@test.com", result.getUsername());
    }

    @Test
    void loadUserByUsername_loadsTheUserFromTheDatabase_notFromTheCache() {
        User user = new User();
        user.setEmail("test@test.com");
        when(userService.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        customUserDetailsService.loadUserByUsername("test@test.com");

        verify(userService).findByEmail("test@test.com");
        verifyNoMoreInteractions(userService);
    }

    @Test
    void loadUserByUsername_unknownUser_throwsUsernameNotFound() {
        when(userService.findByEmail("gone@test.com")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class,
                () -> customUserDetailsService.loadUserByUsername("gone@test.com"));
    }
}
