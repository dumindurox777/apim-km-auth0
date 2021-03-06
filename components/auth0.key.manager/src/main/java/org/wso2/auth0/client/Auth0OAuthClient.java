/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.auth0.client;

import com.google.gson.Gson;
import feign.Feign;
import feign.FeignException;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.okhttp.OkHttpClient;
import feign.slf4j.Slf4jLogger;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.AccessTokenInfo;
import org.wso2.carbon.apimgt.api.model.AccessTokenRequest;
import org.wso2.carbon.apimgt.api.model.ApplicationConstants;
import org.wso2.carbon.apimgt.api.model.KeyManagerConfiguration;
import org.wso2.carbon.apimgt.api.model.OAuthAppRequest;
import org.wso2.carbon.apimgt.api.model.OAuthApplicationInfo;
import org.wso2.carbon.apimgt.api.model.Scope;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.AbstractKeyManager;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.auth0.client.model.Auth0AccessTokenRequest;
import org.wso2.auth0.client.model.Auth0AccessTokenResponse;
import org.wso2.auth0.client.model.Auth0APIKeyInterceptor;
import org.wso2.auth0.client.model.Auth0ResourceServerInfo;
import org.wso2.auth0.client.model.Auth0ClientInfo;
import org.wso2.auth0.client.model.Auth0DCRClient;
import org.wso2.auth0.client.model.Auth0ResourceServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Auth0OAuthClient extends AbstractKeyManager {
    private static final Log log = LogFactory.getLog(Auth0OAuthClient.class);
    private Auth0DCRClient auth0DCRClient;
    private Auth0ResourceServer auth0ResourceServer;

    @Override
    public OAuthApplicationInfo createApplication(OAuthAppRequest oAuthAppRequest) throws APIManagementException {
        OAuthApplicationInfo oAuthApplicationInfo = oAuthAppRequest.getOAuthApplicationInfo();
        Auth0ClientInfo clientInfo = createClientInfoFromOauthApplicationInfo(oAuthApplicationInfo);
        checkAndCreateAPIIfNotExist();
        Auth0ClientInfo createdApplication = auth0DCRClient.createApplication(clientInfo);
        if (createdApplication != null) {
            OAuthApplicationInfo createdOauthApplication = createOAuthAppInfoFromResponse(createdApplication);
            return createdOauthApplication;
        }
        return null;
    }

    /**
     * This method will create {@code OAuthApplicationInfo} object from a Map of Attributes.
     *
     * @param createdApplication Response returned from server as a Map
     * @return OAuthApplicationInfo object will return.
     */
    private OAuthApplicationInfo createOAuthAppInfoFromResponse(Auth0ClientInfo createdApplication) {
        OAuthApplicationInfo appInfo = new OAuthApplicationInfo();
        appInfo.setClientName(createdApplication.getClientName());
        appInfo.setClientId(createdApplication.getClientId());
        appInfo.setClientSecret(createdApplication.getClientSecret());
        if (createdApplication.getRedirectUris() != null) {
            appInfo.setCallBackURL(String.join(",", createdApplication.getRedirectUris()));
        }
        if (StringUtils.isNotEmpty(createdApplication.getClientName())) {
            appInfo.addParameter(ApplicationConstants.OAUTH_CLIENT_NAME, createdApplication.getClientName());
        }
        if (StringUtils.isNotEmpty(createdApplication.getClientId())) {
            appInfo.addParameter(ApplicationConstants.OAUTH_CLIENT_ID, createdApplication.getClientId());
        }
        if (StringUtils.isNotEmpty(createdApplication.getClientSecret())) {
            appInfo.addParameter(ApplicationConstants.OAUTH_CLIENT_SECRET, createdApplication.getClientSecret());
        }
        String additionalProperties = new Gson().toJson(createdApplication);
        appInfo.addParameter(APIConstants.JSON_ADDITIONAL_PROPERTIES,
                new Gson().fromJson(additionalProperties, Map.class));
        return appInfo;
    }

    /**
     * This method can be used to create a JSON Payload out of the Parameters defined in an OAuth Application
     * in order to create and update the client.
     *
     * @param oAuthApplicationInfo Object that needs to be converted.
     * @return JSON payload.
     */
    private Auth0ClientInfo createClientInfoFromOauthApplicationInfo(OAuthApplicationInfo oAuthApplicationInfo) {
        Auth0ClientInfo clientInfo = new Auth0ClientInfo();
        String userId = (String) oAuthApplicationInfo.getParameter(ApplicationConstants.
                OAUTH_CLIENT_USERNAME);
        String userNameForSp = MultitenantUtils.getTenantAwareUsername(userId);
        String domain = UserCoreUtil.extractDomainFromName(userNameForSp);
        if (domain != null && !domain.isEmpty() && !UserCoreConstants.PRIMARY_DEFAULT_DOMAIN_NAME.equals(domain)) {
            userNameForSp = userNameForSp.replace(UserCoreConstants.DOMAIN_SEPARATOR, "_");
        }
        String applicationName = oAuthApplicationInfo.getClientName();
        String keyType = (String) oAuthApplicationInfo.getParameter(ApplicationConstants.APP_KEY_TYPE);
        String callBackURL = oAuthApplicationInfo.getCallBackURL();
        if (keyType != null) {
            applicationName = userNameForSp.concat(applicationName).concat("_").concat(keyType);
        }
        List<String> grantTypes = new ArrayList<>();
        if (oAuthApplicationInfo.getParameter(APIConstants.JSON_GRANT_TYPES) != null) {
            grantTypes = Arrays.asList(((String) oAuthApplicationInfo
                    .getParameter(APIConstants.JSON_GRANT_TYPES)).split(","));
        }
        clientInfo.setClientName(applicationName);
        if (grantTypes != null && !grantTypes.isEmpty()) {
            clientInfo.setGrantTypes(grantTypes);
        }
        if (StringUtils.isNotEmpty(callBackURL)) {
            String[] calBackUris = callBackURL.split(",");
            clientInfo.setRedirectUris(Arrays.asList(calBackUris));
        }
        Object parameter = oAuthApplicationInfo.getParameter(APIConstants.JSON_ADDITIONAL_PROPERTIES);
        Map<String, Object> additionalProperties = new HashMap<>();
        if (parameter instanceof String) {
            additionalProperties = new Gson().fromJson((String) parameter, Map.class);
        }
        if (additionalProperties.containsKey((Auth0Constants.APP_TYPE))) {
            clientInfo.setApplicationType((String) additionalProperties.get((Auth0Constants.APP_TYPE)));
        } else {
            clientInfo.setApplicationType(Auth0Constants.DEFAULT_CLIENT_APPLICATION_TYPE);
        }
        if (additionalProperties.containsKey(Auth0Constants.TOKEN_ENDPOINT_AUTH_METHOD)) {
            clientInfo.setTokenEndpointAuthMethod((String)
                    additionalProperties.get(Auth0Constants.TOKEN_ENDPOINT_AUTH_METHOD));
        }
        return clientInfo;
    }

    @Override
    public OAuthApplicationInfo updateApplication(OAuthAppRequest oAuthAppRequest) throws APIManagementException {
        OAuthApplicationInfo oAuthApplicationInfo = oAuthAppRequest.getOAuthApplicationInfo();
        Auth0ClientInfo clientInfo = createClientInfoFromOauthApplicationInfo(oAuthApplicationInfo);
        clientInfo.setClientSecret(oAuthApplicationInfo.getClientSecret());
        checkAndCreateAPIIfNotExist();
        Auth0ClientInfo createdApplication = auth0DCRClient.updateApplication(oAuthApplicationInfo.getClientId(), clientInfo);
        if (createdApplication != null) {
            OAuthApplicationInfo createOAuthApplication = createOAuthAppInfoFromResponse(createdApplication);
            return createOAuthApplication;
        }
        return null;
    }

    @Override
    public void deleteApplication(String clientID) throws APIManagementException {
        auth0DCRClient.deleteApplication(clientID);
    }

    @Override
    public OAuthApplicationInfo retrieveApplication(String clientID) throws APIManagementException {
        Auth0ClientInfo auth0ClientInfo = auth0DCRClient.getApplication(clientID);
        OAuthApplicationInfo createdOauthApplication = createOAuthAppInfoFromResponse(auth0ClientInfo);
        return createdOauthApplication;
    }

    @Override
    public AccessTokenInfo getNewApplicationAccessToken(AccessTokenRequest accessTokenRequest)
            throws APIManagementException {
        String clientId = accessTokenRequest.getClientId();
        String clientSecret = accessTokenRequest.getClientSecret();
        Object grantType = accessTokenRequest.getGrantType();
        if (grantType == null) {
            grantType = Auth0Constants.GRANT_TYPE_CLIENT_CREDENTIALS;
        }
        String scopes = "";
        if (accessTokenRequest.getScope() != null && (accessTokenRequest.getScope().length > 0)) {
            scopes = String.join(" ", accessTokenRequest.getScope());
        }
        Auth0AccessTokenResponse retrievedAccessTokenResponse = getAccessToken(clientId, clientSecret,
                grantType.toString(), scopes);
        if (retrievedAccessTokenResponse != null) {
            AccessTokenInfo accessTokenInfo = new AccessTokenInfo();
            accessTokenInfo.setConsumerKey(clientId);
            accessTokenInfo.setConsumerSecret(clientSecret);
            accessTokenInfo.setAccessToken(retrievedAccessTokenResponse.getAccessToken());
            if (retrievedAccessTokenResponse.getScope() != null) {
                accessTokenInfo.setScope(retrievedAccessTokenResponse.getScope().split("\\s+"));
            }
            accessTokenInfo.setValidityPeriod(retrievedAccessTokenResponse.getExpiry());
            return accessTokenInfo;
        }
        return null;
    }

    /**
     * Gets an access token.
     *
     * @param clientId     clientId of the oauth client.
     * @param clientSecret clientSecret of the oauth client.
     * @param grantType    grantType of the oauth toke request.
     * @param scope        list of request scopes separated by space.
     * @return an {@code Auth0AccessTokenResponse}
     * @throws APIManagementException
     */
    private Auth0AccessTokenResponse getAccessToken(String clientId, String clientSecret, String grantType,
                                                    String scope) throws APIManagementException {
        try (CloseableHttpClient httpClient = HttpClientBuilder.create().build()) {
            String tokenEndpoint = (String) configuration.getParameter(APIConstants.KeyManager.TOKEN_ENDPOINT);
            Auth0AccessTokenRequest accessTokenInfo = new Auth0AccessTokenRequest();
            accessTokenInfo.setClientId(clientId);
            accessTokenInfo.setClientSecret(clientSecret);
            accessTokenInfo.setGrantType(grantType);
            accessTokenInfo.setAudience(APIUtil.getServerURL());
            accessTokenInfo.setScope(scope);
            HttpPost httpPost = new HttpPost(tokenEndpoint);
            StringEntity requestEntity = new StringEntity(new Gson().toJson(accessTokenInfo));
            httpPost.setHeader(Auth0Constants.CONTENT_TYPE, Auth0Constants.CONTENT_TYPE_JSON);
            httpPost.setEntity(requestEntity);
            HttpResponse response = httpClient.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();
            HttpEntity entity = response.getEntity();
            if (entity == null) {
                new APIManagementException(String.format(Auth0Constants.STRING_FORMAT,
                        Auth0Constants.ERROR_COULD_NOT_READ_HTTP_ENTITY, response));
            }
            if (HttpStatus.SC_OK == statusCode) {

                try (InputStream inputStream = entity.getContent()) {
                    String content = IOUtils.toString(inputStream);
                    return new Gson().fromJson(content, Auth0AccessTokenResponse.class);
                }
            } else if (HttpStatus.SC_FORBIDDEN == statusCode) {
                Auth0AccessTokenResponse errorResponse = new Auth0AccessTokenResponse();
                errorResponse.setAccessToken("Please add application to WSO2 resource server API to generate tokens");
                return errorResponse;
            }
        } catch (UnsupportedEncodingException e) {
            new APIManagementException(Auth0Constants.ERROR_ENCODING_METHOD_NOT_SUPPORTED, e);
        } catch (IOException e) {
            new APIManagementException(Auth0Constants.ERROR_OCCURRED_WHILE_READ_OR_CLOSE_BUFFER_READER, e);
        }
        return null;
    }

    @Override
    public String getNewApplicationConsumerSecret(AccessTokenRequest accessTokenRequest) throws APIManagementException {
        checkAndCreateAPIIfNotExist();
        Auth0ClientInfo createdApplication = auth0DCRClient.regenerateClientSecret(accessTokenRequest.getClientId());
        return createdApplication.getClientSecret();
    }

    @Override
    public AccessTokenInfo getTokenMetaData(String s) throws APIManagementException {
        return null;
    }

    @Override
    public KeyManagerConfiguration getKeyManagerConfiguration() throws APIManagementException {
        return configuration;
    }

    @Override
    public OAuthApplicationInfo mapOAuthApplication(OAuthAppRequest oAuthAppRequest) throws APIManagementException {
        return null;
    }

    @Override
    public void loadConfiguration(KeyManagerConfiguration keyManagerConfiguration) throws APIManagementException {
        configuration = keyManagerConfiguration;
        try {
            Auth0APIKeyInterceptor auth0APIKeyInterceptor = new Auth0APIKeyInterceptor(
                    (String) keyManagerConfiguration.getParameter(APIConstants.KeyManager.TOKEN_ENDPOINT),
                    (String) keyManagerConfiguration.getParameter(Auth0Constants.CLIENT_ID),
                    (String) keyManagerConfiguration.getParameter(Auth0Constants.CLIENT_SECRET),
                    (String) keyManagerConfiguration.getParameter(Auth0Constants.AUDIENCE));
            String clientRegistrationEndpoint =
                    ((String) keyManagerConfiguration.getParameter(Auth0Constants.AUDIENCE)).concat("clients");
            String resourceServerEndpoint =
                    ((String) keyManagerConfiguration.getParameter(Auth0Constants.AUDIENCE)).concat("resource-servers");
            auth0DCRClient = Feign.builder().client(new OkHttpClient()).encoder(new GsonEncoder())
                    .decoder(new GsonDecoder()).logger(new Slf4jLogger()).requestInterceptor(auth0APIKeyInterceptor)
                    .target(Auth0DCRClient.class, clientRegistrationEndpoint);
            auth0ResourceServer = Feign.builder().client(new OkHttpClient()).encoder(new GsonEncoder())
                    .decoder(new GsonDecoder()).logger(new Slf4jLogger()).requestInterceptor(auth0APIKeyInterceptor)
                    .target(Auth0ResourceServer.class, resourceServerEndpoint);
        } catch (IOException e) {
            throw new APIManagementException(Auth0Constants.ERROR_COULD_NOT_READ_HTTP_ENTITY);
        }
        checkAndCreateAPIIfNotExist();
    }

    /**
     * Create Auth0 Resource Server if not created for WSO2 API Manager
     */
    private void checkAndCreateAPIIfNotExist() throws APIManagementException {
        String audience = APIUtil.getServerURL();
        try {
            Auth0ResourceServerInfo resourceServer = new Auth0ResourceServerInfo();
            resourceServer.setIdentifier(audience);
            resourceServer.setName(Auth0Constants.AUTH0_RESOURCE_SERVER);
            resourceServer.setTokenLifetime(Auth0Constants.DEFAULT_TOKEN_LIFETIME);
            Auth0ResourceServerInfo createdResourceServer = auth0ResourceServer.createResourceServer(resourceServer);
            if (createdResourceServer != null) {
                log.info("Resource server created for : " + audience);
            } else {
                log.error("Error while creating resource server for : " + audience);
            }
        } catch (FeignException exception) {
            if (exception.status() == 409) {
                log.warn("Resource server already created for  : ");
            } else {
                log.error("Error while creating resource server for : " + audience, exception);
            }
        }
    }

    @Override
    public boolean registerNewResource(API api, Map map) throws APIManagementException {
        return false;
    }

    @Override
    public Map getResourceByApiId(String s) throws APIManagementException {
        return null;
    }

    @Override
    public boolean updateRegisteredResource(API api, Map map) throws APIManagementException {
        return false;
    }

    @Override
    public void deleteRegisteredResourceByAPIId(String s) throws APIManagementException {

    }

    @Override
    public void deleteMappedApplication(String s) throws APIManagementException {

    }

    @Override
    public Set<String> getActiveTokensByConsumerKey(String s) throws APIManagementException {
        return null;
    }

    @Override
    public AccessTokenInfo getAccessTokenByConsumerKey(String s) throws APIManagementException {
        return null;
    }

    @Override
    public Map<String, Set<Scope>> getScopesForAPIS(String apiIdsString) throws APIManagementException {
        Map<String, Set<Scope>> apiToScopeMapping = new HashMap<>();
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        Map<String, Set<String>> apiToScopeKeyMapping = apiMgtDAO.getScopesForAPIS(apiIdsString);
        for (String apiId : apiToScopeKeyMapping.keySet()) {
            Set<Scope> apiScopes = new LinkedHashSet<>();
            Set<String> scopeKeys = apiToScopeKeyMapping.get(apiId);
            for (String scopeKey : scopeKeys) {
                Scope scope = getScopeByName(scopeKey);
                apiScopes.add(scope);
            }
            apiToScopeMapping.put(apiId, apiScopes);
        }
        return apiToScopeMapping;
    }

    @Override
    public void registerScope(Scope scope) throws APIManagementException {

    }

    @Override
    public Scope getScopeByName(String s) throws APIManagementException {
        return null;
    }

    @Override
    public Map<String, Scope> getAllScopes() throws APIManagementException {
        return null;
    }

    @Override
    public void deleteScope(String s) throws APIManagementException {

    }

    @Override
    public void updateScope(Scope scope) throws APIManagementException {

    }

    @Override
    public boolean isScopeExists(String s) throws APIManagementException {
        return false;
    }

    @Override
    public String getType() {
        return Auth0Constants.AUTH0_TYPE;
    }
}
