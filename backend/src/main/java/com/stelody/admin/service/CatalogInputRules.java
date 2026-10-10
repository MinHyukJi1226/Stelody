package com.stelody.admin.service;

import com.stelody.admin.dto.CatalogAdminDtos.*;
import com.stelody.admin.web.AdminCatalogException;
import com.stelody.auth.web.InputErrors;
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
    if (value == null) throw AdminCatalogException.invalid();
    var errors = InputErrors.violations(validator.validate(value));
    if (!errors.isEmpty()) throw AdminCatalogException.invalid(errors);
  }

  public String text(String text) {
    return text(text, "value");
  }

  public String text(String text, String field) {
    String value = text.strip();
    if (SearchText.normalize(value).isEmpty())
      throw AdminCatalogException.invalid(field, "INVALID_VALUE");
    return value;
  }

  public String url(String value) {
    return url(value, "url");
  }

  public String url(String value, String field) {
    if (value == null || value.isBlank()) return null;
    try {
      URI uri = URI.create(value.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getPort() != -1
          || uri.getRawFragment() != null
          || value.chars().anyMatch(Character::isISOControl))
        throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (host.equals("localhost")
          || host.endsWith(".localhost")
          || !host.contains(".")
          || host.matches("[0-9.]+")
          || host.contains(":")) throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
      return uri.toASCIIString();
    } catch (IllegalArgumentException e) {
      throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
    }
  }

  public String normalized(String value, int maxLength) {
    return normalized(value, maxLength, "value");
  }

  public String normalized(String value, int maxLength, String field) {
    String result = SearchText.normalize(value);
    if (result.isEmpty() || result.length() > maxLength)
      throw AdminCatalogException.invalid(field, "INVALID_VALUE");
    return result;
  }

  public void aliases(List<String> aliases) {
    aliases(aliases, 400);
  }

  public void aliases(List<String> aliases, int maxLength) {
    aliases(aliases, maxLength, "aliases");
  }

  public void aliases(List<String> aliases, int maxLength, String field) {
    var seen = new HashSet<String>();
    for (int i = 0; i < aliases.size(); i++) {
      String path = field + "[" + i + "]";
      String normalized = normalized(aliases.get(i), maxLength, path);
      if (!seen.add(normalized)) throw AdminCatalogException.invalid(path, "DUPLICATE");
    }
  }

  public void ids(List<UUID> ids) {
    ids(ids, "ids");
  }

  public void ids(List<UUID> ids, String field) {
    var seen = new HashSet<UUID>();
    for (int i = 0; i < ids.size(); i++)
      if (!seen.add(ids.get(i)))
        throw AdminCatalogException.invalid(field + "[" + i + "]", "DUPLICATE");
  }

  public void birthday(Integer month, Integer day) {
    if (month == null && day == null) return;
    if (month == null) throw AdminCatalogException.invalid("birthdayMonth", "REQUIRED");
    if (day == null) throw AdminCatalogException.invalid("birthdayDay", "REQUIRED");
    try {
      LocalDate.of(2000, month, day);
    } catch (java.time.DateTimeException e) {
      throw AdminCatalogException.invalid(
          month < 1 || month > 12 ? "birthdayMonth" : "birthdayDay", "OUT_OF_RANGE");
    }
  }

  public void extras(List<Link> links, List<Karaoke> karaoke) {
    var keys = new HashSet<String>();
    for (int i = 0; i < links.size(); i++) {
      var link = links.get(i);
      String field = "links[" + i + "]";
      String address = url(link.url(), field + ".url");
      if (!keys.add(
          SearchText.normalize(text(link.platform(), field + ".platform")) + " " + address))
        throw AdminCatalogException.invalid(field + ".url", "DUPLICATE");
      url(link.sourceUrl(), field + ".sourceUrl");
    }
    var providers = new HashSet<Provider>();
    for (int i = 0; i < karaoke.size(); i++) {
      var entry = karaoke.get(i);
      String field = "karaoke[" + i + "]";
      if (!providers.add(entry.provider()))
        throw AdminCatalogException.invalid(field + ".provider", "DUPLICATE");
      if (entry.status() == KaraokeStatus.REGISTERED) {
        if (entry.number() == null || entry.number().isBlank())
          throw AdminCatalogException.invalid(field + ".number", "REQUIRED");
        if (entry.sourceUrl() == null || entry.sourceUrl().isBlank())
          throw AdminCatalogException.invalid(field + ".sourceUrl", "REQUIRED");
        text(entry.number(), field + ".number");
      } else if (entry.number() != null)
        throw AdminCatalogException.invalid(field + ".number", "INVALID_VALUE");
      if (entry.status() == KaraokeStatus.NOT_LISTED
          && (entry.sourceUrl() == null || entry.sourceUrl().isBlank()))
        throw AdminCatalogException.invalid(field + ".sourceUrl", "REQUIRED");
      url(entry.sourceUrl(), field + ".sourceUrl");
    }
  }

  public String youtubeId(String value) {
    return youtubeId(value, "videoUrl");
  }

  public String youtubeId(String value, String field) {
    if (value.matches("[A-Za-z0-9_-]{11}")) return value;
    try {
      URI uri = URI.create(value.strip());
      if (!"https".equalsIgnoreCase(uri.getScheme())
          || uri.getUserInfo() != null
          || uri.getPort() != -1) throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
      String host = uri.getHost();
      String id = null;
      if ("youtu.be".equalsIgnoreCase(host)) id = uri.getPath().substring(1);
      else if (Set.of("www.youtube.com", "youtube.com", "m.youtube.com").contains(host)) {
        if ("/watch".equals(uri.getPath()) && uri.getRawQuery() != null) {
          for (String pair : uri.getRawQuery().split("&"))
            if (pair.startsWith("v=")) {
              if (id != null) throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
              id = pair.substring(2);
            }
        } else if (uri.getPath().startsWith("/embed/")) id = uri.getPath().substring(7);
      }
      if (id == null || !id.matches("[A-Za-z0-9_-]{11}"))
        throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
      return id;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw AdminCatalogException.invalid(field, "INVALID_FORMAT");
    }
  }
}
