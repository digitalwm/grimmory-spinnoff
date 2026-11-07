package org.booklore.config.security.filter;

import org.booklore.config.security.JwtUtils;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.CannotCreateTransactionException;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void databaseExhaustionReturnsRetryable503InsteadOfTurningAValidSessionInto401() throws Exception {
        var jwt=mock(JwtUtils.class);var users=mock(UserRepository.class);
        when(jwt.validateAccessToken("valid")).thenReturn(true);when(jwt.unsafeExtractUserId("valid")).thenReturn(7L);
        when(users.findByIdWithDetails(7L)).thenThrow(new CannotCreateTransactionException("Connection pool unavailable"));
        var filter=new JwtAuthenticationFilter(mock(BookLoreUserTransformer.class),jwt,users);
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer valid");
        var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);filter.doFilter(request,response,chain);
        assertEquals(503,response.getStatus());assertEquals("5",response.getHeader("Retry-After"));verifyNoInteractions(chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void aDeletedUserStillReachesTheNormalUnauthorizedCheck() throws Exception {
        var jwt=mock(JwtUtils.class);var users=mock(UserRepository.class);
        when(jwt.validateAccessToken("deleted")).thenReturn(true);when(jwt.unsafeExtractUserId("deleted")).thenReturn(7L);
        when(users.findByIdWithDetails(7L)).thenThrow(new UsernameNotFoundException("Deleted user"));
        var filter=new JwtAuthenticationFilter(mock(BookLoreUserTransformer.class),jwt,users);
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer deleted");
        var response=new MockHttpServletResponse();var downstream=mock(FilterChain.class);
        filter.doFilter(request,response,(req,res)->new AuthenticationCheckFilter().doFilter(req,res,downstream));
        assertEquals(401,response.getStatus());verifyNoInteractions(downstream);
    }
}
