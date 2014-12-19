package org.jboss.on.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.rhq.enterprise.server.rest.domain.AlertConditionRest;
import org.rhq.enterprise.server.rest.domain.AlertDefinitionRest;
import org.rhq.enterprise.server.rest.domain.AlertNotificationRest;
import org.rhq.enterprise.server.rest.domain.Link;
import org.rhq.enterprise.server.rest.domain.ResourceTypeRest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.json.JSONConfiguration;

public class AlertDefinitionMigrator {
    
    private static final Logger logger = LoggerFactory.getLogger(AlertDefinitionMigrator.class);

    public static void main(String[] args) throws UnsupportedEncodingException {
        if (args.length < 6) {
            System.out.println("###############################################################");
            System.out.println("################# AlertDefinitionMigrator #####################");
            System.out.println("###############################################################");
            System.out.println("Incorrect usage, following parameters must be given:");
            System.out.println("1. username that applies to both source and target system");
            System.out.println("2. password that applies to both source and target system");
            System.out.println("3. source host name");
            System.out.println("4. source port number");
            System.out.println("5. target host name");
            System.out.println("6. target port number");
            System.out.println("7. Optional regex filter for alert definition names to be migrated");
            System.out.println("###############################################################");
            return;
        }
        
        System.setProperty("rhq.client.version-check", "false");
        // required arguments
        String username = args[0];
        String password = args[1];
        
        String sourceHost = args[2];
        Integer sourcePort = Integer.parseInt(args[3]);
        
        String targetHost = args[4];
        Integer targetPort = Integer.parseInt(args[5]);
        
        String filter = args.length > 6?args[6]:null;
        
        // target links
        String targetResourceTypeUrl = "http://" + targetHost + ":" + targetPort + "/rest/resource/type?q=";
        String targetCreateAlertUrl = "http://" + targetHost + ":" + targetPort + "/rest/alert/definitions";
        
        // source links
        String alertDefinitionsUrl = "http://" + sourceHost + ":" + sourcePort + "/rest/alert/definitions?full=true";
 
        // collect measurment definitions via remote client as that is not available via REST and cache it for further use
        RemoteClientService sourceService = new RemoteClientService(sourceHost, sourcePort, username, password);
        Map<Integer, String> sourceMeasurementDefinitions = sourceService.getMeasurementDefinitionsById();
        
        RemoteClientService targetService = new RemoteClientService(targetHost, targetPort, username, password);
        Map<String, Integer> targetMeasurementDefinitions = targetService.getMeasurementDefinitionsByName();
        
        // cache of alert definitions created on new environments with their assigned ids mapped to source ids
        Map<Integer, Integer> createdAlertDefinitions = new HashMap<Integer, Integer>();
        
        // perform REST operations that fetches all alert definitions from source system and applies them to target system directly
        ClientConfig clientConfig = new DefaultClientConfig();
        clientConfig.getFeatures().put(JSONConfiguration.FEATURE_POJO_MAPPING, Boolean.TRUE);

        Client client = Client.create(clientConfig);
        client.addFilter(new HTTPBasicAuthFilter(username, password));

        ClientResponse alertDefinitionsResponse = performGet(client, alertDefinitionsUrl, ClientResponse.class);

        // fail fast when there is no way to get alert definitions
        if (alertDefinitionsResponse.getStatus() != 200) {
            throw new RuntimeException("Failed : Unable to fetch alert definitions from source system. HTTP error code : " + alertDefinitionsResponse.getStatus());
        }

        List<AlertDefinitionRest> data = alertDefinitionsResponse.getEntity(new GenericType<List<AlertDefinitionRest>>(){});
        
        // sort alert definitions to first create alerts that do not have recoveryId set as they are sort of independent
        Collections.sort(data, new AlertDefinitionComparator());
        
        for (AlertDefinitionRest def : data) {
            try {
                if (filter != null && !def.getName().matches(filter)) {
                    continue;
                }
                Link resourceTypeLink = getResourceTypeLink(def);
                if (resourceTypeLink != null) {
                    logger.debug("Found alert definition with name {} and priority {}", def.getName(), def.getPriority());
                    
                    ClientResponse sourceResourceTypeResponse = performGet(client, resourceTypeLink.getHref(), ClientResponse.class);
                    
                    ResourceTypeRest resource = sourceResourceTypeResponse.getEntity(ResourceTypeRest.class);
                    logger.debug("\tAlert definition {} is for resource type {} and resource id {}", def.getName(), resource.getName(), resource.getId());
    
                    String resourceTypeUrl = targetResourceTypeUrl + URLEncoder.encode(resource.getName(), "UTF-8")
                            + "&plugin=" + URLEncoder.encode(resource.getPluginName(), "UTF-8");
    
                    ClientResponse targetResourceTypesResponse = performGet(client, resourceTypeUrl, ClientResponse.class);
                    
                    List<ResourceTypeRest> targetTypes = targetResourceTypesResponse.getEntity(new GenericType<List<ResourceTypeRest>>(){});

                    for (ResourceTypeRest t : targetTypes) {
                        AlertDefinitionRest toBeCreated = createFromTemplate(t.getName(), def, sourceMeasurementDefinitions, targetMeasurementDefinitions, createdAlertDefinitions);
                        
                        String createAlertDefUrl = targetCreateAlertUrl+"?resourceTypeId="+t.getId();
                        ClientResponse newAlertDefinitionResponse = performPost(client, createAlertDefUrl, toBeCreated, ClientResponse.class);
                        if (newAlertDefinitionResponse.getStatus() < 300) {
                        
                            AlertDefinitionRest created = newAlertDefinitionResponse.getEntity(AlertDefinitionRest.class);
                            
                            logger.info("Alert definition {} created for resource type {}", toBeCreated.getName(), t.getName());
                            
                            createdAlertDefinitions.put(def.getId(), created.getId());
                        } else {
                            // something went wrong
                            String created = newAlertDefinitionResponse.getEntity(String.class);
                            JSONObject json = new JSONObject(created);
                            String errorMessage = json.getString("message");
                            logger.error("Unable to migrate alert definition {} due to {}", def.getName(), errorMessage);
                        }
                    }
    
                }
            
            } catch (Exception e) {
                logger.error("Unable to migrate alert definition {} due to {}", def.getName(), e.getMessage(), e);
            }
        }
    }
    
    protected static <T> T performGet(Client client, String url, Class<T> responseType) {
        WebResource webResource = client.resource(url);
        logger.debug("About to perform GET request for url {} ", url);
        T response = webResource
                .accept("application/json")
                .type("application/json").get(responseType);
        logger.debug("GET request completed for url {} with response {}", url, response);
        return response;
    }
    
    protected static <T> T  performPost(Client client, String url, Object entity, Class<T> responseType) {
        WebResource webResource = client.resource(url);
        logger.debug("About to perform POST request for url {} and entity {}", url, entity);
        T response = webResource.accept("application/json")
                .type("application/json").post(responseType, entity);
        logger.debug("POST request completed for url {} with response {}", url, response);

        return response;
    }
    
    protected static Link getResourceTypeLink(AlertDefinitionRest alertDef) {
        for (Link link : alertDef.getLinks()) {
            if (link.getRel().equals("resourceType")) {
                return link;
            }
        }
        return null;
    }

    protected static AlertDefinitionRest createFromTemplate(String resourceType, AlertDefinitionRest template, Map<Integer, String> sourceMeasurementDefinitions, 
            Map<String, Integer> targetMeasurementDefinitions, Map<Integer, Integer> createdAlertDefinitions) {
        AlertDefinitionRest def = new AlertDefinitionRest();
        
        def.setConditionMode(template.getConditionMode());
        def.setDampeningCategory(template.getDampeningCategory());
        def.setDampeningCount(template.getDampeningCount());
        def.setDampeningPeriod(template.getDampeningPeriod());
        def.setDampeningUnit(template.getDampeningUnit());
        def.setEnabled(template.isEnabled());
        def.setName(template.getName());        
        def.setPriority(template.getPriority());
        
        List<AlertConditionRest> conditions = new ArrayList<AlertConditionRest>();
        
        for (AlertConditionRest conditionTemplate : template.getConditions()) {
            AlertConditionRest condition = new AlertConditionRest();
            condition.setCategory(conditionTemplate.getCategory());
            condition.setComparator(conditionTemplate.getComparator());
            
            // fetch templat from mapping available via remote client
            String mdName = sourceMeasurementDefinitions.get(conditionTemplate.getMeasurementDefinition());
            int targetMdId = targetMeasurementDefinitions.get(resourceType + "-" + mdName);
            
            condition.setMeasurementDefinition(targetMdId);
            condition.setName(conditionTemplate.getName());
            condition.setOption(conditionTemplate.getOption());
            condition.setThreshold(conditionTemplate.getThreshold());
            // TODO - is this really needed ?? - find out trigger id for the condition
            // condition.setTriggerId(conditionTemplate.getTriggerId());
            
            conditions.add(condition);
        }
        def.setConditions(conditions);
        
        List<AlertNotificationRest> notifications = new ArrayList<AlertNotificationRest>();
        
        for (AlertNotificationRest notificationTemplate : template.getNotifications()) {
            
            AlertNotificationRest notification = new AlertNotificationRest();
            notification.setConfig(notificationTemplate.getConfig());
            notification.setSenderName(notificationTemplate.getSenderName());            
            
            notifications.add(notification);
        }
        def.setNotifications(notifications);
        
        if (template.getRecoveryId() > 0) {
            int recoveryId = createdAlertDefinitions.get(template.getRecoveryId());
            def.setRecoveryId(recoveryId);
        }
        
        
        return def;
    }
    
    private static class AlertDefinitionComparator implements Comparator<AlertDefinitionRest> {

        public int compare(AlertDefinitionRest o1, AlertDefinitionRest o2) {
            
            return new Integer(o1.getRecoveryId()).compareTo(new Integer(o2.getRecoveryId()));
        }
        
    }
}
