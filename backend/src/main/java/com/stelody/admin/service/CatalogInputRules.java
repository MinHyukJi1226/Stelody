package com.stelody.admin.service;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.catalog.domain.SearchText;
import jakarta.validation.Validator;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CatalogInputRules {
  private final Validator validator;

  public CatalogInputRules(Validator validator) {
    this.validator = validator;
  }

  public void validate(Object value) {
    if (value == null || !validator.validate(value).isEmpty())
      throw AdminCatalogException.invalid();
  }

  public String text(String text) {
    String value = text.strip();
    if (SearchText.normalize(value).isEmpty()) throw AdminCatalogException.invalid();
    return value;
  }

  public String url(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      URI uri = URI.create(value.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getPort() != -1
          || uri.getRawFragment() != null
          || value.chars().anyMatch(Character::isISOControl)) throw AdminCatalogException.invalid();
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (host.equals("localhost")
          || host.endsWith(".localhost")
          || !host.contains(".")
          || host.matches("[0-9.]+")
          || host.contains(":")) throw AdminCatalogException.invalid();
      return uri.toASCIIString();
    } catch (IllegalArgumentException e) {
      throw AdminCatalogException.invalid();
    }
  }

  public String normalized(String value, int maxLength) {
    String result = SearchText.normalize(value);
    if (result.isEmpty() || result.length() > maxLength) throw AdminCatalogException.invalid();
    return result;
  }

  public void aliases(List<String> aliases) {
    aliases(aliases, 400);
  }

  public void aliases(List<String> aliases, int maxLength) {
    for (String value : aliases) normalized(value, maxLength);
    if (aliases.stream().map(this::text).map(SearchText::normalize).distinct().count()
        != aliases.size()) throw AdminCatalogException.invalid();
  }

  public void ids(List<UUID> ids) {
    if (new HashSet<>(ids).size() != ids.size()) throw AdminCatalogException.invalid();
  }

  public void birthday(Integer month, Integer day) {
    if (month == null && day == null) return;
    if (month == null || day == null) throw AdminCatalogException.invalid();
    try {
      LocalDate.of(2000, month, day);
    } catch (java.time.DateTimeException e) {
      throw AdminCatalogException.invalid();
    }
  }

  public void extras(List<Link> links, List<Karaoke> karaoke) {
    var keys = new HashSet<String>();
    for (var link : links) {
      String address = url(link.url());
      if (!keys.add(SearchText.normalize(text(link.platform())) + " " + address))
        throw AdminCatalogException.invalid();
      url(link.sourceUrl());
    }
    var providers = new HashSet<Provider>();
    for (var entry : karaoke) {
      if (!providers.add(entry.provider())) throw AdminCatalogException.invalid();
      if (entry.status() == KaraokeStatus.REGISTERED) {
        if (entry.number() == null
            || entry.number().isBlank()
            || entry.sourceUrl() == null
            || entry.sourceUrl().isBlank()) throw AdminCatalogException.invalid();
        text(entry.number());
        url(entry.sourceUrl());
      } else if (entry.number() != null) throw AdminCatalogException.invalid();
      if (entry.status() == KaraokeStatus.NOT_LISTED
          && (entry.sourceUrl() == null || entry.sourceUrl().isBlank()))
        throw AdminCatalogException.invalid();
      url(entry.sourceUrl());
    }
  }

  public String youtubeId(String value) {
    if (value.matches("[A-Za-z0-9_-]{11}")) return value;
    try {
      URI uri = URI.create(value.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getUserInfo() != null
          || uri.getPort() != -1) throw AdminCatalogException.invalid();
      String host = uri.getHost();
      String id = null;
      if ("youtu.be".equalsIgnoreCase(host)) id = uri.getPath().substring(1);
      else if (Set.of("www.youtube.com", "youtube.com", "m.youtube.com").contains(host)) {
        if ("/watch".equals(uri.getPath()) && uri.getRawQuery() != null) {
          for (String pair : uri.getRawQuery().split("&"))
            if (pair.startsWith("v=")) {
              if (id != null) throw AdminCatalogException.invalid();
              id = pair.substring(2);
            }
        } else if (uri.getPath().startsWith("/embed/")) id = uri.getPath().substring(7);
      }
      if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw AdminCatalogException.invalid();
      return id;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw AdminCatalogException.invalid();
    }
  }
}
