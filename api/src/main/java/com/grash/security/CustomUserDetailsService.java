package com.grash.security;

import com.grash.model.User;
import com.grash.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    @Lazy
    private UserService userService;

    @Override
    @Transactional(readOnly = true)
    public CustomUserDetail loadUserByUsername(String username) throws UsernameNotFoundException {
        User user;
        try {
            user = userService.whoami(username, true);
        } catch (NoSuchElementException e) {
            throw new UsernameNotFoundException("User not found");
        }
        return CustomUserDetail.builder()//
                .user(user)//
                .build();
    }

}
