/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.api.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiKeyAuthDefinition;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.SecurityDefinition;
import io.swagger.annotations.SwaggerDefinition;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.helix.HelixAdmin;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.pinot.controller.api.access.AccessType;
import org.apache.pinot.controller.api.access.Authenticate;
import org.apache.pinot.controller.api.exception.ControllerApplicationException;
import org.apache.pinot.controller.helix.core.PinotHelixResourceManager;
import org.apache.pinot.core.auth.Actions;
import org.apache.pinot.core.auth.Authorize;
import org.apache.pinot.core.auth.TargetType;
import org.apache.pinot.segment.local.function.GroovyStaticAnalyzerConfig;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.spi.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.spi.utils.CommonConstants.SWAGGER_AUTHORIZATION_KEY;


@Api(tags = Constants.CLUSTER_TAG, authorizations = {@Authorization(value = SWAGGER_AUTHORIZATION_KEY)})
@SwaggerDefinition(securityDefinition = @SecurityDefinition(apiKeyAuthDefinitions = @ApiKeyAuthDefinition(name =
    HttpHeaders.AUTHORIZATION, in = ApiKeyAuthDefinition.ApiKeyLocation.HEADER, key = SWAGGER_AUTHORIZATION_KEY,
    description = "The format of the key is  ```\"Basic <token>\" or \"Bearer <token>\"```")))
@Path("/")
public class PinotClusterConfigs {
  private static final Logger LOGGER = LoggerFactory.getLogger(PinotClusterConfigs.class);
  public static final List<String> GROOVY_STATIC_ANALYZER_CONFIG_LIST = List.of(
      CommonConstants.Groovy.GROOVY_ALL_STATIC_ANALYZER_CONFIG,
      CommonConstants.Groovy.GROOVY_INGESTION_STATIC_ANALYZER_CONFIG,
      CommonConstants.Groovy.GROOVY_QUERY_STATIC_ANALYZER_CONFIG
  );

  @Inject
  PinotHelixResourceManager _pinotHelixResourceManager;

  @GET
  @Path("/cluster/info")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.GET_CLUSTER_CONFIG)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Get cluster Info", notes = "Get cluster Info")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String getClusterInfo() {
    ObjectNode ret = JsonUtils.newObjectNode();
    ret.put("clusterName", _pinotHelixResourceManager.getHelixClusterName());
    return ret.toString();
  }

  @GET
  @Path("/cluster/configs")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.GET_CLUSTER_CONFIG)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "List cluster configurations", notes = "List cluster level configurations")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String listClusterConfigs() {
    HelixAdmin helixAdmin = _pinotHelixResourceManager.getHelixAdmin();
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER)
        .forCluster(_pinotHelixResourceManager.getHelixClusterName()).build();
    List<String> configKeys = helixAdmin.getConfigKeys(configScope);
    ObjectNode ret = JsonUtils.newObjectNode();
    Map<String, String> configs = helixAdmin.getConfig(configScope, configKeys);
    for (String key : configs.keySet()) {
      ret.put(key, configs.get(key));
    }
    return ret.toString();
  }

  @POST
  @Path("/cluster/configs")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.UPDATE_CLUSTER_CONFIG)
  @Authenticate(AccessType.UPDATE)
  @ApiOperation(value = "Update cluster configuration")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Server error updating configuration")
  })
  public SuccessResponse updateClusterConfig(String body) {
    try {
      JsonNode jsonNode = JsonUtils.stringToJsonNode(body);
      HelixAdmin admin = _pinotHelixResourceManager.getHelixAdmin();
      HelixConfigScope configScope =
          new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).forCluster(
              _pinotHelixResourceManager.getHelixClusterName()).build();
      Iterator<String> fieldNamesIterator = jsonNode.fieldNames();
      Map<String, String> properties = new TreeMap<>();
      while (fieldNamesIterator.hasNext()) {
        String key = fieldNamesIterator.next();
        JsonNode valueNode = jsonNode.get(key);
        properties.put(key, valueNode.isNull() ? null : valueNode.asText());
      }
      admin.setConfig(configScope, properties);
      return new SuccessResponse("Updated cluster config.");
    } catch (IOException e) {
      throw new ControllerApplicationException(LOGGER, "Error converting request to cluster config.",
          Response.Status.BAD_REQUEST, e);
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, "Failed to update cluster config.",
          Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @DELETE
  @Path("/cluster/configs/{configName}")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.DELETE_CLUSTER_CONFIG)
  @Authenticate(AccessType.DELETE)
  @ApiOperation(value = "Delete cluster configuration")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Server error deleting configuration")
  })
  public SuccessResponse deleteClusterConfig(
      @ApiParam(value = "Name of the config to delete", required = true) @PathParam("configName") String configName) {
    try {
      HelixAdmin admin = _pinotHelixResourceManager.getHelixAdmin();
      HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER)
          .forCluster(_pinotHelixResourceManager.getHelixClusterName()).build();
      admin.removeConfig(configScope, Collections.singletonList(configName));
      return new SuccessResponse("Deleted cluster config: " + configName);
    } catch (Exception e) {
      String errStr = "Failed to delete cluster config: " + configName;
      throw new ControllerApplicationException(LOGGER, errStr, Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @GET
  @Path("/cluster/configs/groovy/staticAnalyzerConfig")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.GET_GROOVY_STATIC_ANALYZER_CONFIG)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Get the configuration for Groovy Static analysis",
      notes = "Get the configuration for Groovy static analysis")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String getGroovyStaticAnalysisConfig()
      throws Exception {
    HelixAdmin helixAdmin = _pinotHelixResourceManager.getHelixAdmin();
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER)
        .forCluster(_pinotHelixResourceManager.getHelixClusterName()).build();
    Map<String, String> configs = helixAdmin.getConfig(configScope, GROOVY_STATIC_ANALYZER_CONFIG_LIST);
    if (configs == null) {
      return null;
    }

    Map<String, GroovyStaticAnalyzerConfig> groovyStaticAnalyzerConfigMap = new HashMap<>();
    for (Map.Entry<String, String> entry : configs.entrySet()) {
      groovyStaticAnalyzerConfigMap.put(entry.getKey(), GroovyStaticAnalyzerConfig.fromJson(entry.getValue()));
    }
    return JsonUtils.objectToString(groovyStaticAnalyzerConfigMap);
  }

  @POST
  @Path("/cluster/configs/groovy/staticAnalyzerConfig")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.UPDATE_GROOVY_STATIC_ANALYZER_CONFIG)
  @Authenticate(AccessType.UPDATE)
  @ApiOperation(value = "Update Groovy static analysis configuration")
  @Produces(MediaType.APPLICATION_JSON)
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Server error updating configuration")
  })
  public SuccessResponse setGroovyStaticAnalysisConfig(Map<String, GroovyStaticAnalyzerConfig> configMap) {
    try {
      HelixAdmin admin = _pinotHelixResourceManager.getHelixAdmin();
      HelixConfigScope configScope =
          new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).forCluster(
              _pinotHelixResourceManager.getHelixClusterName()).build();
      Map<String, String> properties = new TreeMap<>();
      for (Map.Entry<String, GroovyStaticAnalyzerConfig> entry : configMap.entrySet()) {
        String key = entry.getKey();
        if (!GROOVY_STATIC_ANALYZER_CONFIG_LIST.contains(key)) {
          throw new IOException(String.format("Invalid groovy static analysis config: %s. Valid configs are: %s",
              key, GROOVY_STATIC_ANALYZER_CONFIG_LIST));
        }
        properties.put(key, entry.getValue().toJson());
      }
      admin.setConfig(configScope, properties);
      return new SuccessResponse("Updated Groovy Static Analyzer config.");
    } catch (IOException e) {
      throw new ControllerApplicationException(LOGGER, e.getMessage(), Response.Status.BAD_REQUEST, e);
    } catch (Exception e) {
      throw new ControllerApplicationException(LOGGER, "Failed to update Groovy Static Analyzer config",
          Response.Status.INTERNAL_SERVER_ERROR, e);
    }
  }

  @GET
  @Path("/cluster/configs/groovy/staticAnalyzerConfig/default")
  @Authorize(targetType = TargetType.CLUSTER, action = Actions.Cluster.GET_GROOVY_STATIC_ANALYZER_CONFIG)
  @Produces(MediaType.APPLICATION_JSON)
  @ApiOperation(value = "Get the default configuration for Groovy Static analysis",
      notes = "Get the default configuration for Groovy static analysis")
  @ApiResponses(value = {
      @ApiResponse(code = 200, message = "Success"),
      @ApiResponse(code = 500, message = "Internal server error")
  })
  public String getDefaultGroovyStaticAnalysisConfig()
      throws JsonProcessingException {
    return JsonUtils.objectToString(
        Map.of(
            CommonConstants.Groovy.GROOVY_ALL_STATIC_ANALYZER_CONFIG,
            GroovyStaticAnalyzerConfig.createDefault())
    );
  }
}
