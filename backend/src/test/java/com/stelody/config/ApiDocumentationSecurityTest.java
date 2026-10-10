package com.stelody.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stelody.auth.web.ApiProblems;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

// A real mapped handler ensures rejection comes from security, not a missing resource.
@WebMvcTest(controllers = DocumentationSecurityFixture.Probe.class)
@Import({
  ApiDocumentationSecurity.class,
  ApiProblems.class,
  DocumentationSecurityFixture.Probe.class
})
abstract class DocumentationSecurityFixture {
  @Autowired MockMvc mvc;

  @RestController
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class Probe {
    @RequestMapping({
      "/v3/api-docs",
      "/v3/api-docs.yaml",
      "/v3/api-docs/swagger-config",
      "/swagger-ui.html",
      "/swagger-ui/index.html"
    })
    String document() {
      return "documentation";
    }
  }

  void closed() throws Exception {
    for (var path :
        new String[] {
          "/v3/api-docs",
          "/v3/api-docs.yaml",
          "/v3/api-docs/swagger-config",
          "/swagger-ui.html",
          "/swagger-ui/index.html"
        }) {
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
    }
  }

  void readable() throws Exception {
    for (var path :
        new String[] {
          "/v3/api-docs",
          "/v3/api-docs.yaml",
          "/v3/api-docs/swagger-config",
          "/swagger-ui.html",
          "/swagger-ui/index.html"
        }) {
      mvc.perform(get(path)).andExpect(status().isOk());
      mvc.perform(post(path).with(user("admin").roles("ADMIN")).with(csrf()))
          .andExpect(status().isForbidden());
    }
  }
}

@ActiveProfiles("local")
@TestPropertySource(
    properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class ApiDocumentationSecurityTest extends DocumentationSecurityFixture {
  @Test
  void developmentReadsAreAllowedButMutationsAreDenied() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    mvc.perform(post("/v3/api-docs").with(user("admin").roles("ADMIN")).with(csrf()))
        .andExpect(status().isForbidden());
  }
}

@ActiveProfiles("prod")
class ProductionApiDocumentationSecurityTest extends DocumentationSecurityFixture {
  @Test
  void productionConfigurationAllowsDocumentationReadsButDeniesMutations() throws Exception {
    readable();
  }
}

@ActiveProfiles({"local", "prod"})
class MixedProfileApiDocumentationSecurityTest extends DocumentationSecurityFixture {
  @Test
  void productionWithLocalProfileAlsoAllowsDocumentationReads() throws Exception {
    readable();
  }
}

@ActiveProfiles("prod")
@TestPropertySource(
    properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=true"})
class DisabledProductionApiDocumentationSecurityTest extends DocumentationSecurityFixture {
  @Test
  void productionSpecificationCanBeDisabledTogetherWithUi() throws Exception {
    closed();
  }
}

@ActiveProfiles("prod")
@TestPropertySource(properties = "springdoc.swagger-ui.enabled=false")
class DisabledProductionSwaggerSecurityTest extends DocumentationSecurityFixture {
  @Test
  void productionUiCanBeDisabledWhileSpecificationRemainsReadable() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isUnauthorized());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
  }
}

@TestPropertySource(
    properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
class DefaultApiDocumentationSecurityTest extends DocumentationSecurityFixture {
  @Test
  void defaultProfileDoesNotExposeDocumentation() throws Exception {
    closed();
  }
}

@ActiveProfiles("local")
@TestPropertySource(
    properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=true"})
class DisabledApiDocumentationSecurityTest extends DocumentationSecurityFixture {
  @Test
  void disabledSpecificationAlsoClosesUi() throws Exception {
    closed();
  }
}
