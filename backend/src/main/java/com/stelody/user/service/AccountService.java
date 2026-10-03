package com.stelody.user.service;

import com.stelody.user.dto.CurrentUser;
import com.stelody.user.repository.AccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  private final AccountRepository repository;

  public AccountService(AccountRepository repository) {
    this.repository = repository;
  }

  @Transactional
  public CurrentUser signIn(String subject, String email) {
    if (subject == null
        || subject.isBlank()
        || subject.length() > 255
        || email == null
        || email.isBlank()
        || email.length() > 320) {
      throw new OAuth2AuthenticationException("invalid_identity");
    }
    var account = repository.upsertGoogleUser(subject, email);
    if (!account.status().equals("ACTIVE")) {
      throw new OAuth2AuthenticationException("account_unavailable");
    }
    return account;
  }

  public Optional<CurrentUser> find(UUID id) {
    return repository.find(id);
  }

  @Transactional
  public CurrentUser reauthenticate(UUID id, String subject) {
    if (!repository.lockGoogleSubject(id).filter(expected -> expected.equals(subject)).isPresent())
      throw new OAuth2AuthenticationException("reauthentication_account_mismatch");
    return repository
        .find(id)
        .orElseThrow(() -> new OAuth2AuthenticationException("account_unavailable"));
  }
}
