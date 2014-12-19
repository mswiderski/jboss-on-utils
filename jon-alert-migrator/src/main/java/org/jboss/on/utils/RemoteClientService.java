package org.jboss.on.utils;

import java.util.HashMap;
import java.util.Map;

import org.rhq.core.domain.auth.Subject;
import org.rhq.core.domain.criteria.MeasurementDefinitionCriteria;
import org.rhq.core.domain.measurement.MeasurementDefinition;
import org.rhq.core.domain.util.PageList;
import org.rhq.enterprise.clientapi.RemoteClient;
import org.rhq.enterprise.server.measurement.MeasurementDefinitionManagerRemote;

public class RemoteClientService {

    private Subject subject;
    
    private RemoteClient remoteClient;
    
    public RemoteClientService(String host, Integer port, String username, String password) {
        
        this.remoteClient = new RemoteClient(host, port);
        try {
            this.subject = remoteClient.login(username, password);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public Map<String, Integer> getMeasurementDefinitionsByName() {
        MeasurementDefinitionManagerRemote manager = this.remoteClient.getProxy(MeasurementDefinitionManagerRemote.class);
        MeasurementDefinitionCriteria criteria = new MeasurementDefinitionCriteria();
        criteria.fetchResourceType(true);
        criteria.setPaging(0, 10000);
        PageList<MeasurementDefinition> data = manager.findMeasurementDefinitionsByCriteria(subject, criteria);
        
        Map<String, Integer> definitions = new HashMap<String, Integer>();
        
        for (MeasurementDefinition def : data) {
            // included resource type in the key to avoid fetching wrong ids
            definitions.put(def.getResourceType().getName()+"-"+def.getName(), def.getId());
        }
        
        return definitions;
    }
    
    public Map<Integer, String> getMeasurementDefinitionsById() {
        MeasurementDefinitionManagerRemote manager = this.remoteClient.getProxy(MeasurementDefinitionManagerRemote.class);
        MeasurementDefinitionCriteria criteria = new MeasurementDefinitionCriteria();
        criteria.setPaging(0, 10000);
        PageList<MeasurementDefinition> data = manager.findMeasurementDefinitionsByCriteria(subject, criteria);
        
        Map<Integer, String> definitions = new HashMap<Integer, String>();
        
        for (MeasurementDefinition def : data) {
            if (definitions.containsKey(def.getId())) {
            }
            definitions.put(def.getId(), def.getName());
        }
        
        return definitions;
    }
}
