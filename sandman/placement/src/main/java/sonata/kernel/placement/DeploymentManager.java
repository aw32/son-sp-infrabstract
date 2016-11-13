package sonata.kernel.placement;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.apache.log4j.Logger;
import org.openstack4j.api.Builders;
import org.openstack4j.api.OSClient;
import org.openstack4j.model.heat.Stack;
import org.openstack4j.openstack.OSFactory;
import sonata.kernel.VimAdaptor.commons.DeployServiceData;
import sonata.kernel.VimAdaptor.commons.heat.HeatTemplate;
import sonata.kernel.VimAdaptor.commons.vnfd.Unit;
import sonata.kernel.VimAdaptor.commons.vnfd.UnitDeserializer;
import sonata.kernel.placement.config.PlacementConfig;
import sonata.kernel.placement.config.PopResource;
import sonata.kernel.placement.monitor.MonitorManager;
import sonata.kernel.placement.service.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Manages the deployed service
 * One service can be deployed, updated and removed
 */
public class DeploymentManager implements Runnable{

    final static Logger logger = Logger.getLogger(DeploymentManager.class);

    // Maps datacenter name to stack name
    Map<String, String> dcStackMap = new HashMap<String, String>();

    ServiceInstance currentInstance;
    PlacementMapping currentMapping;

    public void run(){
        while (true) {

            try {
                MessageQueueData message = MessageQueue.get_deploymentQ().take();
                if(message.message_type == MessageType.TERMINATE_MESSAGE) {

                    logger.info("Terminate Message");
                    tearDown();
                    return;

                } else if (message.message_type == MessageType.DEPLOY_MESSAGE) {

                    MessageQueueDeployData deployData = (MessageQueueDeployData) message;
                    logger.info("Deploy Message - deploy path "+deployData.deploy_path);
                    deploy(deployData.deploy_path);

                } else if (message.message_type == MessageType.UNDEPLOY_MESSAGE) {

                    logger.info("Undeploy Message");
                    tearDown();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void deploy(String deploy_path){

        try {
            PlacementConfig config = PlacementConfigLoader.loadPlacementConfig();
            PlacementPlugin plugin = PlacementPluginLoader.placementPlugin;

            DeployServiceData data = DescriptorTranslator.process_descriptor(deploy_path);

            String serviceName = data.getNsd().getName();

            ServiceInstance instance = plugin.initialScaling(data);
            PlacementMapping mapping = plugin.initialPlacement(data, instance, config.getResources());
            List<HeatTemplate> templates = ServiceHeatTranslator.translatePlacementMappingToHeat(instance, config.getResources(), mapping);

            SimpleDateFormat format = new SimpleDateFormat("yymmddHHmmss");
            String timestamp = format.format(new Date());

            List<PopResource> popList = new ArrayList<PopResource>();
            popList.addAll(mapping.popMapping.values());
            for (int i = 0; i < popList.size(); i++) {
                PopResource pop = popList.get(i);
                String popName = pop.getPopName();
                String stackName = timestamp + "-" + serviceName;

                HeatTemplate template = templates.get(i);

                String templateStr = templateToJson(template);

                logger.debug("Template for datacenter: "+popName);
                logger.debug(templateStr);

                deployStack(pop, stackName, templateStr);

                dcStackMap.put(popName, stackName);
            }

            // TODO: deploy LinkChains

            // TODO: start monitoring

            currentInstance = instance;
            currentMapping = mapping;
        } catch(Exception e) {
            logger.error("Deployment failed",e);
        }

    }

    public void deployStack(PopResource pop, String stackName, String templateJsonString){
        OSClient.OSClientV2 os = OSFactory.builderV2()
                .endpoint( pop.getEndpoint())
                .credentials(pop.getUserName(),pop.getPassword())
                .tenantName(pop.getTenantName())
                .authenticate();

        Stack stack = os.heat().stacks().create(Builders.stack()
                .name(stackName)
                .template(templateJsonString)
                .timeoutMins(5L).build());
    }

    public void tearDown(){
        MonitorManager.stopAndRemoveAllMonitors();

        // TODO: unchain

        if(currentMapping != null) {
            List<PopResource> popList = new ArrayList<PopResource>();
            popList.addAll(currentMapping.popMapping.values());
            for (PopResource pop : popList) {
                String stackName = dcStackMap.get(pop.getPopName());
                undeployStack(pop, stackName);
            }
        }
        currentInstance = null;
        currentMapping = null;
        dcStackMap.clear();
    }

    public void undeployStack(PopResource pop, String stackName) {
        OSClient.OSClientV2 os = OSFactory.builderV2()
                .endpoint(pop.getEndpoint())
                .credentials(pop.getUserName(), pop.getPassword())
                .tenantName(pop.getTenantName())
                .authenticate();

        // Get all stacks from datacenter
        List<? extends Stack> stackList = os.heat().stacks().list();

        // Find correct stack
        for (Stack stack : stackList) {
            if (stack.getName().equals(stackName))
                os.heat().stacks().delete(stack.getName(), stack.getId());
        }
    }

    public void monitorMessage(){
        // TODO: update running service here
    }


    // Utility

    static ObjectMapper mapper = new ObjectMapper(new JsonFactory());
    static SimpleModule module = new SimpleModule();

    // Initialize JSON mapper
    static {

        mapper.disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS);
        mapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        mapper.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        module.addDeserializer(Unit.class, new UnitDeserializer());
        mapper.registerModule(module);
        mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);

    }

    public static String templateToJson(HeatTemplate template) {
        try {
            return mapper.writeValueAsString(template);
        } catch (JsonProcessingException e) {
            return null;
        }
    }


}
