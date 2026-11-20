package com.grash.integration.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grash.model.User;
import com.grash.utils.Consts;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Calls the API through the full filter chain (JWT filter, controller, service, database) with {@link MockMvc}.
 * Needs {@code @AutoConfigureMockMvc} on the test class. Safe to use from several threads.
 */
public final class TestApi {

    public record Response(int status, String body) {
        public boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    public TestApi(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    /**
     * Signs in a user created by {@link TestOrganizations} and returns the access token.
     */
    public String login(User user) {
        Response response;
        try {
            response = call(post("/auth/signin").contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            Map.of("email", user.getEmail(), "password", TestOrganizations.PASSWORD))), null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (response.status() != 200) {
            throw new AssertionError("login of " + user.getEmail() + " failed: " + response);
        }
        try {
            return objectMapper.readTree(response.body()).get("accessToken").asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unexpected login response: " + response, e);
        }
    }

    /**
     * Performs the request, with the bearer token when it is not {@code null}, and returns status and body.
     */
    public Response call(MockHttpServletRequestBuilder request, String token) {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, Consts.TOKEN_PREFIX + token);
        }
        try {
            MvcResult result = mockMvc.perform(request).andReturn();
            return new Response(result.getResponse().getStatus(),
                    result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("request failed before an HTTP response", e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
