package com.stelody.user.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** This query uses only its receipt token, including when MVC publishes request events. */
public class WithdrawalResultRequestFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public HttpSession getSession(boolean create) {
            if (create)
              throw new IllegalStateException("Withdrawal result queries cannot create sessions");
            return null;
          }

          @Override
          public HttpSession getSession() {
            return getSession(true);
          }

          @Override
          public String getRequestedSessionId() {
            return null;
          }

          @Override
          public boolean isRequestedSessionIdValid() {
            return false;
          }

          @Override
          public boolean isRequestedSessionIdFromCookie() {
            return false;
          }

          @Override
          public boolean isRequestedSessionIdFromURL() {
            return false;
          }
        },
        response);
  }
}
