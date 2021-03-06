/**
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.vorto.repository.oauth;

import java.security.PublicKey;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.vorto.model.ModelId;
import org.eclipse.vorto.repository.account.IUserAccountService;
import org.eclipse.vorto.repository.domain.Namespace;
import org.eclipse.vorto.repository.domain.Role;
import org.eclipse.vorto.repository.domain.Tenant;
import org.eclipse.vorto.repository.domain.User;
import org.eclipse.vorto.repository.oauth.internal.JwtToken;
import org.eclipse.vorto.repository.oauth.internal.MalformedElement;
import org.eclipse.vorto.repository.oauth.internal.PublicKeyHelper;
import org.eclipse.vorto.repository.oauth.internal.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class BoschIoTSuiteOAuthProviderV2 extends AbstractOAuthProvider {

  private static final String RS256_ALG = "RS256";

  private static final String CLIENT_ID = "client_id";
  
  private String hydraJwtIssuer;

  @Autowired
  public BoschIoTSuiteOAuthProviderV2(
          @Value("${oauth2.verification.hydra.issuer: #{null}}") String hydraJwtIssuer,
          @Value("${oauth2.verification.hydra.publicKeyUri: #{null}}") String hydraPublicKeyUri,
          @Autowired IUserAccountService userAccountService) {
    super(PublicKeyHelper.supplier(new RestTemplate(), hydraPublicKeyUri), userAccountService);
    this.hydraJwtIssuer = hydraJwtIssuer;
  }
  
  public BoschIoTSuiteOAuthProviderV2(Supplier<Map<String, PublicKey>> publicKeySupplier, 
      IUserAccountService userAccountService) {
    super(publicKeySupplier, userAccountService);
  }
  
  @Override
  public String getId() {
    return "BOSCH-IOT-SUITE-AUTH";
  }

  @Override
  public boolean canHandle(Authentication auth) {
    return false;
  }

  @Override
  public String getIssuer() {
    return hydraJwtIssuer; 
  }
  
  @Override
  public OAuth2Authentication createAuthentication(HttpServletRequest httpRequest, JwtToken jwtToken) {
    return getTechnicalUser(jwtToken)
            .map(technicalUser -> createAuthentication(technicalUser.getUsername(), technicalUser.getUsername(),
                    technicalUser.getUsername(), null, getRolesForRequest(technicalUser, httpRequest)))
            .orElseThrow(() -> new MalformedElement("clientId in jwtToken isn't a registered technical user"));
  }

  private Set<Role> getRolesForRequest(User user, HttpServletRequest httpRequest) {
    Optional<Resource> resource = resource(httpRequest);
    if (resource.isPresent()) {
      Optional<Namespace> ns = namespaceApplicableToResource(user, resource.get());
      if (ns.isPresent()) {
        return userAccountService.getRoles(user, ns.get().getTenant().getTenantId());
      } 
    }
    return userAccountService.getAllRoles(user);
  }
  
  @Override
  public boolean verify(HttpServletRequest httpRequest, JwtToken jwtToken) {
    if (!verifyAlgorithm(jwtToken)) {
      return false;
    }

    if (!super.verifyPublicKey(jwtToken)) {
      return false;
    }

    if (!super.verifyExpiry(jwtToken)) {
      return false;
    }

    User technicalUser = getTechnicalUser(jwtToken)
            .orElseThrow(() -> new MalformedElement("clientId in jwtToken isn't a registered technical user"));

    return allowAccess(resource(httpRequest), technicalUser);
  }

  private Optional<User> getTechnicalUser(JwtToken jwtToken) {
    String technicalUserId = getClientId(jwtToken)
            .orElseThrow(() -> new MalformedElement("jwtToken doesn't have clientId"));
    return Optional.ofNullable(userAccountService.getUser(technicalUserId));
  }

  private boolean verifyAlgorithm(JwtToken jwtToken) {
    return jwtToken.getHeaderMap().get("alg").equals(RS256_ALG);
  }

  private boolean allowAccess(Optional<Resource> resource, User user) {
    return !resource.isPresent() || namespaceApplicableToResource(user, resource.get()).isPresent();
  }

  private Optional<Namespace> namespaceApplicableToResource(User user, Resource resource) {
    Collection<Tenant> tenants = userAccountService.getTenants(user);
    for (Tenant tenant : tenants) {
      for (Namespace ns : tenant.getNamespaces()) {
        if (scopeApplies(ns, resource)) {
          return Optional.of(ns);
        }
      }
    }
    return Optional.empty();
  }

  private boolean scopeApplies(Namespace namespace, Resource resource) {
    if (resource.getType() == Resource.Type.ModelId) {
      return namespace.owns(ModelId.fromPrettyFormat(resource.getName()));
    } else {
      return namespace.owns(resource.getName());
    } 
  }

  private Optional<Resource> resource(HttpServletRequest request) {
    Objects.requireNonNull(request.getRequestURI());
    String resourceUrl = request.getRequestURI().replace(request.getContextPath(), "");
    return ResourceIdentificationHelper.identifyResource(resourceUrl);
  }

  @Override
  protected Optional<String> getUserId(Map<String, Object> map) {
    return Optional.ofNullable((String) map.get(JWT_SUB));
  }
  
  protected Optional<String> getClientId(JwtToken token) {
    if (token.getPayloadMap().containsKey(CLIENT_ID)) {
      return Optional.ofNullable((String) token.getPayloadMap().get(CLIENT_ID));
    }
    return Optional.empty();
  }

  @Override
  public String getLabel() {
    return "Bosch IoT Suite OAuth";
  }
}
